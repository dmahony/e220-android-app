package com.dmahony.e220chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class E220ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = E220Repository(application.applicationContext)
    private var rebootReconnectJob: Job? = null
    private var rebootInProgress = false

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
    var debugPaused by mutableStateOf(false)
        private set
    var transportLogText by mutableStateOf("")
        private set

    var diagnostics by mutableStateOf(Diagnostics())
        private set
    var diagnosticsError by mutableStateOf<String?>(null)
        private set

    var wifiStatus by mutableStateOf(WifiStatus())
        private set
    var wifiNetworks by mutableStateOf(listOf<WifiNetwork>())
        private set
    var wifiError by mutableStateOf<String?>(null)
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
    private var chatPollJob: Job? = null
    private var debugPollJob: Job? = null

    init {
        viewModelScope.launch { refreshBluetoothDevices() }
        if (repo.isConnected) {
            connectionState = ConnectionState.CONNECTED
            connectionHint = selectedBluetoothName.ifBlank { "Bluetooth connected" }
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

    fun refreshBluetoothDevices() {
        viewModelScope.launch {
            isScanning = true
            try {
                bluetoothDevices = repo.scanBleDevices()
            } finally {
                isScanning = false
            }
            selectedBluetoothAddress = repo.selectedDeviceAddress.orEmpty()
            selectedBluetoothName = repo.selectedDeviceName.orEmpty()
            if (selectedBluetoothName.isBlank() && selectedBluetoothAddress.isNotBlank()) {
                selectedBluetoothName = bluetoothDevices.firstOrNull { it.address == selectedBluetoothAddress }?.name.orEmpty()
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

    fun connectBluetooth(device: BluetoothDeviceInfo, onError: (String) -> Unit, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                connectionState = ConnectionState.CONNECTING
                connectionHint = if (rebootInProgress) {
                    "ESP32 is coming back up, reconnecting to ${device.name}..."
                } else {
                    "Connecting to ${device.name}..."
                }
                val connected = repo.connect(device.address)
                selectedBluetoothAddress = connected.address
                selectedBluetoothName = connected.name
                connectionState = ConnectionState.CONNECTED
                rebootInProgress = false
                rebootReconnectJob?.cancel()
                connectionHint = "Connected to ${connected.name}"
                configStatus = null
                clearConnectionErrors()
                refreshConfig()
                refreshDiagnostics()
                syncTransportLogs()
                onSuccess()
            } catch (e: Exception) {
                connectionState = ConnectionState.ERROR
                val msg = if (rebootInProgress) {
                    "ESP32 is rebooting. Waiting to reconnect..."
                } else {
                    e.message ?: "Bluetooth connection failed"
                }
                connectionHint = msg
                syncTransportLogs()
                if (!rebootInProgress) onError(msg)
            }
        }
    }

    fun reconnectSavedDevice(onError: (String) -> Unit) {
        val address = selectedBluetoothAddress.ifBlank { repo.selectedDeviceAddress.orEmpty() }
        val device = bluetoothDevices.firstOrNull { it.address == address }
        if (device != null) {
            connectBluetooth(device, onError)
        } else {
            onError("Select a nearby BLE device first")
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

    fun sendMessage(message: String, onError: (String) -> Unit, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val trimmed = message.trim()
                if (trimmed.isEmpty()) {
                    onError("Please enter a message")
                    return@launch
                }
                if (!repo.isConnected) {
                    onError("Connect to BLE first")
                    return@launch
                }
                repo.sendMessage(trimmed)
                operationStatus = operationStatus.copy(type = "send", state = "success", message = "Message sent")
                connectionHint = "Sent to radio"
                refreshChat()
                if (selectedTab == AppTab.DEBUG) {
                    refreshDebugNow()
                }
                syncTransportLogs()
                onSuccess()
            } catch (e: Exception) {
                connectionState = ConnectionState.ERROR
                connectionHint = e.message ?: "Send failed"
                syncTransportLogs()
                onError(e.message ?: "Failed to send message")
            }
        }
    }

    fun refreshChat() {
        if (!repo.isConnected) {
            chatError = null
            return
        }
        viewModelScope.launch {
            try {
                val snapshot = repo.getChat()
                if (snapshot.sequence != lastChatSequence) {
                    chatMessages = snapshot.messages
                    lastChatSequence = snapshot.sequence
                }
                chatError = null
                syncTransportLogs()
            } catch (e: Exception) {
                if (!rebootInProgress) {
                    chatError = e.message ?: "Chat refresh failed"
                }
                syncTransportLogs()
            }
        }
    }

    fun refreshConfig() {
        if (!repo.isConnected) {
            configError = null
            return
        }
        viewModelScope.launch {
            try {
                config = repo.getConfig()
                configError = null
                syncTransportLogs()
            } catch (e: Exception) {
                if (!rebootInProgress) {
                    configError = e.message ?: "Config load failed"
                }
                syncTransportLogs()
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
                rebootReconnectJob?.cancel()
                configError = null
                chatError = null
                diagnosticsError = null
                configStatus = "ESP32 rebooting. Bluetooth will disconnect briefly and reconnect automatically."
                connectionHint = "ESP32 is rebooting... waiting for it to come back up"
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
        viewModelScope.launch {
            try {
                diagnostics = repo.getDiagnostics()
                diagnosticsError = null
                syncTransportLogs()
            } catch (e: Exception) {
                if (!rebootInProgress) {
                    diagnosticsError = e.message ?: "Diagnostics failed"
                }
                syncTransportLogs()
            }
        }
    }

    fun refreshWifi() {
        if (!repo.isConnected) {
            wifiError = null
            return
        }
        viewModelScope.launch {
            try {
                val response = repo.getWifiStatus()
                wifiStatus = WifiStatus(
                    enabled = response.optInt("enabled", 0) == 1,
                    mode = response.optString("mode", "AP"),
                    apSsid = response.optString("ap_ssid", ""),
                    apPassword = response.optString("ap_password", ""),
                    staSsid = response.optString("sta_ssid", ""),
                    staPassword = response.optString("sta_password", ""),
                    staConnected = response.optBoolean("sta_connected", false),
                    staIp = response.optString("sta_ip", ""),
                    apIp = response.optString("ap_ip", "")
                )
                wifiError = null
                syncTransportLogs()
            } catch (e: Exception) {
                wifiError = e.message ?: "WiFi status refresh failed"
                syncTransportLogs()
            }
        }
    }

    fun scanWifiNetworks() {
        if (!repo.isConnected) return
        viewModelScope.launch {
            try {
                val response = repo.scanWifi()
                val networksArray = response.optJSONArray("networks") ?: JSONArray()
                wifiNetworks = buildList {
                    for (i in 0 until networksArray.length()) {
                        val net = networksArray.getJSONObject(i)
                        add(WifiNetwork(
                            ssid = net.optString("ssid", "Unknown"),
                            rssi = net.optInt("rssi", 0),
                            encrypted = net.optBoolean("encrypted", false),
                            channel = net.optInt("channel", 0)
                        ))
                    }
                }
                wifiError = null
                syncTransportLogs()
            } catch (e: Exception) {
                wifiError = e.message ?: "WiFi scan failed"
                syncTransportLogs()
            }
        }
    }

    fun connectWifi(ssid: String, password: String, onError: (String) -> Unit, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                repo.connectWifi(ssid, password)
                syncTransportLogs()
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "WiFi connection failed")
                syncTransportLogs()
            }
        }
    }

    fun disconnectWifi(onError: (String) -> Unit, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                repo.disconnectWifi()
                syncTransportLogs()
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "WiFi disconnect failed")
                syncTransportLogs()
            }
        }
    }

    fun setWifiApPassword(password: String, onError: (String) -> Unit, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                repo.setWifiAp(password)
                syncTransportLogs()
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "WiFi AP update failed")
                syncTransportLogs()
            }
        }
    }

    fun refreshDebugNow() {
        refreshDebug()
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

    fun toggleDebugPause() {
        debugPaused = !debugPaused
    }

    private fun refreshDebug() {
        if (debugPaused || !repo.isConnected) return
        viewModelScope.launch {
            try {
                debugText = repo.getDebug()
                operationStatus = repo.getOperation()
                syncTransportLogs()
            } catch (_: Exception) {
                if (!rebootInProgress) {
                    syncTransportLogs()
                }
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
                        connectionHint = "ESP32 is coming back up, reconnecting to ${device.name}..."
                        val connected = repo.connect(device.address)
                        selectedBluetoothAddress = connected.address
                        selectedBluetoothName = connected.name
                        connectionState = ConnectionState.CONNECTED
                        rebootInProgress = false
                        rebootReconnectJob?.cancel()
                        connectionHint = "Connected to ${connected.name}"
                        configStatus = null
                        clearConnectionErrors()
                        refreshConfig()
                        refreshDiagnostics()
                        syncTransportLogs()
                        return@launch
                    } catch (_: Exception) {
                        connectionHint = "ESP32 is rebooting... found it, retrying connection"
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
                delay(1000)
            }
        }
        debugPollJob = viewModelScope.launch {
            while (isActive) {
                if (repo.isConnected && selectedTab == AppTab.DEBUG) {
                    refreshDebug()
                }
                delay(1500)
            }
        }
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
