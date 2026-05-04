package com.dmahony.e220chat

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

internal fun freqStringToChannelOrFallback(freq: String, fallbackChannel: Int): Int {
    val parsed = freq.trim().toDoubleOrNull()
    if (parsed == null || !parsed.isFinite()) return fallbackChannel.coerceIn(0, 80)
    val channel = (parsed - 850.125).roundToInt()
    return if (channel in 0..80) channel else fallbackChannel.coerceIn(0, 80)
}

class ApiException(message: String) : Exception(message)

class E220Repository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("e220_chat_prefs", Context.MODE_PRIVATE)
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val exchangeMutex = Mutex()
    private val stateLock = Any()
    private val tag = "E220ChatRepo"
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isDebuggableApp = (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingConnect: CompletableDeferred<Unit>? = null
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private var pendingDescriptorWrite: CompletableDeferred<Unit>? = null
    private var pendingResponse: CompletableDeferred<String>? = null
    private var responseBuffer = StringBuilder()
    private var cachedDevices: List<BluetoothDeviceInfo> = emptyList()
    private var lastScanCompletedAtMs: Long = 0L
    private var transportLogs: List<TransportLogEntry> = emptyList()
    private var reconnectJob: Job? = null
    private var manualDisconnectRequested = false
    private var activeScanScanner: BluetoothLeScanner? = null
    private var activeScanCallback: ScanCallback? = null

    private val bleV2 = BleUartManager(appContext)
    private val useBinaryTransport = false
    private val binaryChatMessages = mutableListOf<ChatMessage>()
    private var binaryChatSequence = 0
    private var binaryChatReset = false
    private var binaryConfig: BleConfig? = null
    private var binaryStatus: StatusTelemetry? = null
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

    fun getPairedDevices(): List<BluetoothDeviceInfo> = cachedDevices

    fun getTransportLogs(): List<TransportLogEntry> = transportLogs

    private fun hasBluetoothScanPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        else ->
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothConnectPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else ->
            true
    }

    private fun displayBluetoothName(name: String?): String = name?.takeIf { it.isNotBlank() } ?: "Unnamed device"

    @SuppressLint("MissingPermission")
    suspend fun scanBleDevices(scanMillis: Long = 20000L): List<BluetoothDeviceInfo> = withContext(Dispatchers.IO) {
        if (!hasBluetoothScanPermission()) {
            Log.w(tag, "Skipping BLE scan until Bluetooth permissions are granted")
            cachedDevices = emptyList()
            return@withContext emptyList()
        }

        val expectedResults = linkedMapOf<String, BluetoothDeviceInfo>()
        val fallbackResults = linkedMapOf<String, BluetoothDeviceInfo>()

        fun isExpectedName(name: String?): Boolean =
            name != null && (
                name.startsWith(BLE_NAME_PREFIX, ignoreCase = true) ||
                    name.contains("E220", ignoreCase = true) ||
                    name.contains("ESP32", ignoreCase = true)
                )

        fun putDevice(target: MutableMap<String, BluetoothDeviceInfo>, address: String, name: String?) {
            target[address] = BluetoothDeviceInfo(name = displayBluetoothName(name), address = address)
        }

        fun addBondedDevice(device: BluetoothDevice) {
            val name = device.name
            if (isExpectedName(name) || device.address == selectedDeviceAddress) {
                putDevice(expectedResults, device.address, name)
            } else {
                putDevice(fallbackResults, device.address, name)
            }
        }

        fun addScanResult(result: ScanResult) {
            val device = result.device
            val advertisedName = result.scanRecord?.deviceName ?: device.name
            val hasExpectedName = isExpectedName(advertisedName)
            val hasExpectedService = result.scanRecord?.serviceUuids?.any { it.uuid == NUS_SERVICE_UUID } == true
            val isSelectedDevice = device.address == selectedDeviceAddress
            Log.d(
                tag,
                "BLE scan found: addr=${redactBluetoothAddress(device.address)} name=$advertisedName expectedName=$hasExpectedName expectedService=$hasExpectedService"
            )
            if (hasExpectedName || hasExpectedService || isSelectedDevice) {
                putDevice(expectedResults, device.address, advertisedName)
                Log.d(tag, "BLE scan added expected device: ${redactBluetoothAddress(device.address)} (${displayBluetoothName(advertisedName)}) total=${expectedResults.size}")
            } else {
                putDevice(fallbackResults, device.address, advertisedName)
                Log.d(tag, "BLE scan added fallback device: ${redactBluetoothAddress(device.address)} (${displayBluetoothName(advertisedName)}) total=${fallbackResults.size}")
            }
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

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            synchronized(stateLock) {
                activeScanScanner = scanner
                activeScanCallback = scanCallback
            }
            scanner.startScan(emptyList(), settings, scanCallback)
            try {
                delay(scanMillis)
            } finally {
                try {
                    scanner.stopScan(scanCallback)
                } catch (_: Exception) {
                }
                synchronized(stateLock) {
                    if (activeScanCallback === scanCallback) activeScanCallback = null
                    if (activeScanScanner === scanner) activeScanScanner = null
                }
            }
        }

        val chosen = if (expectedResults.isNotEmpty()) expectedResults else fallbackResults
        val discovered = chosen.values.sortedWith(compareBy<BluetoothDeviceInfo> { it.name.lowercase() }.thenBy { it.address })
        cachedDevices = discovered
        lastScanCompletedAtMs = System.currentTimeMillis()
        discovered
    }

    fun stopBleScan() {
        val scanner: BluetoothLeScanner?
        val callback: ScanCallback?
        synchronized(stateLock) {
            scanner = activeScanScanner
            callback = activeScanCallback
            activeScanScanner = null
            activeScanCallback = null
        }
        if (scanner != null && callback != null) {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
        }
    }

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

    suspend fun getChat(sinceSequence: Int = 0): ChatSnapshot {
        if (useBinaryTransport) {
            val reset = binaryChatReset
            binaryChatReset = false
            val outMessages = synchronized(binaryChatMessages) {
                if (sinceSequence <= 0 || reset) {
                    binaryChatMessages.toList()
                } else if (sinceSequence < binaryChatSequence) {
                    val from = sinceSequence.coerceAtLeast(0)
                    if (from >= binaryChatMessages.size) emptyList() else binaryChatMessages.subList(from, binaryChatMessages.size).toList()
                } else {
                    emptyList()
                }
            }
            return ChatSnapshot(sequence = binaryChatSequence, messages = outMessages, reset = reset)
        }
        return E220Protocol.parseChatResponse(exchange(E220Protocol.buildChatRequest(sinceSequence)))
    }

    suspend fun clearChatHistory() {
        if (useBinaryTransport) {
            synchronized(binaryChatMessages) {
                binaryChatMessages.clear()
                binaryChatReset = true
                binaryChatSequence = 0
            }
            return
        }
        exchange(E220Protocol.buildClearChatRequest())
    }

    suspend fun sendMessage(message: String): String {
        if (useBinaryTransport) {
            val destination = parseDestinationUserId()
            bleV2.sendText(destination, message)
            synchronized(binaryChatMessages) {
                binaryChatMessages.add(ChatMessage(text = message, sent = true, delivered = true))
                binaryChatSequence = binaryChatMessages.size
            }
            appendTransportLog(TransportDirection.SENT, "TEXT dst=${destination.toString(16).padStart(6, '0')} len=${message.length}")
            return "queued"
        }
        val response = exchange(E220Protocol.buildSendRequest(message))
        return E220Protocol.parseSendAcknowledgement(response)
    }

    suspend fun getConfig(): E220Config {
        if (useBinaryTransport) {
            val cfg = bleV2.readConfigCharacteristic()
            binaryConfig = cfg
            return mapBleConfigToLegacy(cfg)
        }
        return E220Protocol.parseConfigResponse(exchange(E220Protocol.buildConfigGetRequest()))
    }

    suspend fun saveConfig(config: E220Config): E220Config = withContext(Dispatchers.IO) {
        if (useBinaryTransport) {
            val cfg = mapLegacyConfigToBle(config)
            bleV2.writeConfig(cfg)
            val live = bleV2.readConfigCharacteristic()
            binaryConfig = live
            return@withContext mapBleConfigToLegacy(live)
        }

        val response = exchange(E220Protocol.buildConfigRequest(config))
        if (E220Protocol.hasConfigPayload(response)) {
            return@withContext E220Protocol.parseConfigResponse(response)
        }

        val deadlineMs = System.currentTimeMillis() + CONFIG_APPLY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadlineMs) {
            delay(300)
            val operation = runCatching { getOperation() }.getOrNull()
            if (operation == null || operation.type != "apply_config") continue
            when (operation.state) {
                "success", "idle" -> return@withContext getConfig()
                "error" -> throw ApiException(operation.message.ifBlank { "Config apply failed" })
            }
        }
        getConfig()
    }

    suspend fun getOperation(): OperationStatus {
        if (useBinaryTransport) {
            val st = binaryStatus
            val msg = when (st?.flowState) {
                FlowState.BUSY -> "busy"
                FlowState.TX_IN_PROGRESS -> "tx_in_progress"
                FlowState.TX_DONE -> "tx_done"
                FlowState.TX_FAILED -> "tx_failed"
                else -> "ready"
            }
            return OperationStatus(type = "ble_v2", state = "idle", message = msg, updatedAtMs = System.currentTimeMillis(), rawResult = "{}")
        }
        return E220Protocol.parseOperationResponse(exchange(E220Protocol.buildOperationRequest()))
    }

    suspend fun reboot() {
        if (useBinaryTransport) {
            throw ApiException("Reboot API is not supported by BLE v2 firmware")
        }
        exchange(E220Protocol.buildRebootRequest())
    }

    suspend fun getDiagnostics(): Diagnostics {
        if (useBinaryTransport) {
            val st = binaryStatus
            return Diagnostics(
                e220Timeouts = 0,
                e220RxErrors = 0,
                e220TxErrors = if (st?.flowState == FlowState.TX_FAILED) 1 else 0,
                uptimeMs = (st?.uptimeSec ?: 0L) * 1000L,
                freeHeap = 0,
                minFreeHeap = 0,
                btName = selectedDeviceName.orEmpty(),
                btHasClient = isConnected,
                btRequestCount = binaryChatSequence,
                btParseErrors = 0,
                btRawMessageCount = binaryChatSequence,
                lastRssi = st?.lastRssi ?: 0
            )
        }
        return E220Protocol.parseDiagnosticsResponse(exchange(E220Protocol.buildDiagnosticsRequest()))
    }
    
    suspend fun getWifiStatus(): WifiStatus {
        if (useBinaryTransport) throw ApiException("WiFi controls aren't supported by this firmware")
        return E220Protocol.parseWifiStatus(exchange(E220Protocol.buildWifiGetRequest()))
    }

    suspend fun setWifiEnabled(enabled: Boolean): WifiStatus {
        if (useBinaryTransport) throw ApiException("WiFi controls aren't supported by this firmware")
        return E220Protocol.parseWifiStatus(exchange(E220Protocol.buildWifiToggleRequest(enabled)))
    }

    suspend fun scanWifi(): WifiScanResult = withContext(Dispatchers.IO) {
        if (useBinaryTransport) throw ApiException("WiFi controls aren't supported by this firmware")
        exchangeMutex.withLock {
            ensureConnectedLocked()
            executeExchangeLocked(E220Protocol.buildWifiScanRequest())
        }

        val deadlineMs = System.currentTimeMillis() + WIFI_SCAN_TIMEOUT_MS
        var pollDelayMs = WIFI_SCAN_INITIAL_POLL_DELAY_MS
        while (System.currentTimeMillis() < deadlineMs) {
            val operation = runCatching { getOperation() }.getOrNull()
            if (operation != null && operation.type == "wifi_scan") {
                when (operation.state) {
                    "success", "error" -> return@withContext E220Protocol.parseWifiScanResult(operation)
                }
            }
            delay(pollDelayMs)
            pollDelayMs = (pollDelayMs + WIFI_SCAN_POLL_BACKOFF_MS).coerceAtMost(WIFI_SCAN_MAX_POLL_DELAY_MS)
        }
        throw ApiException("Timed out waiting for WiFi scan result")
    }

    suspend fun connectWifi(ssid: String, password: String) {
        if (useBinaryTransport) throw ApiException("WiFi controls aren't supported by this firmware")
        exchange(E220Protocol.buildWifiConnectRequest(ssid, password))
    }

    suspend fun disconnectWifi() {
        if (useBinaryTransport) throw ApiException("WiFi controls aren't supported by this firmware")
        exchange(E220Protocol.buildWifiDisconnectRequest())
    }

    suspend fun setWifiAp(password: String) {
        if (useBinaryTransport) throw ApiException("WiFi controls aren't supported by this firmware")
        exchange(E220Protocol.buildWifiApRequest(password))
    }

    suspend fun getDebug(): String {
        if (useBinaryTransport) {
            val st = binaryStatus
            return "BLE v2 connected=$isConnected flow=${st?.flowState} batteryMv=${st?.batteryMv} rssi=${st?.lastRssi} qBleRx=${st?.qBleRx} qBleTx=${st?.qBleTx} qRadioTx=${st?.qRadioTx} qRadioRx=${st?.qRadioRx}"
        }
        return E220Protocol.parseDebugLog(exchange(E220Protocol.buildDebugRequest()))
    }

    suspend fun clearDebug() {
        if (useBinaryTransport) return
        exchange(E220Protocol.buildDebugClearRequest())
    }

    private suspend fun exchange(request: String): String = withContext(Dispatchers.IO) {
        exchangeMutex.withLock {
            retryTransportFailure(
                block = {
                    ensureConnectedLocked()
                    executeExchangeLocked(request)
                },
                onRetry = {
                    appendTransportLog(TransportDirection.INFO, "BLE link stale, reconnecting")
                    connectionEventListener?.invoke(
                        TransportConnectionEvent(
                            state = TransportConnectionState.RECONNECTING,
                            message = "Bluetooth link lost, reconnecting..."
                        )
                    )
                    closeGattLocked()
                    val address = selectedDeviceAddress ?: throw ApiException("Select a nearby E220 BLE device first")
                    reconnectJob?.cancel()
                    reconnectJob = null
                    runBlockingConnect(address)
                }
            )
        }
    }

    private suspend fun executeExchangeLocked(request: String): String {
        appendTransportLog(TransportDirection.SENT, request)
        val line = writeRequestAndAwaitResponseLocked(request)
        appendTransportLog(TransportDirection.RECEIVED, line)
        return line
    }

    private suspend fun writeRequestAndAwaitResponseLocked(requestText: String): String {
        val gatt = bluetoothGatt ?: throw IOException("BLE GATT not connected")
        val characteristic = rxCharacteristic ?: throw IOException("BLE write characteristic not ready")
        val responseDeferred = CompletableDeferred<String>()
        synchronized(stateLock) {
            responseBuffer = StringBuilder()
            pendingResponse = responseDeferred
        }
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
            synchronized(stateLock) {
                if (pendingResponse === responseDeferred) pendingResponse = null
            }
        }
    }

    private fun isDeviceVisibleInRecentScan(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        if (System.currentTimeMillis() - lastScanCompletedAtMs > RECENT_SCAN_VISIBILITY_WINDOW_MS) return false
        return cachedDevices.any { it.address.equals(address, ignoreCase = true) }
    }

    private suspend fun ensureConnectedLocked() {
        if (isConnected) return
        val address = selectedDeviceAddress ?: throw ApiException("Select a nearby E220 BLE device first")
        if (!isDeviceVisibleInRecentScan(address)) {
            throw ApiException("Saved BLE device is not visible in the current scan. Refresh Bluetooth devices and select the ESP32 again.")
        }

        val activeReconnectJob = reconnectJob
        if (activeReconnectJob?.isActive == true) {
            withTimeoutOrNull(AUTO_RECONNECT_WAIT_MS) {
                activeReconnectJob.join()
            }
            if (isConnected) return
        }

        // Avoid rapid reconnect loops: check if we just disconnected
        delay(100)
        runBlockingConnect(address)
    }

    @SuppressLint("MissingPermission")
    private suspend fun runBlockingConnect(address: String) {
        val device = adapter?.getRemoteDevice(address)
            ?: throw ApiException("Bluetooth LE is not available on this device")
        connectWithRetryLocked(device)
    }

    private suspend fun connectWithRetryLocked(device: BluetoothDevice): BluetoothDeviceInfo {
        val deviceName = displayBluetoothName(device.name)
        var lastError: Exception? = null
        repeat(CONNECT_MAX_ATTEMPTS) { attempt ->
            stopBleScan()
            if (attempt > 0) {
                appendTransportLog(TransportDirection.INFO, "Retrying BLE connect (${attempt + 1}/$CONNECT_MAX_ATTEMPTS)")
                delay(CONNECT_RETRY_BACKOFF_MS)
            }
            try {
                return connectGattOnceLocked(device, deviceName)
            } catch (e: Exception) {
                lastError = e
                closeGattLocked()
                if (isBluetoothCacheStaleError(e)) {
                    throw e
                }
            }
        }
        throw ApiException(lastError?.message ?: "Failed to connect to Bluetooth LE device")
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectGattOnceLocked(device: BluetoothDevice, deviceName: String): BluetoothDeviceInfo {
        closeGattLocked(triggerDisconnect = false)
        delay(CONNECT_RETRY_DELAY_MS)
        val connectDeferred = CompletableDeferred<Unit>()
        pendingConnect = connectDeferred
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        } ?: throw ApiException("Failed to start BLE connection")
        bluetoothGatt = gatt
        appendTransportLog(TransportDirection.INFO, "Connecting to $deviceName")
        connectionEventListener?.invoke(
            TransportConnectionEvent(
                state = TransportConnectionState.CONNECTING,
                message = "Connecting to $deviceName..."
            )
        )
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { connectDeferred.await() }
        } catch (e: Exception) {
            closeGattLocked()
            throw ApiException(e.message ?: "Failed to connect to Bluetooth LE device")
        }
        appendTransportLog(TransportDirection.INFO, "Connected to $deviceName")
        connectionEventListener?.invoke(
            TransportConnectionEvent(
                state = TransportConnectionState.CONNECTED,
                message = "Connected to $deviceName"
            )
        )
        return BluetoothDeviceInfo(name = deviceName, address = device.address)
    }

    private fun closeGattLocked(triggerDisconnect: Boolean = true) {
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
        val currentGatt = bluetoothGatt
        try {
            if (triggerDisconnect) {
                refreshGattCache()
            }
        } catch (_: Exception) {
        }
        try {
            if (triggerDisconnect) {
                currentGatt?.disconnect()
            }
        } catch (_: Exception) {
        }
        try {
            currentGatt?.close()
        } catch (_: Exception) {
        }
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
    }

    private fun refreshGattCache(): Boolean {
        val gatt = bluetoothGatt ?: return false
        return try {
            val refresh = gatt.javaClass.getMethod("refresh")
            refresh.isAccessible = true
            refresh.invoke(gatt) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    private fun handleUnexpectedDisconnect(gatt: BluetoothGatt, status: Int) {
        synchronized(stateLock) {
            pendingResponse?.completeExceptionally(IOException("BLE disconnected"))
            pendingWrite?.completeExceptionally(IOException("BLE disconnected"))
            pendingDescriptorWrite?.completeExceptionally(IOException("BLE disconnected"))
        }
        if (pendingConnect?.isCompleted == false) {
            pendingConnect?.completeExceptionally(IOException("BLE disconnected"))
        }
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
        try {
            gatt.close()
        } catch (_: Exception) {
        }

        val address = selectedDeviceAddress
        val shouldReconnect = !manualDisconnectRequested && !address.isNullOrBlank()
        appendTransportLog(
            TransportDirection.INFO,
            if (status == BluetoothGatt.GATT_SUCCESS) {
                "BLE disconnected"
            } else {
                "BLE disconnected ($status)"
            }
        )
        connectionEventListener?.invoke(
            TransportConnectionEvent(
                state = if (shouldReconnect) TransportConnectionState.RECONNECTING else TransportConnectionState.DISCONNECTED,
                message = if (shouldReconnect) {
                    "Bluetooth link lost, reconnecting..."
                } else {
                    "Bluetooth disconnected"
                },
                manualDisconnect = manualDisconnectRequested
            )
        )

        if (shouldReconnect) {
            scheduleAutoReconnect(address!!)
        }
    }

    private fun scheduleAutoReconnect(address: String) {
        if (manualDisconnectRequested) return
        if (!isDeviceVisibleInRecentScan(address)) {
            appendTransportLog(TransportDirection.INFO, "Skipping BLE auto-reconnect because $address is not visible in the current scan")
            connectionEventListener?.invoke(
                TransportConnectionEvent(
                    state = TransportConnectionState.DISCONNECTED,
                    message = "Saved BLE device is not visible in the current scan"
                )
            )
            return
        }
        if (reconnectJob?.isActive == true) return
        reconnectJob = recoveryScope.launch {
            try {
                var attempt = 0
                while (!manualDisconnectRequested) {
                    if (selectedDeviceAddress != address) return@launch
                    if (!isDeviceVisibleInRecentScan(address)) {
                        appendTransportLog(TransportDirection.INFO, "Stopping BLE auto-reconnect because $address is no longer visible")
                        connectionEventListener?.invoke(
                            TransportConnectionEvent(
                                state = TransportConnectionState.DISCONNECTED,
                                message = "Saved BLE device is not visible in the current scan"
                            )
                        )
                        return@launch
                    }
                    attempt++
                    try {
                        connectionEventListener?.invoke(
                            TransportConnectionEvent(
                                state = TransportConnectionState.RECONNECTING,
                                message = if (attempt == 1) {
                                    "Bluetooth link lost, reconnecting..."
                                } else {
                                    "Retrying Bluetooth reconnect ($attempt)"
                                }
                            )
                        )
                        runBlockingConnect(address)
                        connectionEventListener?.invoke(
                            TransportConnectionEvent(
                                state = TransportConnectionState.CONNECTED,
                                message = "Reconnected to ${selectedDeviceName ?: address}"
                            )
                        )
                        return@launch
                    } catch (e: Exception) {
                        appendTransportLog(TransportDirection.INFO, "Bluetooth reconnect attempt $attempt failed: ${e.message ?: "unknown error"}")
                        if (isBluetoothCacheStaleError(e)) {
                            connectionEventListener?.invoke(
                                TransportConnectionEvent(
                                    state = TransportConnectionState.DISCONNECTED,
                                    message = "Bluetooth cache is stale. Forget this device in Bluetooth settings, then re-pair and reconnect."
                                )
                            )
                            return@launch
                        }
                        if (manualDisconnectRequested) return@launch
                        if (attempt >= MAX_AUTO_RECONNECT_ATTEMPTS) {
                            connectionEventListener?.invoke(
                                TransportConnectionEvent(
                                    state = TransportConnectionState.DISCONNECTED,
                                    message = "Bluetooth reconnect failed"
                                )
                            )
                            return@launch
                        }
                        delay(AUTO_RECONNECT_BACKOFF_MS * attempt)
                    }
                }
            } finally {
                if (reconnectJob?.isActive == false) {
                    reconnectJob = null
                }
            }
        }
    }

    private fun isBluetoothCacheStaleError(e: Exception): Boolean {
        val message = e.message.orEmpty()
        return message.contains("status 133", ignoreCase = true) ||
            message.contains("Bluetooth cache is stale", ignoreCase = true)
    }

    private fun redactBluetoothAddress(value: String): String {
        val parts = value.split(":")
        return if (parts.size == 6 && parts.all { it.length == 2 }) {
            parts.take(3).joinToString(":") + ":**:**:**"
        } else {
            value
        }
    }

    private fun redactSensitiveFields(payload: String): String {
        fun redactField(input: String, fieldName: String): String =
            input.replace(Regex("""(?i)(\"$fieldName\"\s*:\s*\")[^\"]*(\")"""), "$1<redacted>$2")

        var sanitized = payload
        for (field in listOf("password", "wifi_ap_password", "wifi_sta_password")) {
            sanitized = redactField(sanitized, field)
        }
        if (!isDebuggableApp) {
            for (field in listOf("message", "ssid", "wifi_ap_ssid", "wifi_sta_ssid")) {
                sanitized = redactField(sanitized, field)
            }
        }
        sanitized = sanitized.replace(Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")) { matchResult ->
            redactBluetoothAddress(matchResult.value)
        }
        return sanitized
    }

    private fun appendTransportLog(direction: TransportDirection, payload: String) {
        val safePayload = redactSensitiveFields(payload)
        transportLogs = (transportLogs + TransportLogEntry(direction = direction, payload = safePayload)).takeLast(MAX_TRANSPORT_LOGS)
        val prefix = when (direction) {
            TransportDirection.SENT -> "APP -> ESP32"
            TransportDirection.RECEIVED -> "ESP32 -> APP"
            TransportDirection.INFO -> "INFO"
        }
        if (isDebuggableApp || direction == TransportDirection.INFO) {
            Log.d(tag, "[$prefix] $safePayload")
        }
    }

    private fun parseDestinationUserId(): Int {
        val cfg = binaryConfig ?: defaultBinaryConfig()
        return cfg.userId24
    }

    private fun defaultBinaryConfig(): BleConfig {
        val address = selectedDeviceAddress.orEmpty().replace(":", "")
        val suffix = if (address.length >= 6) address.takeLast(6) else "000001"
        val id = suffix.toIntOrNull(16)?.coerceIn(1, 0xFFFFFF) ?: 1
        val user = "u${suffix.uppercase()}".take(19)
        return BleConfig(
            ackTimeoutMs = 180,
            maxRetries = 4,
            radioTxIntervalMs = 90,
            statusIntervalMs = 1000,
            profileIntervalSec = 900,
            userId24 = id,
            username = user,
            channel = 0,
            txpower = 21,
            baud = 9600,
            parity = 0,
            airrate = 2,
            txmode = 0,
            lbt = 0,
            subpkt = 0,
            rssiNoise = 0,
            rssiByte = 0,
            urxt = 3,
            worCycle = 3,
            cryptH = 0,
            cryptL = 0,
            saveType = 1,
            addr = id,
            dest = 0xFFFF
        )
    }

    private fun channelToFreqString(channel: Int): String = String.format(java.util.Locale.US, "%.3f", 850.125 + channel.coerceIn(0, 80))

    private fun parseHex16(value: String, fallback: Int): Int =
        value.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.coerceIn(0, 0xFFFF) ?: fallback

    private fun parseHex24(value: String, fallback: Int): Int =
        value.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.coerceIn(1, 0xFFFFFF) ?: fallback

    private fun mapBleConfigToLegacy(cfg: BleConfig): E220Config {
        return E220Config(
            freq = channelToFreqString(cfg.channel),
            txpower = cfg.txpower.toString(),
            baud = cfg.baud.toString(),
            parity = cfg.parity.toString(),
            airrate = cfg.airrate.toString(),
            txmode = cfg.txmode.toString(),
            lbt = cfg.lbt.toString(),
            subpkt = cfg.subpkt.toString(),
            rssiNoise = cfg.rssiNoise.toString(),
            rssiByte = cfg.rssiByte.toString(),
            urxt = cfg.maxRetries.toString(),
            worCycle = cfg.radioTxIntervalMs.toString(),
            cryptH = cfg.cryptH.toString(),
            cryptL = cfg.cryptL.toString(),
            lbrTimeout = cfg.ackTimeoutMs.toString(),
            lbrRssi = cfg.statusIntervalMs.toString(),
            saveType = cfg.profileIntervalSec.toString(),
            addr = "0x${cfg.addr.toString(16).padStart(4, '0').uppercase()}",
            dest = "0x${cfg.dest.toString(16).padStart(4, '0').uppercase()}",
            wifiEnabled = cfg.wifiEnabled.toString(),
            wifiMode = when (cfg.wifiMode) {
                1 -> "STA"
                2 -> "AP_STA"
                else -> "AP"
            },
            wifiApSsid = cfg.wifiApSsid,
            wifiApPassword = cfg.wifiApPassword,
            wifiStaSsid = cfg.wifiStaSsid,
            wifiStaPassword = cfg.wifiStaPassword
        )
    }

    private fun mapLegacyConfigToBle(config: E220Config): BleConfig {
        val current = binaryConfig ?: defaultBinaryConfig()
        val userId = parseHex24(config.addr, current.userId24)
        val username = (config.wifiApSsid.ifBlank { current.username }).take(19)
        return BleConfig(
            ackTimeoutMs = config.lbrTimeout.toIntOrNull()?.coerceIn(60, 2000) ?: current.ackTimeoutMs,
            maxRetries = config.urxt.toIntOrNull()?.coerceIn(1, 10) ?: current.maxRetries,
            radioTxIntervalMs = config.worCycle.toIntOrNull()?.coerceIn(20, 2000) ?: current.radioTxIntervalMs,
            statusIntervalMs = config.lbrRssi.toIntOrNull()?.coerceIn(200, 5000) ?: current.statusIntervalMs,
            profileIntervalSec = config.saveType.toIntOrNull()?.coerceIn(60, 3600) ?: current.profileIntervalSec,
            userId24 = userId,
            username = username,
            channel = freqStringToChannelOrFallback(config.freq, current.channel),
            txpower = config.txpower.toIntOrNull() ?: current.txpower,
            baud = config.baud.toIntOrNull() ?: current.baud,
            parity = config.parity.toIntOrNull() ?: current.parity,
            airrate = config.airrate.toIntOrNull() ?: current.airrate,
            txmode = config.txmode.toIntOrNull() ?: current.txmode,
            lbt = config.lbt.toIntOrNull() ?: current.lbt,
            subpkt = config.subpkt.toIntOrNull() ?: current.subpkt,
            rssiNoise = config.rssiNoise.toIntOrNull() ?: current.rssiNoise,
            rssiByte = config.rssiByte.toIntOrNull() ?: current.rssiByte,
            urxt = config.urxt.toIntOrNull() ?: current.urxt,
            worCycle = config.worCycle.toIntOrNull() ?: current.worCycle,
            cryptH = config.cryptH.toIntOrNull() ?: current.cryptH,
            cryptL = config.cryptL.toIntOrNull() ?: current.cryptL,
            saveType = config.saveType.toIntOrNull() ?: current.saveType,
            addr = parseHex16(config.addr, current.addr),
            dest = parseHex16(config.dest, current.dest),
            wifiEnabled = config.wifiEnabled.toIntOrNull() ?: current.wifiEnabled,
            wifiMode = config.wifiMode.toIntOrNull() ?: current.wifiMode,
            wifiApSsid = config.wifiApSsid,
            wifiApPassword = config.wifiApPassword,
            wifiStaSsid = config.wifiStaSsid,
            wifiStaPassword = config.wifiStaPassword
        )
    }

    private fun handleBinaryFrame(frame: BleFrame) {
        when (frame.type) {
            MsgType.TEXT -> {
                if (frame.payload.size >= 3) {
                    val userId = ((frame.payload[0].toInt() and 0xFF) shl 16) or
                        ((frame.payload[1].toInt() and 0xFF) shl 8) or
                        (frame.payload[2].toInt() and 0xFF)
                    val text = frame.payload.copyOfRange(3, frame.payload.size).toString(Charsets.UTF_8)
                    synchronized(binaryChatMessages) {
                        binaryChatMessages.add(ChatMessage(text = "[RX ${userId.toString(16).padStart(6, '0')}] $text", sent = false, delivered = true))
                        binaryChatSequence = binaryChatMessages.size
                    }
                    appendTransportLog(TransportDirection.RECEIVED, "TEXT src=${userId.toString(16).padStart(6, '0')} len=${text.length}")
                }
            }
            MsgType.PROFILE -> {
                appendTransportLog(TransportDirection.RECEIVED, "PROFILE len=${frame.payload.size}")
            }
            MsgType.CONFIG -> {
                runCatching { BleConfig.fromPayload(frame.payload) }.onSuccess { cfg ->
                    binaryConfig = cfg
                    appendTransportLog(TransportDirection.RECEIVED, "CONFIG ackTimeout=${cfg.ackTimeoutMs} retries=${cfg.maxRetries}")
                }
            }
            MsgType.ERROR -> {
                val code = frame.payload.getOrNull(0)?.toInt()?.and(0xFF) ?: -1
                val origin = frame.payload.getOrNull(1)?.toInt()?.and(0xFF) ?: -1
                appendTransportLog(TransportDirection.INFO, "BLE error code=$code originType=$origin")
            }
            MsgType.STATUS -> {
                runCatching { StatusTelemetry.fromPayload(frame.payload) }.onSuccess { st ->
                    binaryStatus = st
                }
            }
            MsgType.ACK, MsgType.WHOIS -> Unit
        }
    }

    private fun handleIncomingChunk(chunk: String) {
        val completeLine: String? = synchronized(stateLock) {
            if (pendingResponse == null) return
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
                if (status == 133) {
                    appendTransportLog(TransportDirection.INFO, "BLE status 133: refreshing Bluetooth cache")
                    try {
                        refreshGattCache()
                    } catch (_: Exception) {
                    }
                    closeGattLocked(triggerDisconnect = false)
                    pendingConnect?.completeExceptionally(
                        ApiException(
                            "Bluetooth cache is stale (status 133). Forget this device in Bluetooth settings, then re-pair and reconnect."
                        )
                    )
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    handleUnexpectedDisconnect(gatt, status)
                } else {
                    pendingConnect?.completeExceptionally(IOException("BLE connect failed ($status)"))
                }
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
                    handleUnexpectedDisconnect(gatt, status)
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
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
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
        // BLE responses are chunked in 20-byte notifications with a small delay between chunks.
        // Chat/debug history can legitimately take longer than 20 seconds on larger payloads.
        private const val RESPONSE_TIMEOUT_MS = 30000L
        private const val CONFIG_APPLY_TIMEOUT_MS = 12000L
        private const val WIFI_SCAN_TIMEOUT_MS = 60000L
        private const val WIFI_SCAN_INITIAL_POLL_DELAY_MS = 250L
        private const val WIFI_SCAN_POLL_BACKOFF_MS = 150L
        private const val WIFI_SCAN_MAX_POLL_DELAY_MS = 1000L
        private const val CONNECT_TIMEOUT_MS = 20000L
        private const val CONNECT_RETRY_DELAY_MS = 250L
        private const val CONNECT_MAX_ATTEMPTS = 2
        private const val CONNECT_RETRY_BACKOFF_MS = 600L
        private const val AUTO_RECONNECT_WAIT_MS = 10000L
        private const val AUTO_RECONNECT_BACKOFF_MS = 1200L
        private const val MAX_AUTO_RECONNECT_ATTEMPTS = 5
        private const val RECENT_SCAN_VISIBILITY_WINDOW_MS = 10000L
        private const val BLE_NAME_PREFIX = "E220-BLE-"
        private val NUS_SERVICE_UUID: UUID = UUID.fromString("9f6d0001-6f52-4d94-b43f-2ef6f3ed7a10")
        private val NUS_RX_UUID: UUID = UUID.fromString("9f6d0002-6f52-4d94-b43f-2ef6f3ed7a10")
        private val NUS_TX_UUID: UUID = UUID.fromString("9f6d0003-6f52-4d94-b43f-2ef6f3ed7a10")
        private val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}

suspend fun <T> retryTransportFailure(
    block: suspend () -> T,
    onRetry: suspend () -> Unit
): T {
    var lastError: Exception? = null
    repeat(3) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            lastError = e
            val activeContext = runCatching { currentCoroutineContext()[Job]?.isActive == true }.getOrDefault(false)
            val shouldRetry =
                (e is java.util.concurrent.CancellationException && activeContext) ||
                    e is IOException ||
                    e is TimeoutCancellationException ||
                    e.message?.contains("Timed out waiting", ignoreCase = true) == true ||
                    e.message?.contains("ble", ignoreCase = true) == true ||
                    e.message?.contains("socket", ignoreCase = true) == true
            if (!shouldRetry || attempt == 2) {
                throw e
            }
            delay(500L * (attempt + 1))
            onRetry()
        }
    }
    throw lastError ?: IOException("Transport retry failed")
}

fun readLineWithTimeout(input: java.io.InputStream, timeoutMs: Long): String {
    val deadline = System.currentTimeMillis() + timeoutMs
    val output = java.io.ByteArrayOutputStream()
    while (System.currentTimeMillis() < deadline) {
        val available = try {
            input.available()
        } catch (_: Exception) {
            0
        }
        if (available <= 0) {
            if (output.size() > 0) break
            Thread.sleep(1)
            continue
        }
        val byte = input.read()
        if (byte == -1) break
        if (byte == '\n'.code) break
        if (byte != '\r'.code) output.write(byte)
    }
    return output.toString(Charsets.UTF_8.name())
}
