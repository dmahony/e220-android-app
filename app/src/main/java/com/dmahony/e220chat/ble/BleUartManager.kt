package com.dmahony.e220chat.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

class BleUartManager(context: Context) {
    private val app = context.applicationContext
    private val bluetoothManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val codec = BleFrameCodec()
    private val ioMutex = Mutex()

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var configChar: BluetoothGattCharacteristic? = null
    private var reconnectJob: Job? = null
    private var connectedAddress: String? = null

    private var pendingConnect: CompletableDeferred<Unit>? = null
    private var pendingDiscover: CompletableDeferred<Unit>? = null
    private var pendingMtu: CompletableDeferred<Unit>? = null
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private var pendingDescWrite: CompletableDeferred<Unit>? = null
    private var pendingRead: CompletableDeferred<ByteArray>? = null

    private val ackWaiters = HashMap<UByte, CompletableDeferred<Unit>>()
    private var nextSeq: UByte = 1u

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _frames = MutableSharedFlow<BleFrame>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames: SharedFlow<BleFrame> = _frames.asSharedFlow()

    private val _status = MutableStateFlow<StatusTelemetry?>(null)
    val status: StateFlow<StatusTelemetry?> = _status.asStateFlow()

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("9f6d0001-6f52-4d94-b43f-2ef6f3ed7a10")
        val RX_UUID: UUID = UUID.fromString("9f6d0002-6f52-4d94-b43f-2ef6f3ed7a10")
        val TX_UUID: UUID = UUID.fromString("9f6d0003-6f52-4d94-b43f-2ef6f3ed7a10")
        val STATUS_UUID: UUID = UUID.fromString("9f6d0004-6f52-4d94-b43f-2ef6f3ed7a10")
        val CONFIG_UUID: UUID = UUID.fromString("9f6d0005-6f52-4d94-b43f-2ef6f3ed7a10")
        val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val CONNECT_TIMEOUT_MS = 10000L
        private const val WRITE_TIMEOUT_MS = 3000L
        private const val ACK_TIMEOUT_MS = 350L
        private const val MAX_RETRY = 4
        private const val RECONNECT_DELAY_MS = 700L
        private const val TARGET_MTU = 247
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String) = withContext(Dispatchers.IO) {
        if (!hasConnectPermission()) throw SecurityException("BLUETOOTH_CONNECT not granted")

        val device = adapter?.getRemoteDevice(address) ?: throw IOException("No BLE adapter/device")
        val cd = CompletableDeferred<Unit>()
        val dd = CompletableDeferred<Unit>()

        ioMutex.withLock {
            connectedAddress = address
            reconnectJob?.cancel()
            closeGatt()

            pendingConnect = cd
            pendingDiscover = dd
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(app, false, callback)
            }
        }

        withTimeout(CONNECT_TIMEOUT_MS) {
            cd.await()
            dd.await()
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        scope.launch {
            ioMutex.withLock {
                reconnectJob?.cancel()
                reconnectJob = null
                connectedAddress = null
                closeGatt()
            }
        }
    }

    suspend fun sendText(userId24: Int, text: String): UByte {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(3 + textBytes.size)
        payload[0] = ((userId24 ushr 16) and 0xFF).toByte()
        payload[1] = ((userId24 ushr 8) and 0xFF).toByte()
        payload[2] = (userId24 and 0xFF).toByte()
        textBytes.copyInto(payload, destinationOffset = 3)
        val seq = allocSeq()
        sendReliable(BleFrame(MsgType.TEXT, seq, payload, requireAck = true))
        return seq
    }

    suspend fun requestWhois() {
        val seq = allocSeq()
        sendReliable(BleFrame(MsgType.WHOIS, seq, byteArrayOf(), requireAck = true))
    }

    suspend fun sendProfile(userId24: Int, username: String) {
        val n = username.toByteArray(Charsets.UTF_8).take(32).toByteArray()
        val payload = ByteArray(4 + n.size)
        payload[0] = ((userId24 ushr 16) and 0xFF).toByte()
        payload[1] = ((userId24 ushr 8) and 0xFF).toByte()
        payload[2] = (userId24 and 0xFF).toByte()
        payload[3] = n.size.toByte()
        n.copyInto(payload, 4)
        val seq = allocSeq()
        sendReliable(BleFrame(MsgType.PROFILE, seq, payload, requireAck = true))
    }

    suspend fun writeConfig(cfg: BleConfig) {
        val payload = cfg.toPayload()
        val seq = allocSeq()
        sendReliable(BleFrame(MsgType.CONFIG, seq, payload, requireAck = true))
    }

    suspend fun readConfigCharacteristic(): BleConfig = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val g = gatt ?: throw IOException("Not connected")
            val c = configChar ?: throw IOException("CONFIG characteristic unavailable")
            val rd = CompletableDeferred<ByteArray>()
            pendingRead = rd
            if (!g.readCharacteristic(c)) throw IOException("readCharacteristic failed")
            val value = withTimeout(WRITE_TIMEOUT_MS) { rd.await() }
            BleConfig.fromPayload(value)
        }
    }

    private suspend fun sendReliable(frame: BleFrame) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            ensureConnected()
            val waiter = CompletableDeferred<Unit>()
            ackWaiters[frame.seq] = waiter
            try {
                var attempt = 0
                while (true) {
                    attempt++
                    writeFrame(frame)
                    val ok = runCatching { withTimeout(ACK_TIMEOUT_MS) { waiter.await() } }.isSuccess
                    if (ok) return@withLock
                    if (attempt >= MAX_RETRY) throw IOException("ACK timeout type=${frame.type} seq=${frame.seq}")
                }
            } finally {
                ackWaiters.remove(frame.seq)
            }
        }
    }

    private suspend fun writeFrame(frame: BleFrame) {
        val g = gatt ?: throw IOException("Not connected")
        val c = rxChar ?: throw IOException("RX characteristic unavailable")
        val raw = codec.encode(frame)
        val mtu = (g.device?.let { currentMtu } ?: TARGET_MTU).coerceAtLeast(23)
        val maxChunk = (mtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < raw.size) {
            val end = minOf(offset + maxChunk, raw.size)
            val chunk = raw.copyOfRange(offset, end)
            val wd = CompletableDeferred<Unit>()
            pendingWrite = wd
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = chunk
            if (!g.writeCharacteristic(c)) throw IOException("writeCharacteristic failed")
            withTimeout(WRITE_TIMEOUT_MS) { wd.await() }
            offset = end
        }
    }

    private var currentMtu: Int = 23

    private suspend fun ensureConnected() {
        if (_connected.value && gatt != null && rxChar != null && txChar != null) return
        val addr = connectedAddress ?: throw IOException("No saved BLE address")
        connect(addr)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        pendingConnect?.cancel()
        pendingDiscover?.cancel()
        pendingMtu?.cancel()
        pendingWrite?.cancel()
        pendingDescWrite?.cancel()
        pendingRead?.cancel()
        pendingConnect = null
        pendingDiscover = null
        pendingMtu = null
        pendingWrite = null
        pendingDescWrite = null
        pendingRead = null

        ackWaiters.values.forEach { it.cancel() }
        ackWaiters.clear()

        rxChar = null
        txChar = null
        statusChar = null
        configChar = null
        _connected.value = false

        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(c, true)) throw IOException("setCharacteristicNotification failed")
        val d = c.getDescriptor(CCC_UUID) ?: throw IOException("CCC descriptor missing")
        val dd = CompletableDeferred<Unit>()
        pendingDescWrite = dd
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val res = g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (res != BluetoothStatusCodes.SUCCESS) {
                throw IOException("writeDescriptor failed code=$res")
            }
        } else {
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (!g.writeDescriptor(d)) throw IOException("writeDescriptor failed")
        }
        withTimeout(WRITE_TIMEOUT_MS) { dd.await() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                pendingConnect?.complete(Unit)
                pendingConnect = null
                g.discoverServices()
            } else {
                _connected.value = false
                rxChar = null
                txChar = null
                statusChar = null
                configChar = null
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    maybeReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pendingDiscover?.completeExceptionally(IOException("discoverServices failed=$status"))
                return
            }
            scope.launch {
                ioMutex.withLock {
                    val service: BluetoothGattService = g.getService(SERVICE_UUID)
                        ?: run {
                            pendingDiscover?.completeExceptionally(IOException("Service not found"))
                            return@withLock
                        }
                    rxChar = service.getCharacteristic(RX_UUID)
                    txChar = service.getCharacteristic(TX_UUID)
                    statusChar = service.getCharacteristic(STATUS_UUID)
                    configChar = service.getCharacteristic(CONFIG_UUID)

                    if (rxChar == null || txChar == null || statusChar == null || configChar == null) {
                        pendingDiscover?.completeExceptionally(IOException("Required characteristics missing"))
                        return@withLock
                    }

                    val md = CompletableDeferred<Unit>()
                    pendingMtu = md
                    if (!g.requestMtu(TARGET_MTU)) md.complete(Unit)
                    runCatching { withTimeout(WRITE_TIMEOUT_MS) { md.await() } }

                    enableNotify(g, txChar!!)
                    enableNotify(g, statusChar!!)

                    _connected.value = true
                    pendingDiscover?.complete(Unit)
                    pendingDiscover = null
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) currentMtu = mtu
            pendingMtu?.complete(Unit)
            pendingMtu = null
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val p = pendingWrite ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) p.complete(Unit)
            else p.completeExceptionally(IOException("Characteristic write failed=$status"))
            pendingWrite = null
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) pendingRead?.complete(characteristic.value ?: byteArrayOf())
            else pendingRead?.completeExceptionally(IOException("Characteristic read failed=$status"))
            pendingRead = null
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) pendingRead?.complete(value)
            else pendingRead?.completeExceptionally(IOException("Characteristic read failed=$status"))
            pendingRead = null
        }

        @Suppress("DEPRECATION")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val p = pendingDescWrite ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) p.complete(Unit)
            else p.completeExceptionally(IOException("Descriptor write failed=$status"))
            pendingDescWrite = null
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            handleIncoming(characteristic.uuid, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncoming(characteristic.uuid, value)
        }
    }

    private fun handleIncoming(uuid: UUID, value: ByteArray) {
        if (uuid == STATUS_UUID) {
            runCatching { StatusTelemetry.fromPayload(value) }.onSuccess { _status.value = it }
            return
        }
        if (uuid != TX_UUID) return

        val decoded = codec.decodeStream(value)
        for (frame in decoded) {
            if (frame.type == MsgType.ACK) {
                ackWaiters[frame.seq]?.complete(Unit)
            } else {
                scope.launch {
                    if (frame.type != MsgType.STATUS) {
                        // app->esp ACK via reliable channel
                        runCatching {
                            val ack = BleFrame(MsgType.ACK, frame.seq, byteArrayOf(), requireAck = false)
                            ioMutex.withLock { if (_connected.value) writeFrame(ack) }
                        }
                    }
                    _frames.emit(frame)
                }
            }
        }
    }

    private fun maybeReconnect() {
        val addr = connectedAddress ?: return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(RECONNECT_DELAY_MS)
            runCatching { connect(addr) }
        }
    }

    private fun allocSeq(): UByte {
        if (nextSeq == 0.toUByte()) nextSeq = 1u
        val s = nextSeq
        nextSeq = (nextSeq + 1u).toUByte()
        return s
    }
}
