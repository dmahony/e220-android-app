package com.dmahony.e220chat

import com.dmahony.e220chat.ble.BleConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private suspend fun E220Repository.readBinaryConfigWithRetry(): BleConfig? {
    repeat(3) { attempt ->
        val cfg = runCatching { bleV2.readConfigCharacteristic() }.getOrNull()
        if (cfg != null) return cfg
        if (attempt < 2) {
            delay(250L * (attempt + 1))
        }
    }
    return null
}

internal suspend fun E220Repository.getConfig(): E220Config {
    if (useBinaryTransport) {
        val cfg = readBinaryConfigWithRetry()
        if (cfg != null) {
            binaryConfig = cfg
            return E220ConfigMapper.toLegacy(cfg)
        }
        // GATT read failed — fall back to frame-based config if available
        val frameCfg = binaryConfig
        if (frameCfg != null) {
            appendTransportLog(TransportDirection.INFO, "Using frame-based config (GATT read unavailable)")
            return E220ConfigMapper.toLegacy(frameCfg)
        }
        throw ApiException("Config not available — neither GATT nor frame-based")
    }
    return E220Protocol.parseConfigResponse(exchange(E220Protocol.buildConfigGetRequest()))
}

internal suspend fun E220Repository.saveConfig(config: E220Config): E220Config = withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (useBinaryTransport) {
        val cfg = E220ConfigMapper.toBinary(config, binaryConfig ?: E220ConfigMapper.defaultBinaryConfig(selectedDeviceAddress))
        bleV2.writeConfig(cfg)
        val live = bleV2.readConfigCharacteristic()
        binaryConfig = live
        return@withContext E220ConfigMapper.toLegacy(live)
    }

    val response = exchange(E220Protocol.buildConfigRequest(config))
    if (E220Protocol.hasConfigPayload(response)) {
        return@withContext E220Protocol.parseConfigResponse(response)
    }

    val deadlineMs = System.currentTimeMillis() + E220Repository.CONFIG_APPLY_TIMEOUT_MS
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

internal suspend fun E220Repository.reboot() {
    if (useBinaryTransport) {
        throw ApiException("Reboot API is not supported by BLE v2 firmware")
    }
    exchange(E220Protocol.buildRebootRequest())
}
