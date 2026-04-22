package com.dmahony.e220chat

enum class AppTab(val label: String) {
    CHAT("Chat"),
    SETTINGS("Settings"),
    DEBUG("Debug")
}

data class BluetoothDeviceInfo(
    val name: String,
    val address: String
)

data class ChatMessage(
    val text: String,
    val sent: Boolean,
    val delivered: Boolean = false
)

data class ChatSnapshot(
    val sequence: Int = 0,
    val messages: List<ChatMessage> = emptyList()
)

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
    val rssiNoise: String = "0",
    val rssiByte: String = "0",
    val lbt: String = "0",
    val lbrRssi: String = "-55",
    val lbrTimeout: String = "2000",
    val urxt: String = "3",
    val worCycle: String = "3",
    val cryptH: String = "0",
    val cryptL: String = "0",
    val saveType: String = "1"
)

data class Diagnostics(
    val e220Timeouts: Int = 0,
    val e220RxErrors: Int = 0,
    val e220TxErrors: Int = 0,
    val uptimeMs: Long = 0,
    val freeHeap: Long = 0,
    val minFreeHeap: Long = 0,
    val btName: String = "",
    val btHasClient: Boolean = false,
    val btRequestCount: Int = 0,
    val btParseErrors: Int = 0,
    val btRawMessageCount: Int = 0,
    val lastRssi: Int = 0
)

data class OperationStatus(
    val type: String = "none",
    val state: String = "idle",
    val message: String = "",
    val updatedAtMs: Long = 0L,
    val rawResult: String = "{}"
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
