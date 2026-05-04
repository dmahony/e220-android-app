package com.dmahony.e220chat

import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal suspend fun E220Repository.getConfig(): E220Config {
    if (useBinaryTransport) {
        val cfg = bleV2.readConfigCharacteristic()
        binaryConfig = cfg
        return E220ConfigMapper.toLegacy(cfg)
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
