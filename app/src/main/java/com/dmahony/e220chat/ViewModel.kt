package com.dmahony.e220chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class E220ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = E220Repository(application.applicationContext)
    private var rebootReconnectJob: Job? = null
    private var rebootInProgress = false
    private var chatRefreshJob: Job? = null
    private var chatClearJob: Job? = null
    private var configRefreshJob: Job? = null
    private var diagnosticsRefreshJob: Job? = null
    private var debugRefreshJob: Job? = null

    var selectedTab by mutableStateOf(AppTab.CHAT)
        private set

    var chatMessages by mutableStateOf(listOf<ChatMessage>())
        private set
    var chatError by mutableStateOf<String?>(null)
        private set

    var config by mutableStateOf(E220Config())
        private set
    var configStatus by mutableStateOf<String?>(null)
        private set
    var configError by mutableStateOf<String?>(null)
        private set

    var darkTheme by mutableStateOf(repo.darkTheme)
        private set

    var debugText by mutableStateOf("")
        private set
    var debugEnabled by mutableStateOf(false)
        private set
    var transportLogText by mutableStateOf("")
        private set

    var diagnostics by mutableStateOf(Diagnostics())
        private set
    var diagnosticsError by mutableStateOf<String?>(null)
        private set

    var wifiStatus by mutableStateOf(WifiStatus(enabled = false))
        private set
    var wifiNetworks by mutableStateOf(listOf<WifiNetwork>())
        private set
    var wifiScanResult by mutableStateOf(WifiScanResult())
        private set
    var wifiScanInProgress by mutableStateOf(false)
        private set
    var wifiError by mutableStateOf<String?>(null)
        private set
    var wifiApiSupported by mutableStateOf(true)
        private set

    var operationStatus by mutableStateOf(OperationStatus())
        private set

    var bluetoothDevices by mutableStateOf(listOf<BluetoothDeviceInfo>())
        private set
    var isScanning by mutableStateOf(false)
        private set
    var selectedBluetoothAddress by mutableStateOf(repo.selectedDeviceAddress.orEmpty())
        private set
    var selectedBluetoothName by mutableStateOf(repo.selectedDeviceName.orEmpty())
        private set
    var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
        private set
    var connectionHint by mutableStateOf("Connect to a nearby BLE device")
        private set

    private var lastChatSequence = -1
    private var rebootReconnectGraceUntilMs = 0L
    private var chatSyncMutex = Mutex()
    private var pendingChatClear: CompletableDeferred<Unit>? = null
    private var chatPollJob: Job? = null
    private var debugPollJob: Job? = null
    private var startupAutoConnectAttempted = false

    init {
        repo.connectionEventListener = { event ->
            viewModelScope.launch {
                handleTransportEvent(event)
            }
        }
        if (repo.isConnected) {
            connectionState = ConnectionState.CONNECTED
            connectionHint = selectedBluetoothName.ifBlank { "Bluetooth connected" }
        }
        if (repo.selectedDeviceAddress.isNullOrBlank()) {
            viewModelScope.launch { refreshBluetoothDevices() }
        } else {
            viewModelScope.launch { refreshBluetoothDevices(autoConnectSavedDevice = true) }
        }
        refreshAllIfConnected()
        syncTransportLogs()
        startPolling()
    }

    fun setTab(tab: AppTab) {
        selectedTab = tab
    }

    fun toggleTheme() {
        repo.darkTheme = !repo.darkTheme
        darkTheme = repo.darkTheme
    }

    fun refreshBluetoothDevices(autoConnectSavedDevice: Boolean = false) {
        viewModelScope.launch {
            isScanning = true
            try {
                bluetoothDevices = repo.scanBleDevices()
                if (bluetoothDevices.isEmpty() && repo.selectedDeviceAddress.isNullOrBlank()) {
                    connectionHint = "Grant Bluetooth permissions to scan for the ESP32"
                }
            } catch (e: Exception) {
                bluetoothDevices = emptyList()
                connectionState = ConnectionState.ERROR
                connectionHint = e.message ?: "Bluetooth scan failed"
            } finally {
                isScanning = false
            }
            selectedBluetoothAddress = repo.selectedDeviceAddress.orEmpty()
            selectedBluetoothName = repo.selectedDeviceName.orEmpty()
            if (selectedBluetoothName.isBlank() && selectedBluetoothAddress.isNotBlank()) {
                selectedBluetoothName = bluetoothDevices.firstOrNull { it.address == selectedBluetoothAddress }?.name.orEmpty()
            }
            if (autoConnectSavedDevice && selectedBluetoothAddress.isNotBlank()) {
                val visible = bluetoothDevices.any { it.address.equals(selectedBluetoothAddress, ignoreCase = true) }
                if (visible) {
                    autoConnectLastDevice()
                } else {
                    connectionHint = "Saved BLE device is not visible in the current scan"
                    connectionState = ConnectionState.DISCONNECTED
                }
            }
        }
    }

    fun selectBluetoothDevice(device: BluetoothDeviceInfo) {
        repo.selectedDeviceAddress = device.address
        repo.selectedDeviceName = device.name
        selectedBluetoothAddress = device.address
        selectedBluetoothName = device.name
        connectionHint = "Ready to connect to ${device.name}"
        syncTransportLogs()
    }

    fun autoConnectLastDevice() {
        if (startupAutoConnectAttempted) return
        val address = repo.selectedDeviceAddress.orEmpty()
        if (address.isBlank()) return
        val visible = bluetoothDevices.any { it.address.equals(address, ignoreCase = true) }
        if (!visible) {
            connectionHint = "Saved BLE device is not visible in the current scan"
            connectionState = ConnectionState.DISCONNECTED
            return
        }
        startupAutoConnectAttempted = true
        val name = repo.selectedDeviceName.orEmpty().ifBlank { address }
        connectBluetooth(BluetoothDeviceInfo(name = name, address = address), onError = {})
    }

    fun connectBluetooth(device: BluetoothDeviceInfo, onError: (String) -> Unit, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                connectionState = ConnectionState.CONNECTING
                connectionHint = if (rebootInProgress) {
                    "ESP32 is rebooting, reconnecting..."
                } else {
                    "Connecting to ${device.name}..."
                }
                val connected = repo.connect(device.address)
                selectedBluetoothAddress = connected.address
                selectedBluetoothName = connected.name
                connectionState = ConnectionState.CONNECTED
                rebootInProgress = false
                rebootReconnectGraceUntilMs = System.currentTimeMillis() + 8000L
                rebootReconnectJob?.cancel()
                connectionHint = if (rebootReconnectGraceUntilMs > System.currentTimeMillis()) {
                    "ESP32 is rebooting, reconnecting..."
                } else {
                    "Connected to ${connected.name}"
                }
                configStatus = null
                clearConnectionErrors()
                viewModelScope.launch {
                    delay(1000)
                    refreshConfig()
                    refreshDiagnostics()
                    syncTransportLogs()
                }
                onSuccess()
            } catch (e: Exception) {
                val rebooting = shouldSuppressTransientRebootError(e)
                val cacheStale = isBluetoothCacheStaleError(e)
                connectionState = when {
                    rebooting -> ConnectionState.CONNECTING
                    cacheStale -> ConnectionState.ERROR
                    else -> ConnectionState.ERROR
                }
                val msg = when {
                    rebooting -> "ESP32 is rebooting, reconnecting..."
                    cacheStale -> "Bluetooth cache is stale. Forget this device in Bluetooth settings, then re-pair and reconnect."
                    else -> e.message ?: "Bluetooth connection failed"
                }
                connectionHint = msg
                syncTransportLogs()
                if (!rebooting) onError(msg)
            }
        }
    }


    fun reconnectSavedDevice(onError: (String) -> Unit) {
        val address = selectedBluetoothAddress.ifBlank { repo.selectedDeviceAddress.orEmpty() }
        if (address.isNotBlank()) {
            val name = selectedBluetoothName.ifBlank { repo.selectedDeviceName.orEmpty() }.ifBlank { address }
            connectBluetooth(BluetoothDeviceInfo(name = name, address = address), onError)
        } else {
            onError("No saved BLE device to reconnect")
        }
    }

    fun disconnectBluetooth() {
        viewModelScope.launch {
            try {
                repo.disconnect()
            } catch (_: Exception) {
            }
            rebootInProgress = false
            rebootReconnectJob?.cancel()
            connectionState = ConnectionState.DISCONNECTED
            connectionHint = "Bluetooth disconnected"
            syncTransportLogs()
        }
    }

    fun onBluetoothAdapterStateChanged(enabled: Boolean) {
        if (enabled) {
            viewModelScope.launch {
                try {
                    refreshBluetoothDevices(autoConnectSavedDevice = true)
                } catch (_: Exception) {
                }
            }
        } else {
            viewModelScope.launch {
                try {
                    repo.disconnect()
                } catch (_: Exception) {
                }
                rebootInProgress = false
                rebootReconnectJob?.cancel()
                connectionState = ConnectionState.DISCONNECTED
                connectionHint = "Bluetooth turned off"
                syncTransportLogs()
            }
        }
    }

    fun sendMessage(message: String, onError: (String) -> Unit, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            awaitPendingChatClear()
            chatSyncMutex.withLock {
                try {
                    val trimmed = message.trim()
                    if (trimmed.isEmpty()) {
                        onError("Please enter a message")
                        return@withLock
                    }
                    if (!repo.isConnected) {
                        onError("Connect to BLE first")
                        return@withLock
                    }

                    val chunks = splitMessageForRadio(trimmed)
                    val totalChunks = chunks.size
                    chunks.forEachIndexed { index, chunk ->
                        sendChunkWithRetry(chunk, index, totalChunks)
                        if (index < chunks.lastIndex) {
                            delay(300)
                        }
                    }

                    operationStatus = operationStatus.copy(
                        type = "send",
                        state = "success",
                        message = if (totalChunks == 1) "Message sent" else "Message sent in $totalChunks chunks"
                    )
                    connectionHint = if (totalChunks == 1) {
                        "Sent to radio"
                    } else {
                        "Sent $totalChunks chunks to radio"
                    }
                    if (totalChunks > 1) {
                        delay(400)
                    }
                    if (repo.isConnected) {
                        refreshChatLocked()
                        if (selectedTab == AppTab.DEBUG && debugEnabled) {
                            refreshDebugNow()
                        }
                    }
                    syncTransportLogs()
                    onSuccess()
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) {
                        return@withLock
                    }
                    val transportLoss = isTransportLossError(e)
                    if (transportLoss) {
                        connectionState = ConnectionState.CONNECTING
                        connectionHint = "Bluetooth link lost, reconnecting..."
                        operationStatus = operationStatus.copy(
                            type = "send",
                            state = "running",
                            message = "Bluetooth link lost, reconnecting..."
                        )
                        syncTransportLogs()
                        return@withLock
                    }
                    connectionState = ConnectionState.ERROR
                    connectionHint = e.message ?: "Send failed"
                    operationStatus = operationStatus.copy(
                        type = "send",
                        state = "error",
                        message = e.message ?: "Send failed"
                    )
                    syncTransportLogs()
                    onError(e.message ?: "Failed to send message")
                }
            }
        }
    }

    private suspend fun sendChunkWithRetry(chunk: String, index: Int, totalChunks: Int) {
        var attempt = 0
        while (true) {
            attempt++
            val progress = if (totalChunks == 1) {
                if (attempt == 1) "Sending message" else "Retrying message"
            } else {
                if (attempt == 1) {
                    "Sending chunk ${index + 1}/$totalChunks"
                } else {
                    "Retrying chunk ${index + 1}/$totalChunks (attempt $attempt)"
                }
            }
            operationStatus = operationStatus.copy(type = "send", state = "running", message = progress)
            try {
                repo.sendMessage(chunk)
                return
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                if (!isTransportLossError(e) || attempt >= 3) {
                    throw e
                }
                connectionState = ConnectionState.CONNECTING
                connectionHint = "Bluetooth link lost, reconnecting..."
                operationStatus = operationStatus.copy(type = "send", state = "running", message = "Reconnecting before retrying chunk ${index + 1}/$totalChunks")
                if (!waitForTransportReconnect()) {
                    throw IOException("Bluetooth reconnect timed out")
                }
                delay(250L * attempt)
            }
        }
    }

    private suspend fun waitForTransportReconnect(): Boolean {
        return withTimeoutOrNull(15000L) {
            while (!repo.isConnected) {
                delay(250)
            }
            true
        } == true
    }

    private suspend fun awaitPendingChatClear() {
        val gate = pendingChatClear ?: return
        runCatching {
            withTimeoutOrNull(5000L) {
                gate.await()
            }
        }
    }

    fun clearChatMessages() {
        chatMessages = emptyList()
        chatError = null
        lastChatSequence = -1
        pendingChatClear?.complete(Unit)
        val clearGate = CompletableDeferred<Unit>()
        pendingChatClear = clearGate
        chatClearJob?.cancel()
        chatClearJob = viewModelScope.launch {
            try {
                chatSyncMutex.withLock {
                    if (repo.isConnected) {
                        repo.clearChatHistory()
                        refreshChatLocked()
                    }
                }
            } catch (e: Exception) {
                chatError = e.message ?: "Chat clear failed"
            } finally {
                pendingChatClear = null
                clearGate.complete(Unit)
                chatClearJob = null
            }
        }
    }

    fun refreshChat() {
        if (!repo.isConnected) {
            chatError = null
            return
        }
        if (chatRefreshJob?.isActive == true) return
        chatRefreshJob = viewModelScope.launch {
            try {
                awaitPendingChatClear()
                chatSyncMutex.withLock {
                    refreshChatLocked()
                }
            } finally {
                chatRefreshJob = null
            }
        }
    }

    private suspend fun refreshChatLocked() {
        try {
            val snapshot = repo.getChat(lastChatSequence.coerceAtLeast(0))
            applyChatSnapshot(snapshot)
            chatError = null
            syncTransportLogs()
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) {
                return
            }
            if (shouldSuppressTransientRebootError(e)) {
                setRebootReconnectStatus()
                chatError = null
            } else if (!rebootInProgress) {
                chatError = e.message ?: "Chat refresh failed"
            }
            syncTransportLogs()
        }
    }

    private fun applyChatSnapshot(snapshot: ChatSnapshot) {
        if (snapshot.reset || lastChatSequence < 0 || snapshot.sequence < lastChatSequence) {
            chatMessages = snapshot.messages
        } else if (snapshot.sequence > lastChatSequence) {
            chatMessages = chatMessages + snapshot.messages
        }
        lastChatSequence = snapshot.sequence
    }

    fun refreshConfig() {
        if (!repo.isConnected) {
            configError = null
            return
        }
        if (configRefreshJob?.isActive == true) return
        configStatus = "Loading radio config..."
        configError = null
        configRefreshJob = viewModelScope.launch {
            try {
                config = repo.getConfig()
                configError = null
                configStatus = "Radio config loaded"
                syncTransportLogs()
            } catch (e: Exception) {
                if (shouldSuppressTransientRebootError(e)) {
                    setRebootReconnectStatus()
                    configError = null
                    configStatus = "ESP32 is rebooting, reconnecting..."
                } else if (!rebootInProgress) {
                    configError = e.message ?: "Config load failed"
                    configStatus = null
                }
                syncTransportLogs()
            } finally {
                configRefreshJob = null
            }
        }
    }

    fun setConfigField(field: String, value: String) {
        config = when (field) {
            "freq" -> config.copy(freq = value)
            "txpower" -> config.copy(txpower = value)
            "baud" -> config.copy(baud = value)
            "addr" -> config.copy(addr = value)
            "dest" -> config.copy(dest = value)
            "airrate" -> config.copy(airrate = value)
            "subpkt" -> config.copy(subpkt = value)
            "parity" -> config.copy(parity = value)
            "txmode" -> config.copy(txmode = value)
            "rssi_noise" -> config.copy(rssiNoise = value)
            "rssi_byte" -> config.copy(rssiByte = value)
            "lbt" -> config.copy(lbt = value)
            "lbr_rssi" -> config.copy(lbrRssi = value)
            "lbr_timeout" -> config.copy(lbrTimeout = value)
            "urxt" -> config.copy(urxt = value)
            "wor_cycle" -> config.copy(worCycle = value)
            "crypt_h" -> config.copy(cryptH = value)
            "crypt_l" -> config.copy(cryptL = value)
            "savetype" -> config.copy(saveType = value)
            "wifi_enabled" -> config.copy(wifiEnabled = value)
            "wifi_mode" -> config.copy(wifiMode = value)
            "wifi_ap_ssid" -> config.copy(wifiApSsid = value)
            "wifi_ap_password" -> config.copy(wifiApPassword = value)
            "wifi_sta_ssid" -> config.copy(wifiStaSsid = value)
            "wifi_sta_password" -> config.copy(wifiStaPassword = value)
            else -> config
        }
    }

    fun saveConfig(onError: (String) -> Unit, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                if (!repo.isConnected) {
                    onError("Connect to BLE first")
                    return@launch
                }
                configRefreshJob?.cancel()
                diagnosticsRefreshJob?.cancel()
                config = repo.saveConfig(config)
                configStatus = "Configuration applied"
                configError = null
                operationStatus = repo.getOperation()
                syncTransportLogs()
                onSuccess()
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to save config"
                configError = msg
                syncTransportLogs()
                onError(msg)
            }
        }
    }

    fun quickSave(onError: (String) -> Unit, onSuccess: () -> Unit) {
        saveConfig(onError, onSuccess)
    }

    fun reboot(onError: (String) -> Unit, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                if (!repo.isConnected) {
                    onError("Connect to BLE first")
                    return@launch
                }
                rebootInProgress = true
                rebootReconnectGraceUntilMs = System.currentTimeMillis() + 30000L
                rebootReconnectJob?.cancel()
                configError = null
                chatError = null
                diagnosticsError = null
                configStatus = "ESP32 rebooting. Bluetooth will disconnect briefly and reconnect automatically."
                connectionHint = "ESP32 is rebooting, reconnecting..."
                operationStatus = OperationStatus(type = "reboot", state = "running", message = "ESP32 rebooting")
                syncTransportLogs()
                try {
                    repo.reboot()
                } catch (_: Exception) {
                }
                scheduleReconnectAfterReboot()
                onSuccess()
            } catch (e: Exception) {
                rebootInProgress = false
                syncTransportLogs()
                onError(e.message ?: "Failed to reboot")
            }
        }
    }

    fun refreshDiagnostics() {
        if (!repo.isConnected) {
            diagnosticsError = null
            return
        }
        if (diagnosticsRefreshJob?.isActive == true) return
        diagnosticsRefreshJob = viewModelScope.launch {
            try {
                diagnostics = repo.getDiagnostics()
                diagnosticsError = null
                syncTransportLogs()
            } catch (e: Exception) {
                if (shouldSuppressTransientRebootError(e)) {
                    setRebootReconnectStatus()
                    diagnosticsError = null
                } else if (!rebootInProgress) {
                    diagnosticsError = e.message ?: "Diagnostics failed"
                }
                syncTransportLogs()
            } finally {
                diagnosticsRefreshJob = null
            }
        }
    }

    fun refreshWifi() {
        if (!repo.isConnected) {
            wifiError = null
            return
        }
        if (!wifiApiSupported) {
            wifiError = "WiFi controls aren't supported by this firmware."
            return
        }
        viewModelScope.launch {
            try {
                wifiStatus = readWifiStatus()
                wifiError = null
                syncTransportLogs()
            } catch (e: Exception) {
                if (shouldSuppressTransientRebootError(e)) {
                    setRebootReconnectStatus()
                    wifiError = null
                } else if (isUnsupportedWifiApiError(e)) {
                    wifiApiSupported = false
                    wifiError = "WiFi controls aren't supported by this firmware."
                    wifiNetworks = emptyList()
                } else {
                    wifiError = e.message ?: "WiFi status refresh failed"
                }
                syncTransportLogs()
            }
        }
    }

    fun setWifiEnabled(enabled: Boolean, onError: (String) -> Unit, onSuccess: () -> Unit) {
        if (!repo.isConnected) {
            onError("Connect to BLE first")
            return
        }
        if (!wifiApiSupported) {
            onError("WiFi controls aren't supported by this firmware.")
            return
        }
        viewModelScope.launch {
            try {
                repo.setWifiEnabled(enabled)
                wifiStatus = readWifiStatus()
                wifiNetworks = if (enabled) wifiNetworks else emptyList()
                wifiError = null
                syncTransportLogs()
                onSuccess()
            } catch (e: Exception) {
                if (isUnsupportedWifiApiError(e)) {
                    wifiApiSupported = false
                    wifiError = "WiFi controls aren't supported by this firmware."
                    wifiNetworks = emptyList()
                    onError("WiFi controls aren't supported by this firmware.")
                } else {
                    onError(e.message ?: "WiFi toggle failed")
                }
                syncTransportLogs()
            }
        }
    }

    fun scanWifiNetworks() {
        if (!repo.isConnected) {
            wifiError = "Connect to BLE first"
            return
        }
        if (!wifiApiSupported) {
            wifiError = "WiFi controls aren't supported by this firmware."
            return
        }
        viewModelScope.launch {
            if (wifiScanInProgress) return@launch
            wifiScanInProgress = true
            wifiError = null
            wifiScanResult = WifiScanResult(
                scan = WifiScanInfo(
                    status = "scanning",
                    requestedAtMs = System.currentTimeMillis(),
                    networkCount = wifiNetworks.size
                ),
                networks = wifiNetworks
            )
            try {
                val scan = repo.scanWifi()
                wifiScanResult = scan
                wifiNetworks = scan.networks
                wifiError = formatWifiScanError(scan)
            } catch (e: Exception) {
                if (isUnsupportedWifiApiError(e)) {
                    wifiApiSupported = false
                    wifiError = "WiFi controls aren't supported by this firmware."
                    wifiNetworks = emptyList()
                    wifiScanResult = WifiScanResult()
                } else {
                    wifiError = e.message ?: "WiFi scan failed"
                    wifiScanResult = WifiScanResult(
                        scan = WifiScanInfo(
                            status = "error",
                            error = e.message ?: "WiFi scan failed"
                        )
                    )
                    wifiNetworks = emptyList()
                }
                syncTransportLogs()
            } finally {
                wifiScanInProgress = false
            }
        }
    }

    private fun formatWifiScanError(scan: WifiScanResult): String? {
        return when {
            scan.scan.status.equals("error", ignoreCase = true) -> {
                val code = scan.scan.errorCode?.let { " (ESP32 error $it)" } ?: ""
                val detail = scan.scan.error.ifBlank { scan.scan.status }
                "WiFi scan failed$code: $detail"
            }
            scan.networks.isEmpty() || scan.scan.networkCount == 0 -> {
                "WiFi scan completed, but no networks were found."
            }
            else -> null
        }
    }

    fun connectWifi(ssid: String, password: String, onError: (String) -> Unit, onSuccess: () -> Unit) {
        if (!wifiApiSupported) {
            onError("WiFi controls aren't supported by this firmware.")
            return
        }
        viewModelScope.launch {
            try {
                repo.connectWifi(ssid, password)
                wifiStatus = readWifiStatus()
                wifiError = null
                syncTransportLogs()
                onSuccess()
            } catch (e: Exception) {
                if (isUnsupportedWifiApiError(e)) {
                    wifiApiSupported = false
                    wifiError = "WiFi controls aren't supported by this firmware."
                    wifiNetworks = emptyList()
                    onError("WiFi controls aren't supported by this firmware.")
                } else {
                    onError(e.message ?: "WiFi connection failed")
                }
                syncTransportLogs()
            }
        }
    }

    fun disconnectWifi(onError: (String) -> Unit, onSuccess: () -> Unit) {
        if (!wifiApiSupported) {
            onError("WiFi controls aren't supported by this firmware.")
            return
        }
        viewModelScope.launch {
            try {
                repo.disconnectWifi()
                wifiStatus = readWifiStatus()
                wifiError = null
                syncTransportLogs()
                onSuccess()
            } catch (e: Exception) {
                if (isUnsupportedWifiApiError(e)) {
                    wifiApiSupported = false
                    wifiError = "WiFi controls aren't supported by this firmware."
                    wifiNetworks = emptyList()
                    onError("WiFi controls aren't supported by this firmware.")
                } else {
                    onError(e.message ?: "WiFi disconnect failed")
                }
                syncTransportLogs()
            }
        }
    }

    fun setUsername(name: String, onError: (String) -> Unit, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                if (!repo.isConnected) {
                    onError("Connect to BLE first")
                    return@launch
                }
                val updatedConfig = repo.saveConfig(repo.getConfig().copy(wifiApSsid = name))
                config = updatedConfig
                configStatus = "Username updated to $name"
                syncTransportLogs()
                onSuccess()
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to update username"
                configError = msg
                syncTransportLogs()
                onError(msg)
            }
        }
    }

    fun setWifiApPassword(password: String, onError: (String) -> Unit, onSuccess: () -> Unit) {
        if (!wifiApiSupported) {
            onError("WiFi controls aren't supported by this firmware.")
            return
        }
        viewModelScope.launch {
            try {
                repo.setWifiAp(password)
                wifiStatus = readWifiStatus()
                wifiError = null
                syncTransportLogs()
                onSuccess()
            } catch (e: Exception) {
                if (isUnsupportedWifiApiError(e)) {
                    wifiApiSupported = false
                    wifiError = "WiFi controls aren't supported by this firmware."
                    wifiNetworks = emptyList()
                    onError("WiFi controls aren't supported by this firmware.")
                } else {
                    onError(e.message ?: "WiFi AP update failed")
                }
                syncTransportLogs()
            }
        }
    }

    fun refreshDebugNow() {
        refreshDebug(force = true)
    }

    fun clearDebug() {
        viewModelScope.launch {
            try {
                if (!repo.isConnected) return@launch
                repo.clearDebug()
                debugText = ""
                syncTransportLogs()
            } catch (_: Exception) {
                syncTransportLogs()
            }
        }
    }

    fun updateDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
    }

    private fun shouldSuppressTransientRebootError(e: Exception): Boolean {
        if (rebootInProgress || System.currentTimeMillis() < rebootReconnectGraceUntilMs) return true
        val message = e.message.orEmpty()
        return message.contains("Invalid response from ESP32", ignoreCase = true) ||
            message.contains("unexpected JSON token", ignoreCase = true) ||
            message.contains("JSON parse error", ignoreCase = true) ||
            message.contains("BLE disconnected", ignoreCase = true)
    }

    private fun isTransportLossError(e: Exception): Boolean {
        val message = e.message.orEmpty()
        return message.contains("BLE", ignoreCase = true) ||
            message.contains("GATT", ignoreCase = true) ||
            message.contains("socket", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("disconnected", ignoreCase = true) ||
            message.contains("connect failed", ignoreCase = true)
    }

    private fun handleTransportEvent(event: TransportConnectionEvent) {
        when (event.state) {
            TransportConnectionState.CONNECTED -> {
                rebootInProgress = false
                rebootReconnectGraceUntilMs = 0L
                connectionState = ConnectionState.CONNECTED
                connectionHint = event.message.ifBlank { selectedBluetoothName.ifBlank { "Bluetooth connected" } }
                configStatus = null
                clearConnectionErrors()
                if (operationStatus.type == "send" && operationStatus.state == "running") {
                    operationStatus = operationStatus.copy(message = "Bluetooth reconnected, finishing send...")
                }
                refreshAllIfConnected()
            }
            TransportConnectionState.CONNECTING, TransportConnectionState.RECONNECTING -> {
                connectionState = ConnectionState.CONNECTING
                connectionHint = event.message.ifBlank { "Bluetooth link lost, reconnecting..." }
                if (operationStatus.type == "send" && operationStatus.state == "running") {
                    operationStatus = operationStatus.copy(message = connectionHint)
                }
            }
            TransportConnectionState.DISCONNECTED -> {
                if (event.manualDisconnect) {
                    rebootInProgress = false
                    connectionState = ConnectionState.DISCONNECTED
                    connectionHint = event.message.ifBlank { "Bluetooth disconnected" }
                    if (operationStatus.type == "send" && operationStatus.state == "running") {
                        operationStatus = operationStatus.copy(state = "error", message = "Send stopped because Bluetooth disconnected")
                    }
                } else {
                    connectionState = ConnectionState.ERROR
                    connectionHint = event.message.ifBlank { "Bluetooth reconnect failed" }
                    if (operationStatus.type == "send" && operationStatus.state == "running") {
                        operationStatus = operationStatus.copy(state = "error", message = connectionHint)
                    }
                }
            }
        }
        syncTransportLogs()
    }

    private fun setRebootReconnectStatus() {
        connectionHint = "ESP32 is rebooting, reconnecting..."
    }

    private fun refreshDebug(force: Boolean = false) {
        if (!repo.isConnected) return
        if (!force && !debugEnabled) return
        if (debugRefreshJob?.isActive == true) return
        debugRefreshJob = viewModelScope.launch {
            try {
                debugText = repo.getDebug()
                operationStatus = repo.getOperation()
                syncTransportLogs()
            } catch (e: Exception) {
                if (shouldSuppressTransientRebootError(e)) {
                    setRebootReconnectStatus()
                }
                if (!rebootInProgress) {
                    syncTransportLogs()
                }
            } finally {
                debugRefreshJob = null
            }
        }
    }

    private fun refreshAllIfConnected() {
        if (!repo.isConnected) return
        refreshChat()
        refreshConfig()
        refreshDiagnostics()
        refreshWifi()
        refreshDebug()
    }

    private fun scheduleReconnectAfterReboot() {
        rebootReconnectJob?.cancel()
        rebootReconnectJob = viewModelScope.launch {
            connectionState = ConnectionState.CONNECTING
            repeat(15) {
                bluetoothDevices = repo.scanBleDevices(3000L)
                selectedBluetoothAddress = repo.selectedDeviceAddress.orEmpty()
                selectedBluetoothName = repo.selectedDeviceName.orEmpty().ifBlank {
                    bluetoothDevices.firstOrNull { device -> device.address == selectedBluetoothAddress }?.name.orEmpty()
                }
                val address = selectedBluetoothAddress.ifBlank { repo.selectedDeviceAddress.orEmpty() }
                val device = bluetoothDevices.firstOrNull { it.address == address }
                if (device != null) {
                    try {
                        connectionHint = "ESP32 is rebooting, reconnecting..."
                        val connected = repo.connect(device.address)
                        selectedBluetoothAddress = connected.address
                        selectedBluetoothName = connected.name
                        connectionState = ConnectionState.CONNECTED
                        rebootInProgress = false
                        rebootReconnectGraceUntilMs = System.currentTimeMillis() + 8000L
                        rebootReconnectJob?.cancel()
                        connectionHint = "ESP32 is rebooting, reconnecting..."
                        configStatus = null
                        clearConnectionErrors()
                        delay(750)
                        refreshConfig()
                        refreshDiagnostics()
                        syncTransportLogs()
                        return@launch
                    } catch (_: Exception) {
                        connectionHint = "ESP32 is rebooting, reconnecting..."
                    }
                } else {
                    connectionHint = "ESP32 is rebooting... scanning for it to come back up"
                }
                delay(2000)
            }
            rebootInProgress = false
            connectionState = ConnectionState.ERROR
            configStatus = "ESP32 rebooted, but automatic reconnect timed out. Open Bluetooth and reconnect when it reappears."
            connectionHint = "ESP32 reboot finished. Reconnect when the device reappears."
        }
    }

    private fun startPolling() {
        chatPollJob = viewModelScope.launch {
            while (isActive) {
                if (repo.isConnected && selectedTab == AppTab.CHAT) {
                    refreshChat()
                }
                delay(2500)
            }
        }
        debugPollJob = viewModelScope.launch {
            while (isActive) {
                if (repo.isConnected && selectedTab == AppTab.DEBUG && debugEnabled) {
                    refreshDebug()
                }
                delay(1500)
            }
        }
    }

    private suspend fun readWifiStatus(): WifiStatus {
        return repo.getWifiStatus()
    }

    private fun isUnsupportedWifiApiError(e: Exception): Boolean {
        val message = e.message.orEmpty()
        return message.contains("Unknown BLE API request", ignoreCase = true) ||
            message.contains("/api/wifi", ignoreCase = true) ||
            message.contains("WiFi controls aren't supported", ignoreCase = true)
    }

    private fun isBluetoothCacheStaleError(e: Exception): Boolean {
        val message = e.message.orEmpty()
        return message.contains("status 133", ignoreCase = true) ||
            message.contains("Bluetooth cache is stale", ignoreCase = true)
    }

    private fun syncTransportLogs() {
        transportLogText = repo.getTransportLogs().joinToString(separator = "\\n\\n") { entry ->
            val tag = when (entry.direction) {
                TransportDirection.SENT -> "APP -> ESP32"
                TransportDirection.RECEIVED -> "ESP32 -> APP"
                TransportDirection.INFO -> "INFO"
            }
            "[$tag] ${entry.payload}"
        }
    }

    private fun clearConnectionErrors() {
        chatError = null
        configError = null
        diagnosticsError = null
    }

    override fun onCleared() {
        super.onCleared()
        chatPollJob?.cancel()
        debugPollJob?.cancel()
        rebootReconnectJob?.cancel()
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
