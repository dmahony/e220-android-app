package com.dmahony.e220chat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

class ApiException(message: String) : Exception(message)

class E220Repository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("e220_chat_prefs", Context.MODE_PRIVATE)
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val exchangeMutex = Mutex()
    private val stateLock = Any()
    private val tag = "E220ChatRepo"

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingConnect: CompletableDeferred<Unit>? = null
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private var pendingDescriptorWrite: CompletableDeferred<Unit>? = null
    private var pendingResponse: CompletableDeferred<String>? = null
    private var responseBuffer = StringBuilder()
    private var cachedDevices: List<BluetoothDeviceInfo> = emptyList()
    private var transportLogs: List<TransportLogEntry> = emptyList()

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
        get() = bluetoothGatt != null && rxCharacteristic != null && txCharacteristic != null

    fun getPairedDevices(): List<BluetoothDeviceInfo> = cachedDevices

    fun getTransportLogs(): List<TransportLogEntry> = transportLogs

    suspend fun scanBleDevices(scanMillis: Long = 10000L): List<BluetoothDeviceInfo> = withContext(Dispatchers.IO) {
        val results = linkedMapOf<String, BluetoothDeviceInfo>()

        fun isExpectedName(name: String?): Boolean =
            name != null && (name.startsWith(BLE_NAME_PREFIX, ignoreCase = true) || name.contains("E220", ignoreCase = true))

        fun addBondedDevice(device: BluetoothDevice) {
            val name = device.name
            if (!isExpectedName(name) && device.address != selectedDeviceAddress) return
            results[device.address] = BluetoothDeviceInfo(name = name ?: device.address, address = device.address)
        }

        fun addScanResult(result: ScanResult) {
            val device = result.device
            val advertisedName = result.scanRecord?.deviceName ?: device.name
            val hasExpectedName = isExpectedName(advertisedName)
            val hasExpectedService = result.scanRecord?.serviceUuids?.any { it.uuid == NUS_SERVICE_UUID } == true
            val isSelectedDevice = device.address == selectedDeviceAddress
            Log.d(
                tag,
                "BLE scan found: addr=${device.address} name=$advertisedName expectedName=$hasExpectedName expectedService=$hasExpectedService"
            )
            if (!hasExpectedName && !hasExpectedService && !isSelectedDevice) {
                Log.d(tag, "BLE scan filtered out: ${device.address}")
                return
            }
            results[device.address] = BluetoothDeviceInfo(name = advertisedName ?: device.address, address = device.address)
            Log.d(tag, "BLE scan added: ${device.address} (${advertisedName ?: "unnamed"}) total=${results.size}")
        }

        adapter?.bondedDevices?.forEach { bonded ->
            addBondedDevice(bonded)
        }

        val scanner = adapter?.bluetoothLeScanner
        if (scanner != null) {
            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    addScanResult(result)
                }

                override fun onBatchScanResults(resultsBatch: MutableList<ScanResult>) {
                    resultsBatch.forEach(::addScanResult)
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(tag, "BLE scan failed with code $errorCode")
                }
            }

            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
                    .build()
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner.startScan(filters, settings, scanCallback)
            try {
                delay(scanMillis)
            } finally {
                try {
                    scanner.stopScan(scanCallback)
                } catch (_: Exception) {
                }
            }
        }

        val discovered = results.values.sortedWith(compareBy<BluetoothDeviceInfo> { it.name.lowercase() }.thenBy { it.address })
        cachedDevices = discovered
        discovered
    }

    suspend fun connect(address: String): BluetoothDeviceInfo = withContext(Dispatchers.IO) {
        exchangeMutex.withLock {
            val device = adapter?.getRemoteDevice(address)
                ?: throw ApiException("Bluetooth LE is not available on this device")
            closeGattLocked()
            val connectDeferred = CompletableDeferred<Unit>()
            pendingConnect = connectDeferred
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, gattCallback)
            } ?: throw ApiException("Failed to start BLE connection")
            bluetoothGatt = gatt
            selectedDeviceAddress = address
            selectedDeviceName = device.name ?: device.address
            appendTransportLog(TransportDirection.INFO, "Connecting to ${selectedDeviceName ?: address}")
            try {
                withTimeout(CONNECT_TIMEOUT_MS) { connectDeferred.await() }
            } catch (e: Exception) {
                closeGattLocked()
                throw ApiException(e.message ?: "Failed to connect to Bluetooth LE device")
            }
            appendTransportLog(TransportDirection.INFO, "Connected to ${selectedDeviceName ?: address}")
            BluetoothDeviceInfo(name = selectedDeviceName ?: device.address, address = address)
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        exchangeMutex.withLock {
            appendTransportLog(TransportDirection.INFO, "Disconnected")
            closeGattLocked()
        }
    }

    suspend fun getChat(): ChatSnapshot = E220Protocol.parseChatResponse(exchange(E220Protocol.buildChatRequest()))

    suspend fun sendMessage(message: String): String {
        val response = exchange(E220Protocol.buildSendRequest(message))
        return E220Protocol.parseSendAcknowledgement(response)
    }

    suspend fun getConfig(): E220Config = E220Protocol.parseConfigResponse(exchange(E220Protocol.buildConfigGetRequest()))

    suspend fun saveConfig(config: E220Config): E220Config = E220Protocol.parseConfigResponse(exchange(E220Protocol.buildConfigRequest(config)))

    suspend fun getOperation(): OperationStatus = E220Protocol.parseOperationResponse(exchange(E220Protocol.buildOperationRequest()))

    suspend fun reboot() {
        exchange(E220Protocol.buildRebootRequest())
    }

    suspend fun getDiagnostics(): Diagnostics = E220Protocol.parseDiagnosticsResponse(exchange(E220Protocol.buildDiagnosticsRequest()))

    suspend fun getDebug(): String = E220Protocol.parseDebugLog(exchange(E220Protocol.buildDebugRequest()))

    suspend fun clearDebug() {
        exchange(E220Protocol.buildDebugClearRequest())
    }

    private suspend fun exchange(request: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        exchangeMutex.withLock {
            retryTransportFailure(
                block = {
                    ensureConnectedLocked()
                    executeExchangeLocked(request)
                },
                onRetry = {
                    appendTransportLog(TransportDirection.INFO, "BLE link stale, reconnecting")
                    closeGattLocked()
                    val address = selectedDeviceAddress ?: throw ApiException("Select a nearby E220 BLE device first")
                    runBlockingConnect(address)
                }
            )
        }
    }

    private suspend fun executeExchangeLocked(request: JSONObject): JSONObject {
        val requestText = request.toString()
        appendTransportLog(TransportDirection.SENT, requestText)
        val line = writeRequestAndAwaitResponseLocked(requestText)
        appendTransportLog(TransportDirection.RECEIVED, line)
        return try {
            E220Protocol.parseEnvelope(line)
        } catch (e: Exception) {
            throw ApiException("Invalid response from ESP32: ${e.message ?: line}")
        }
    }

    private suspend fun writeRequestAndAwaitResponseLocked(requestText: String): String {
        val gatt = bluetoothGatt ?: throw IOException("BLE GATT not connected")
        val characteristic = rxCharacteristic ?: throw IOException("BLE write characteristic not ready")
        val responseDeferred = CompletableDeferred<String>()
        pendingResponse = responseDeferred
        try {
            val payload = (requestText + "\n").toByteArray(Charsets.UTF_8)
            val chunkSize = 20
            var offset = 0
            while (offset < payload.size) {
                val end = minOf(offset + chunkSize, payload.size)
                val chunk = payload.copyOfRange(offset, end)
                val writeDeferred = CompletableDeferred<Unit>()
                pendingWrite = writeDeferred
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (!characteristic.setValue(chunk)) {
                    throw IOException("Failed to stage BLE request chunk")
                }
                if (!gatt.writeCharacteristic(characteristic)) {
                    throw IOException("Failed to write BLE request chunk")
                }
                withTimeout(RESPONSE_TIMEOUT_MS) { writeDeferred.await() }
                offset = end
            }
            return withTimeout(RESPONSE_TIMEOUT_MS) { responseDeferred.await() }
        } finally {
            if (pendingResponse === responseDeferred) pendingResponse = null
        }
    }

    private suspend fun ensureConnectedLocked() {
        if (isConnected) return
        val address = selectedDeviceAddress ?: throw ApiException("Select a nearby E220 BLE device first")
        
        // Avoid rapid reconnect loops: check if we just disconnected
        delay(100) 
        runBlockingConnect(address)
    }

    private suspend fun runBlockingConnect(address: String) {
        val device = adapter?.getRemoteDevice(address)
            ?: throw ApiException("Bluetooth LE is not available on this device")
        val connectDeferred = CompletableDeferred<Unit>()
        pendingConnect = connectDeferred
        closeGattLocked()
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        } ?: throw ApiException("Failed to start BLE connection")
        bluetoothGatt = gatt
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { connectDeferred.await() }
        } catch (e: Exception) {
            closeGattLocked()
            throw ApiException(e.message ?: "Failed to connect to Bluetooth LE device")
        }
    }

    private fun closeGattLocked() {
        synchronized(stateLock) {
            pendingConnect?.cancel()
            pendingWrite?.cancel()
            pendingDescriptorWrite?.cancel()
            pendingResponse?.cancel()
            pendingConnect = null
            pendingWrite = null
            pendingDescriptorWrite = null
            pendingResponse = null
            responseBuffer = StringBuilder()
        }
        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
    }

    private fun appendTransportLog(direction: TransportDirection, payload: String) {
        transportLogs = (transportLogs + TransportLogEntry(direction = direction, payload = payload)).takeLast(MAX_TRANSPORT_LOGS)
        val prefix = when (direction) {
            TransportDirection.SENT -> "APP -> ESP32"
            TransportDirection.RECEIVED -> "ESP32 -> APP"
            TransportDirection.INFO -> "INFO"
        }
        Log.d(tag, "[$prefix] $payload")
    }

    private fun handleIncomingChunk(chunk: String) {
        val completeLine: String? = synchronized(stateLock) {
            responseBuffer.append(chunk)
            val buffer = responseBuffer.toString()
            val newlineIndex = buffer.indexOf('\n')
            if (newlineIndex >= 0) {
                val line = buffer.substring(0, newlineIndex).trimEnd('\r')
                responseBuffer = StringBuilder(buffer.substring(newlineIndex + 1))
                line
            } else {
                null
            }
        }
        if (completeLine != null) {
            pendingResponse?.let { deferred ->
                if (!deferred.isCompleted) deferred.complete(completeLine)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pendingConnect?.completeExceptionally(IOException("BLE connect failed ($status)"))
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    appendTransportLog(TransportDirection.INFO, "BLE connected, discovering services")
                    if (!gatt.discoverServices()) {
                        pendingConnect?.completeExceptionally(IOException("BLE service discovery failed to start"))
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    appendTransportLog(TransportDirection.INFO, "BLE disconnected")
                    pendingConnect?.completeExceptionally(IOException("BLE disconnected"))
                    synchronized(stateLock) {
                        pendingResponse?.completeExceptionally(IOException("BLE disconnected"))
                        pendingWrite?.completeExceptionally(IOException("BLE disconnected"))
                        pendingDescriptorWrite?.completeExceptionally(IOException("BLE disconnected"))
                    }
                    bluetoothGatt = null
                    rxCharacteristic = null
                    txCharacteristic = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pendingConnect?.completeExceptionally(IOException("BLE service discovery failed ($status)"))
                return
            }
            val service = gatt.getService(NUS_SERVICE_UUID)
                ?: run {
                    pendingConnect?.completeExceptionally(IOException("BLE UART service not found"))
                    return
                }
            rxCharacteristic = service.getCharacteristic(NUS_RX_UUID)
            txCharacteristic = service.getCharacteristic(NUS_TX_UUID)
            if (rxCharacteristic == null || txCharacteristic == null) {
                pendingConnect?.completeExceptionally(IOException("BLE UART characteristics not found"))
                return
            }
            val notifyChar = txCharacteristic ?: return
            gatt.setCharacteristicNotification(notifyChar, true)
            val descriptor = notifyChar.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor == null) {
                pendingConnect?.completeExceptionally(IOException("BLE notification descriptor not found"))
                return
            }
            val descriptorDeferred = CompletableDeferred<Unit>()
            pendingDescriptorWrite = descriptorDeferred
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) {
                pendingConnect?.completeExceptionally(IOException("BLE descriptor write failed"))
                return
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingWrite?.complete(Unit)
            } else {
                pendingWrite?.completeExceptionally(IOException("BLE write failed ($status)"))
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingChunk(value.toString(Charsets.UTF_8))
        }

        @Deprecated("Deprecated in Android 13 but still available for older callback signatures")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val value = characteristic.value ?: return
            handleIncomingChunk(value.toString(Charsets.UTF_8))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingDescriptorWrite?.complete(Unit)
                pendingConnect?.complete(Unit)
            } else {
                pendingDescriptorWrite?.completeExceptionally(IOException("BLE notification setup failed ($status)"))
                pendingConnect?.completeExceptionally(IOException("BLE notification setup failed ($status)"))
            }
        }
    }

    companion object {
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_BT_DEVICE_ADDRESS = "bt_device_address"
        private const val KEY_BT_DEVICE_NAME = "bt_device_name"
        private const val MAX_TRANSPORT_LOGS = 200
        private const val RESPONSE_TIMEOUT_MS = 4000L
        private const val CONNECT_TIMEOUT_MS = 12000L
        private const val BLE_NAME_PREFIX = "E220-Chat-"
        private val NUS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NUS_RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NUS_TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}

suspend fun <T> retryTransportFailure(
    block: suspend () -> T,
    onRetry: suspend () -> Unit
): T {
    return try {
        block()
    } catch (e: Exception) {
        if (e is IOException || e.message?.contains("ble", ignoreCase = true) == true || e.message?.contains("socket", ignoreCase = true) == true) {
            // Log is handled by the repository calling this or via internal logs
            delay(500)
            onRetry()
            block()
        } else {
            throw e
        }
    }
}
