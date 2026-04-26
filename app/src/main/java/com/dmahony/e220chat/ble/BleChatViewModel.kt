package com.dmahony.e220chat.ble

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatLine(
    val fromUserId24: Int,
    val fromName: String,
    val text: String
)

class BleChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = BleMessagingRepository(application.applicationContext)

    private val _lines = MutableStateFlow<List<ChatLine>>(emptyList())
    val lines: StateFlow<List<ChatLine>> = _lines.asStateFlow()

    val status: StateFlow<StatusTelemetry?> = repo.status

    init {
        viewModelScope.launch {
            repo.messages.collect { pkt ->
                _lines.value = _lines.value + ChatLine(
                    fromUserId24 = pkt.userId24,
                    fromName = repo.usernameFor(pkt.userId24),
                    text = pkt.text
                )
            }
        }
    }

    fun connect(address: String) {
        viewModelScope.launch {
            repo.connect(address)
            repo.requestWhois()
        }
    }

    fun disconnect() = repo.disconnect()

    fun sendText(toUserId24: Int, text: String) {
        viewModelScope.launch { repo.sendText(toUserId24, text) }
    }

    fun setProfile(myUserId24: Int, username: String) {
        viewModelScope.launch { repo.announceProfile(myUserId24, username) }
    }

    fun applyConfig(cfg: BleConfig) {
        viewModelScope.launch { repo.applyConfig(cfg) }
    }
}
