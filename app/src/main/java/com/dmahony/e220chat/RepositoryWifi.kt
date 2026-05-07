package com.dmahony.e220chat

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal suspend fun E220Repository.getWifiStatus(): WifiStatus {
    if (useBinaryTransport) throw ApiException("WiFi controls aren't supported by this firmware")
    return E220Protocol.parseWifiStatus(exchange(E220Protocol.buildWifiGetRequest()))
}

internal suspend fun E220Repository.setWifiEnabled(enabled: Boolean): WifiStatus {
    if (useBinaryTransport) throw ApiException("WiFi controls aren't supported by this firmware")
    return E220Protocol.parseWifiStatus(exchange(E220Protocol.buildWifiToggleRequest(enabled)))
}

internal suspend fun E220Repository.scanWifi(): WifiScanResult = withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (useBinaryTransport) throw ApiException("WiFi controls aren't supported by this firmware")
    exchangeMutex.withLock {
        ensureConnectedLocked()
        executeExchangeLocked(E220Protocol.buildWifiScanRequest())
    }

    val deadlineMs = System.currentTimeMillis() + E220Repository.WIFI_SCAN_TIMEOUT_MS
    var pollDelayMs = E220Repository.WIFI_SCAN_INITIAL_POLL_DELAY_MS
    while (System.currentTimeMillis() < deadlineMs) {
        val operation = runCatching { getOperation() }.getOrNull()
        if (operation != null && operation.type == "wifi_scan") {
            when (operation.state) {
                "success", "error" -> return@withContext E220Protocol.parseWifiScanResult(operation)
            }
        }
        delay(pollDelayMs)
        pollDelayMs = (pollDelayMs + E220Repository.WIFI_SCAN_POLL_BACKOFF_MS).coerceAtMost(E220Repository.WIFI_SCAN_MAX_POLL_DELAY_MS)
    }
    throw ApiException("Timed out waiting for WiFi scan result")
}

internal suspend fun E220Repository.connectWifi(ssid: String, password: String) {
    if (useBinaryTransport) throw ApiException("WiFi controls aren't supported by this firmware")
    exchange(E220Protocol.buildWifiConnectRequest(ssid, password))
}

internal suspend fun E220Repository.disconnectWifi() {
    if (useBinaryTransport) throw ApiException("WiFi controls aren't supported by this firmware")
    exchange(E220Protocol.buildWifiDisconnectRequest())
}

internal suspend fun E220Repository.setWifiAp(password: String) {
    if (useBinaryTransport) throw ApiException("WiFi controls aren't supported by this firmware")
    exchange(E220Protocol.buildWifiApRequest(password))
}
