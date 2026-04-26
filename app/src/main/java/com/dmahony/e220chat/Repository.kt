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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    private var reconnectJob: Job? = null
    private var manualDisconnectRequested = false

    var connectionEventListener: ((TransportConnectionEvent) -> Unit)? = null

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
            target[address] = BluetoothDeviceInfo(name = name?.takeIf { it.isNotBlank() } ?: address, address = address)
        }

        fun addBondedDevice(device: BluetoothDevice) {
            val name = device.name
            if (name == null) return
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
                "BLE scan found: addr=${device.address} name=$advertisedName expectedName=$hasExpectedName expectedService=$hasExpectedService"
            )
            if (hasExpectedName || hasExpectedService || isSelectedDevice) {
                putDevice(expectedResults, device.address, advertisedName)
                Log.d(tag, "BLE scan added expected device: ${device.address} (${advertisedName ?: "unnamed"}) total=${expectedResults.size}")
            } else if (!advertisedName.isNullOrBlank()) {
                putDevice(fallbackResults, device.address, advertisedName)
                Log.d(tag, "BLE scan added fallback device: ${device.address} (${advertisedName}) total=${fallbackResults.size}")
            } else {
                Log.d(tag, "BLE scan ignored unnamed device: ${device.address}")
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

            scanner.startScan(emptyList(), settings, scanCallback)
            try {
                delay(scanMillis)
            } finally {
                try {
                    scanner.stopScan(scanCallback)
                } catch (_: Exception) {
                }
            }
        }

        val chosen = if (expectedResults.isNotEmpty()) expectedResults else fallbackResults
        val discovered = chosen.values.sortedWith(compareBy<BluetoothDeviceInfo> { it.name.lowercase() }.thenBy { it.address })
        cachedDevices = discovered
        discovered
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String): BluetoothDeviceInfo = withContext(Dispatchers.IO) {
        exchangeMutex.withLock {
            manualDisconnectRequested = false
            reconnectJob?.cancel()
            reconnectJob = null
            if (!hasBluetoothConnectPermission()) {
                throw ApiException("Grant Bluetooth permissions first")
            }
            val device = adapter?.getRemoteDevice(address)
                ?: throw ApiException("Bluetooth LE is not available on this device")
            closeGattLocked()
            delay(CONNECT_RETRY_DELAY_MS)
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
            connectionEventListener?.invoke(
                TransportConnectionEvent(
                    state = TransportConnectionState.CONNECTING,
                    message = "Connecting to ${selectedDeviceName ?: address}..."
                )
            )
            try {
                withTimeout(CONNECT_TIMEOUT_MS) { connectDeferred.await() }
            } catch (e: Exception) {
                closeGattLocked()
                throw ApiException(e.message ?: "Failed to connect to Bluetooth LE device")
            }
            appendTransportLog(TransportDirection.INFO, "Connected to ${selectedDeviceName ?: address}")
            connectionEventListener?.invoke(
                TransportConnectionEvent(
                    state = TransportConnectionState.CONNECTED,
                    message = "Connected to ${selectedDeviceName ?: address}"
                )
            )
            BluetoothDeviceInfo(name = selectedDeviceName ?: device.address, address = address)
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
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

    suspend fun getChat(sinceSequence: Int = 0): ChatSnapshot = E220Protocol.parseChatResponse(exchange(E220Protocol.buildChatRequest(sinceSequence)))

    suspend fun clearChatHistory() {
        exchange(E220Protocol.buildClearChatRequest())
    }

    suspend fun sendMessage(message: String): String {
        val response = exchange(E220Protocol.buildSendRequest(message))
        return E220Protocol.parseSendAcknowledgement(response)
    }

    suspend fun getConfig(): E220Config = E220Protocol.parseConfigResponse(exchange(E220Protocol.buildConfigGetRequest()))

    suspend fun saveConfig(config: E220Config): E220Config = withContext(Dispatchers.IO) {
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

    suspend fun getOperation(): OperationStatus = E220Protocol.parseOperationResponse(exchange(E220Protocol.buildOperationRequest()))

    suspend fun reboot() {
        exchange(E220Protocol.buildRebootRequest())
    }

    suspend fun getDiagnostics(): Diagnostics = E220Protocol.parseDiagnosticsResponse(exchange(E220Protocol.buildDiagnosticsRequest()))
    
    suspend fun getWifiStatus(): WifiStatus = E220Protocol.parseWifiStatus(exchange(E220Protocol.buildWifiGetRequest()))

    suspend fun setWifiEnabled(enabled: Boolean): WifiStatus = E220Protocol.parseWifiStatus(
        exchange(E220Protocol.buildWifiToggleRequest(enabled))
    )

    suspend fun scanWifi(): List<WifiNetwork> = withContext(Dispatchers.IO) {
        exchangeMutex.withLock {
            ensureConnectedLocked()
            executeExchangeLocked(E220Protocol.buildWifiScanRequest())
        }

        val deadlineMs = System.currentTimeMillis() + WIFI_SCAN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadlineMs) {
            val operation = getOperation()
            if (operation.type == "wifi_scan") {
                if (operation.state == "success") {
                    return@withContext E220Protocol.parseWifiScanNetworks(operation)
                }
                if (operation.state == "error") {
                    throw ApiException(operation.message.ifBlank { "WiFi scan failed" })
                }
            }
            delay(300)
        }
        throw ApiException("Timed out waiting for WiFi scan result")
    }

    suspend fun connectWifi(ssid: String, password: String) {
        exchange(E220Protocol.buildWifiConnectRequest(ssid, password))
    }

    suspend fun disconnectWifi() {
        exchange(E220Protocol.buildWifiDisconnectRequest())
    }

    suspend fun setWifiAp(password: String) {
        exchange(E220Protocol.buildWifiApRequest(password))
    }

    suspend fun getDebug(): String = E220Protocol.parseDebugLog(exchange(E220Protocol.buildDebugRequest()))

    suspend fun clearDebug() {
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

    private suspend fun ensureConnectedLocked() {
        if (isConnected) return
        val address = selectedDeviceAddress ?: throw ApiException("Select a nearby E220 BLE device first")

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
        val connectDeferred = CompletableDeferred<Unit>()
        pendingConnect = connectDeferred
        closeGattLocked(triggerDisconnect = false)
        delay(CONNECT_RETRY_DELAY_MS)
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
        if (reconnectJob?.isActive == true) return
        reconnectJob = recoveryScope.launch {
            try {
                var attempt = 0
                while (!manualDisconnectRequested) {
                    if (selectedDeviceAddress != address) return@launch
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
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
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
        private const val WIFI_SCAN_TIMEOUT_MS = 15000L
        private const val CONNECT_TIMEOUT_MS = 20000L
        private const val CONNECT_RETRY_DELAY_MS = 250L
        private const val AUTO_RECONNECT_WAIT_MS = 10000L
        private const val AUTO_RECONNECT_BACKOFF_MS = 1200L
        private const val MAX_AUTO_RECONNECT_ATTEMPTS = 5
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
