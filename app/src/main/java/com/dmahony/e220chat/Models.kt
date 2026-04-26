package com.dmahony.e220chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AppTab(val label: String) {
    CHAT("Chat"),
    RADIO("Radio"),
    WIFI("WiFi"),
    DEBUG("Debug")
}

@Serializable
enum class SlashCommand(val label: String, val description: String) {
    GPS("/gps", "Get phone GPS location and post to chat"),
    CLEAR("/clear", "Clear sent and received messages"),
    NAME("/name", "Set your username (e.g. /name Alice)")
}

@Serializable
data class BluetoothDeviceInfo(
    val name: String,
    val address: String
)

@Serializable
data class ChatMessage(
    val text: String,
    val sent: Boolean,
    val delivered: Boolean = false
)

@Serializable
data class ChatSnapshot(
    val sequence: Int = 0,
    val messages: List<ChatMessage> = emptyList(),
    val reset: Boolean = false
)

@Serializable
data class E220Config(
    val freq: String = "868.125",
    val txpower: String = "21",
    val baud: String = "9600",
    val addr: String = "0x0000",
    val dest: String = "0xFFFF",
    val airrate: String = "2",
    val subpkt: String = "0",
    val parity: String = "0",
    val txmode: String = "0",
    @SerialName("rssi_noise")
    val rssiNoise: String = "0",
    @SerialName("rssi_byte")
    val rssiByte: String = "0",
    val lbt: String = "0",
    @SerialName("lbr_rssi")
    val lbrRssi: String = "-55",
    @SerialName("lbr_timeout")
    val lbrTimeout: String = "2000",
    val urxt: String = "3",
    @SerialName("wor_cycle")
    val worCycle: String = "3",
    @SerialName("crypt_h")
    val cryptH: String = "0",
    @SerialName("crypt_l")
    val cryptL: String = "0",
    @SerialName("savetype")
    val saveType: String = "1",
    @SerialName("wifi_enabled")
    val wifiEnabled: String = "0",
    @SerialName("wifi_mode")
    val wifiMode: String = "AP",
    @SerialName("wifi_ap_ssid")
    val wifiApSsid: String = "",
    @SerialName("wifi_ap_password")
    val wifiApPassword: String = "",
    @SerialName("wifi_sta_ssid")
    val wifiStaSsid: String = "",
    @SerialName("wifi_sta_password")
    val wifiStaPassword: String = ""
)

@Serializable
data class Diagnostics(
    @SerialName("e220_timeout_count")
    val e220Timeouts: Int = 0,
    @SerialName("e220_rx_errors")
    val e220RxErrors: Int = 0,
    @SerialName("e220_tx_errors")
    val e220TxErrors: Int = 0,
    @SerialName("uptime_ms")
    val uptimeMs: Long = 0,
    @SerialName("free_heap")
    val freeHeap: Long = 0,
    @SerialName("min_free_heap")
    val minFreeHeap: Long = 0,
    @SerialName("bt_name")
    val btName: String = "",
    @SerialName("bt_has_client")
    val btHasClient: Boolean = false,
    @SerialName("bt_request_count")
    val btRequestCount: Int = 0,
    @SerialName("bt_parse_errors")
    val btParseErrors: Int = 0,
    @SerialName("bt_raw_message_count")
    val btRawMessageCount: Int = 0,
    @SerialName("last_rssi")
    val lastRssi: Int = 0
)

data class OperationStatus(
    val type: String = "none",
    val state: String = "idle",
    val message: String = "",
    val updatedAtMs: Long = 0L,
    val rawResult: String = "{}"
)

enum class TransportConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    RECONNECTING
}

data class TransportConnectionEvent(
    val state: TransportConnectionState,
    val message: String,
    val manualDisconnect: Boolean = false
)

enum class TransportDirection {
    SENT,
    RECEIVED,
    INFO
}

data class TransportLogEntry(
    val direction: TransportDirection,
    val payload: String,
    val timestampMs: Long = System.currentTimeMillis()
)

@Serializable
data class WifiStatus(
    val enabled: Boolean = false,
    val mode: String = "AP", // AP, STA, or AP_STA
    val apSsid: String = "",
    val apPassword: String = "",
    val staSsid: String = "",
    val staPassword: String = "",
    val staConnected: Boolean = false,
    val staIp: String = "",
    val apIp: String = ""
)

@Serializable
data class WifiNetwork(
    val ssid: String,
    val rssi: Int,
    val encrypted: Boolean,
    val channel: Int
)

data class WifiScanInfo(
    val status: String = "idle",
    val requestedAtMs: Long = 0L,
    val completedAtMs: Long = 0L,
    val durationMs: Long = 0L,
    val networkCount: Int = 0,
    val errorCode: Int? = null,
    val error: String = ""
)

data class WifiScanResult(
    val scan: WifiScanInfo = WifiScanInfo(),
    val networks: List<WifiNetwork> = emptyList()
)
