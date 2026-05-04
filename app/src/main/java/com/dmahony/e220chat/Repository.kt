package com.dmahony.e220chat

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.dmahony.e220chat.ble.BleConfig
import com.dmahony.e220chat.ble.BleFrame
import com.dmahony.e220chat.ble.BleUartManager
import com.dmahony.e220chat.ble.FlowState
import com.dmahony.e220chat.ble.MsgType
import com.dmahony.e220chat.ble.StatusTelemetry
import com.dmahony.e220chat.ble.TextPacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

class ApiException(message: String) : Exception(message)

class E220Repository(context: Context) {
    internal val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("e220_chat_prefs", Context.MODE_PRIVATE)
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    internal val adapter: BluetoothAdapter? = bluetoothManager.adapter
    internal val exchangeMutex = Mutex()
    internal val stateLock = Any()
    internal val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val isDebuggableApp = (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    internal val tag = "E220ChatRepo"

    internal var bluetoothGatt: BluetoothGatt? = null
    internal var rxCharacteristic: BluetoothGattCharacteristic? = null
    internal var txCharacteristic: BluetoothGattCharacteristic? = null
    internal var pendingConnect: CompletableDeferred<Unit>? = null
    internal var pendingWrite: CompletableDeferred<Unit>? = null
    internal var pendingDescriptorWrite: CompletableDeferred<Unit>? = null
    internal var pendingResponse: CompletableDeferred<String>? = null
    internal var responseBuffer = StringBuilder()
    internal var transportLogs: List<TransportLogEntry> = emptyList()
    internal var reconnectJob: Job? = null
    internal var manualDisconnectRequested = false

    internal val bleV2 = BleUartManager(appContext)
    internal val bleScanner = BleScanner(
        context = appContext,
        bluetoothAdapter = adapter,
        tag = "E220ChatRepo",
        isDebuggableApp = isDebuggableApp,
        selectedDeviceAddressProvider = { selectedDeviceAddress },
        displayBluetoothName = ::displayBluetoothName
    )
    internal val useBinaryTransport = false
    internal val binaryChatMessages = mutableListOf<ChatMessage>()
    internal var binaryChatSequence = 0
    internal var binaryChatReset = false
    internal var binaryConfig: BleConfig? = null
    internal var binaryStatus: StatusTelemetry? = null
    private var lastBinaryConnected = false

    var connectionEventListener: ((TransportConnectionEvent) -> Unit)? = null

    init {
        recoveryScope.launch {
            bleV2.connected.collect { connected ->
                if (connected == lastBinaryConnected) return@collect
                lastBinaryConnected = connected
                if (connected) {
                    appendTransportLog(TransportDirection.INFO, "BLE connected")
                    connectionEventListener?.invoke(
                        TransportConnectionEvent(
                            state = TransportConnectionState.CONNECTED,
                            message = "Bluetooth connected"
                        )
                    )
                } else if (!manualDisconnectRequested && selectedDeviceAddress != null) {
                    appendTransportLog(TransportDirection.INFO, "BLE link lost, reconnecting")
                    connectionEventListener?.invoke(
                        TransportConnectionEvent(
                            state = TransportConnectionState.RECONNECTING,
                            message = "Bluetooth link lost, reconnecting..."
                        )
                    )
                }
            }
        }
        recoveryScope.launch {
            bleV2.status.collect { st ->
                binaryStatus = st
            }
        }
        recoveryScope.launch {
            bleV2.frames.collect { frame ->
                handleBinaryFrame(frame)
            }
        }
    }

    var darkTheme: Boolean
        get() = prefs.getBoolean(KEY_DARK_THEME, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DARK_THEME, value).apply()
        }

    var selectedDeviceAddress: String?
        get() = prefs.getString(KEY_BT_DEVICE_ADDRESS, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_BT_DEVICE_ADDRESS) else putString(KEY_BT_DEVICE_ADDRESS, value)
            }.apply()
        }

    var selectedDeviceName: String?
        get() = prefs.getString(KEY_BT_DEVICE_NAME, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_BT_DEVICE_NAME) else putString(KEY_BT_DEVICE_NAME, value)
            }.apply()
        }

    val isConnected: Boolean
        get() = if (useBinaryTransport) bleV2.connected.value else (bluetoothGatt != null && rxCharacteristic != null && txCharacteristic != null)

    fun getPairedDevices(): List<BluetoothDeviceInfo> = bleScanner.getPairedDevices()

    fun getTransportLogs(): List<TransportLogEntry> = transportLogs

    internal fun hasBluetoothConnectPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else ->
            true
    }

    @SuppressLint("MissingPermission")
    suspend fun scanBleDevices(scanMillis: Long = 20000L): List<BluetoothDeviceInfo> = bleScanner.scanBleDevices(scanMillis)

    fun stopBleScan() = bleScanner.stopBleScan()

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String): BluetoothDeviceInfo = withContext(Dispatchers.IO) {
        if (useBinaryTransport) {
            if (!hasBluetoothConnectPermission()) {
                throw ApiException("Grant Bluetooth permissions first")
            }
            val device = adapter?.getRemoteDevice(address)
                ?: throw ApiException("Bluetooth LE is not available on this device")
            val name = displayBluetoothName(device.name)
            selectedDeviceAddress = address
            selectedDeviceName = name
            manualDisconnectRequested = false
            appendTransportLog(TransportDirection.INFO, "Connecting to $name")
            connectionEventListener?.invoke(
                TransportConnectionEvent(
                    state = TransportConnectionState.CONNECTING,
                    message = "Connecting to $name..."
                )
            )
            bleV2.connect(address)
            appendTransportLog(TransportDirection.INFO, "Connected to $name")
            connectionEventListener?.invoke(
                TransportConnectionEvent(
                    state = TransportConnectionState.CONNECTED,
                    message = "Connected to $name"
                )
            )
            runCatching { bleV2.requestWhois() }
            runCatching { binaryConfig = bleV2.readConfigCharacteristic() }
            return@withContext BluetoothDeviceInfo(name = name, address = address)
        }

        exchangeMutex.withLock {
            manualDisconnectRequested = false
            reconnectJob?.cancel()
            reconnectJob = null
            if (!hasBluetoothConnectPermission()) {
                throw ApiException("Grant Bluetooth permissions first")
            }
            val device = adapter?.getRemoteDevice(address)
                ?: throw ApiException("Bluetooth LE is not available on this device")
            selectedDeviceAddress = address
            selectedDeviceName = device.name ?: device.address
            connectWithRetryLocked(device)
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        if (useBinaryTransport) {
            manualDisconnectRequested = true
            bleV2.disconnect()
            appendTransportLog(TransportDirection.INFO, "Disconnected")
            connectionEventListener?.invoke(
                TransportConnectionEvent(
                    state = TransportConnectionState.DISCONNECTED,
                    message = "Bluetooth disconnected",
                    manualDisconnect = true
                )
            )
            return@withContext
        }

        exchangeMutex.withLock {
            manualDisconnectRequested = true
            reconnectJob?.cancel()
            reconnectJob = null
            appendTransportLog(TransportDirection.INFO, "Disconnected")
            closeGattLocked()
            connectionEventListener?.invoke(
                TransportConnectionEvent(
                    state = TransportConnectionState.DISCONNECTED,
                    message = "Bluetooth disconnected",
                    manualDisconnect = true
                )
            )
        }
    }

    fun dispose() {
        if (useBinaryTransport) {
            bleV2.dispose()
        } else {
            runBlocking(Dispatchers.IO) {
                disconnect()
            }
            bleV2.dispose()
        }
    }

    internal val gattCallback = E220GattCallback(this)

    companion object {
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_BT_DEVICE_ADDRESS = "bt_device_address"
        private const val KEY_BT_DEVICE_NAME = "bt_device_name"
        internal const val MAX_TRANSPORT_LOGS = 200
        // BLE responses are chunked in 20-byte notifications with a small delay between chunks.
        // Chat/debug history can legitimately take longer than 20 seconds on larger payloads.
        internal const val RESPONSE_TIMEOUT_MS = 30000L
        internal const val CONFIG_APPLY_TIMEOUT_MS = 12000L
        internal const val WIFI_SCAN_TIMEOUT_MS = 60000L
        internal const val WIFI_SCAN_INITIAL_POLL_DELAY_MS = 250L
        internal const val WIFI_SCAN_POLL_BACKOFF_MS = 150L
        internal const val WIFI_SCAN_MAX_POLL_DELAY_MS = 1000L
        internal const val CONNECT_TIMEOUT_MS = 20000L
        internal const val CONNECT_RETRY_DELAY_MS = 250L
        internal const val CONNECT_MAX_ATTEMPTS = 2
        internal const val CONNECT_RETRY_BACKOFF_MS = 600L
        internal const val AUTO_RECONNECT_WAIT_MS = 10000L
        internal const val AUTO_RECONNECT_BACKOFF_MS = 1200L
        internal const val MAX_AUTO_RECONNECT_ATTEMPTS = 5
        internal val NUS_SERVICE_UUID: UUID = UUID.fromString("9f6d0001-6f52-4d94-b43f-2ef6f3ed7a10")
        internal val NUS_RX_UUID: UUID = UUID.fromString("9f6d0002-6f52-4d94-b43f-2ef6f3ed7a10")
        internal val NUS_TX_UUID: UUID = UUID.fromString("9f6d0003-6f52-4d94-b43f-2ef6f3ed7a10")
        internal val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
