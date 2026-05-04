package com.dmahony.e220chat.ble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BleMessagingRepository(context: Context) {
    private val manager = BleUartManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val userMap = linkedMapOf<Int, String>()

    private val _messages = MutableSharedFlow<TextPacket>(extraBufferCapacity = 256)
    val messages: SharedFlow<TextPacket> = _messages.asSharedFlow()

    private val _status = MutableStateFlow<StatusTelemetry?>(null)
    val status: StateFlow<StatusTelemetry?> = _status.asStateFlow()

    init {
        scope.launch {
            manager.status.collect { _status.value = it }
        }
        scope.launch {
            manager.frames.collect { frame ->
                when (frame.type) {
                    MsgType.TEXT -> {
                        if (frame.payload.size >= 3) {
                            val id = ((frame.payload[0].toInt() and 0xFF) shl 16) or
                                ((frame.payload[1].toInt() and 0xFF) shl 8) or
                                (frame.payload[2].toInt() and 0xFF)
                            val text = frame.payload.copyOfRange(3, frame.payload.size).toString(Charsets.UTF_8)
                            _messages.emit(TextPacket(id, text))
                        }
                    }

                    MsgType.PROFILE -> {
                        if (frame.payload.size >= 4) {
                            val id = ((frame.payload[0].toInt() and 0xFF) shl 16) or
                                ((frame.payload[1].toInt() and 0xFF) shl 8) or
                                (frame.payload[2].toInt() and 0xFF)
                            val n = (frame.payload[3].toInt() and 0xFF).coerceAtMost(frame.payload.size - 4)
                            val name = if (n > 0) frame.payload.copyOfRange(4, 4 + n).toString(Charsets.UTF_8) else "u$id"
                            userMap[id] = name
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    suspend fun connect(address: String) = manager.connect(address)
    fun disconnect() = manager.disconnect()

    fun dispose() {
        scope.cancel()
        manager.dispose()
    }

    suspend fun sendText(toUserId24: Int, message: String) = manager.sendText(toUserId24, message)

    suspend fun announceProfile(myUserId24: Int, myName: String) {
        userMap[myUserId24] = myName
        manager.sendProfile(myUserId24, myName)
    }

    suspend fun requestWhois() = manager.requestWhois()

    suspend fun applyConfig(cfg: BleConfig) = manager.writeConfig(cfg)
    suspend fun readConfig(): BleConfig = manager.readConfigCharacteristic()

    fun usernameFor(userId24: Int): String = userMap[userId24] ?: "u${userId24.toString(16).padStart(6, '0')}"
}
