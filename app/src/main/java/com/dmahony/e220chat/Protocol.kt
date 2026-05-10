package com.dmahony.e220chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object E220Protocol {
    fun buildChatRequest(sinceSequence: Int = 0): String = buildRequest(
        path = "/api/chat",
        method = "GET",
        extras = if (sinceSequence > 0) mapOf("since_sequence" to JsonPrimitive(sinceSequence)) else emptyMap()
    )

    fun buildSendRequest(message: String): String = buildRequest(
        path = "/api/send",
        method = "POST",
        extras = mapOf(
            "message" to JsonPrimitive(message),
            "body" to buildJsonObject { put("message", JsonPrimitive(message)) }
        )
    )

    fun buildConfigGetRequest(): String = buildRequest(path = "/api/config", method = "GET")

    fun buildConfigRequest(config: E220Config): String = buildRequest(
        path = "/api/config",
        method = "POST",
        extras = mapOf("config" to config.toJson())
    )

    fun buildOperationRequest(): String = buildRequest(path = "/api/operation", method = "GET")

    fun buildDiagnosticsRequest(): String = buildRequest(path = "/api/diagnostics", method = "GET")

    fun buildDebugRequest(): String = buildRequest(path = "/api/debug", method = "GET")

    fun buildDebugClearRequest(): String = buildRequest(path = "/api/debug/clear", method = "POST")

    fun buildRebootRequest(): String = buildRequest(path = "/api/reboot", method = "POST")

    fun buildClearChatRequest(): String = buildRequest(path = "/api/chat/clear", method = "POST")

    fun buildWifiGetRequest(): String = buildRequest(path = "/api/wifi/status", method = "GET")

    fun buildWifiToggleRequest(enabled: Boolean): String = buildRequest(
        path = "/api/wifi/toggle",
        method = "POST",
        extras = mapOf("body" to buildJsonObject { put("enabled", JsonPrimitive(enabled)) })
    )

    fun buildWifiScanRequest(): String = buildRequest(path = "/api/wifi/scan", method = "POST")

    fun buildWifiConnectRequest(ssid: String, password: String): String = buildRequest(
        path = "/api/wifi/connect",
        method = "POST",
        extras = mapOf("body" to buildJsonObject {
            put("ssid", JsonPrimitive(ssid))
            put("password", JsonPrimitive(password))
        })
    )

    fun buildWifiDisconnectRequest(): String = buildRequest(path = "/api/wifi/disconnect", method = "POST")

    fun buildWifiApRequest(password: String): String = buildRequest(
        path = "/api/wifi/ap",
        method = "POST",
        extras = mapOf("body" to buildJsonObject { put("password", JsonPrimitive(password)) })
    )

    fun parseEnvelope(line: String): JsonObject = E220Json.parseToJsonElement(line).jsonObject

    fun parseChatResponse(response: String): ChatSnapshot = parseChatResponse(parseEnvelope(response))

    fun parseChatResponse(response: JsonObject): ChatSnapshot {
        val data = requireData(response)
        val sequence = data.optInt("sequence", 0)
        val messages = data["messages"]?.jsonArray ?: JsonArray(emptyList())
        val parsed = buildList {
            for (element in messages) {
                val raw = element.jsonPrimitive.contentOrNull.orEmpty()
                if (raw.isBlank()) continue
                val sent = raw.startsWith("[TX]")
                val cleaned = raw.replace(Regex("^\\[(TX|RX)\\]\\s*"), "").trim()
                add(ChatMessage(text = cleaned.ifBlank { raw }, sent = sent, delivered = sent))
            }
        }
        return ChatSnapshot(sequence = sequence, messages = parsed)
    }

    fun parseConfigResponse(response: String): E220Config = parseConfigResponse(parseEnvelope(response))

    fun parseConfigResponse(response: JsonObject): E220Config {
        val data = requireData(response)
        return E220Config(
            freq = data.optDouble("freq", 930.125).toString(),
            txpower = data.optInt("txpower", 21).toString(),
            baud = data.optInt("baud", 9600).toString(),
            addr = data.optString("addr", "0x0001"),
            dest = data.optString("dest", "0x0001"),
            airrate = data.optInt("airrate", 2).toString(),
            subpkt = data.optInt("subpkt", 0).toString(),
            parity = data.optInt("parity", 0).toString(),
            txmode = data.optInt("txmode", 0).toString(),
            rssiNoise = data.optInt("rssi_noise", 1).toString(),
            rssiByte = data.optInt("rssi_byte", 1).toString(),
            lbt = data.optInt("lbt", 0).toString(),
            lbrRssi = data.optInt("lbr_rssi", -85).toString(),
            lbrTimeout = data.optInt("lbr_timeout", 2000).toString(),
            urxt = data.optInt("urxt", 3).toString(),
            worCycle = data.optInt("wor_cycle", 3).toString(),
            cryptH = data.optInt("crypt_h", 0).toString(),
            cryptL = data.optInt("crypt_l", 0).toString(),
            saveType = data.optInt("savetype", 1).toString(),
            wifiEnabled = data.optString("wifi_enabled", "0"),
            wifiMode = data.optString("wifi_mode", "AP"),
            wifiApSsid = data.optString("wifi_ap_ssid", ""),
            wifiApPassword = data.optString("wifi_ap_password", ""),
            wifiStaSsid = data.optString("wifi_sta_ssid", ""),
            wifiStaPassword = data.optString("wifi_sta_password", "")
        )
    }

    fun parseDiagnosticsResponse(response: String): Diagnostics = parseDiagnosticsResponse(parseEnvelope(response))

    fun parseDiagnosticsResponse(response: JsonObject): Diagnostics {
        val data = requireData(response)
        return Diagnostics(
            e220Timeouts = data.optInt("e220_timeout_count", 0),
            e220RxErrors = data.optInt("e220_rx_errors", 0),
            e220TxErrors = data.optInt("e220_tx_errors", 0),
            uptimeMs = data.optLong("uptime_ms", 0L),
            freeHeap = data.optLong("free_heap", 0L),
            minFreeHeap = data.optLong("min_free_heap", 0L),
            btName = data.optString("bt_name", ""),
            radioModel = data.optString(
                "radio_model",
                data.optString("radioModel", data.optString("model", data.optString("devtype", data.optString("DEVTYPE", ""))))
            ),
            softwareVersion = data.optString(
                "software_version",
                data.optString("softwareVersion", data.optString("firmware_version", data.optString("version", data.optString("fwcode", data.optString("FWCODE", "")))))
            ),
            btHasClient = data.optBooleanFlexible("bt_has_client", false),
            btRequestCount = data.optInt("bt_request_count", 0),
            btParseErrors = data.optInt("bt_parse_errors", 0),
            btRawMessageCount = data.optInt("bt_raw_message_count", 0),
            lastRssi = data.optInt("last_rssi", 0)
        )
    }

    fun parseOperationResponse(response: String): OperationStatus = parseOperationResponse(parseEnvelope(response))

    fun parseOperationResponse(response: JsonObject): OperationStatus {
        val data = requireData(response)
        return OperationStatus(
            type = data.optString("type", "none"),
            state = data.optString("state", "idle"),
            message = data.optString("message", ""),
            updatedAtMs = data.optLong("updated_at_ms", 0L),
            rawResult = data["result"]?.toString() ?: data.optString("result_raw", "{}")
        )
    }

    fun parseDebugLog(response: String): String = parseDebugLog(parseEnvelope(response))

    fun parseDebugLog(response: JsonObject): String = requireData(response)
        .optString("log", "")
        .replace("\\n", "\n")

    fun parseSendAcknowledgement(response: String): String = parseSendAcknowledgement(parseEnvelope(response))

    fun parseSendAcknowledgement(response: JsonObject): String {
        if (!response.optBooleanFlexible("ok", false)) {
            throw ApiException(response.optString("error", "Send failed"))
        }
        return response["data"]?.jsonObject?.optString("message", "")
            ?.ifBlank { response.optString("message", "") }
            ?: response.optString("message", "")
    }

    fun hasConfigPayload(response: String): Boolean = hasConfigPayload(parseEnvelope(response))

    fun hasConfigPayload(response: JsonObject): Boolean {
        if (!response.optBooleanFlexible("ok", false)) return false
        val data = response["data"]?.jsonObject ?: return false
        val configKeys = setOf(
            "freq", "txpower", "baud", "addr", "dest", "airrate", "subpkt", "parity", "txmode",
            "rssi_noise", "rssi_byte", "lbt", "lbr_rssi", "lbr_timeout", "urxt", "wor_cycle",
            "crypt_h", "crypt_l", "savetype", "wifi_enabled", "wifi_mode", "wifi_ap_ssid",
            "wifi_ap_password", "wifi_sta_ssid", "wifi_sta_password"
        )
        return data.containsKey("config") || configKeys.any { data.containsKey(it) }
    }

    fun parseWifiStatus(response: String): WifiStatus = parseWifiStatus(parseEnvelope(response))

    fun parseWifiStatus(response: JsonObject): WifiStatus {
        val data = requireData(response)
        return WifiStatus(
            enabled = data.optBooleanFlexible("enabled", false),
            mode = data.optString("mode", "AP"),
            apSsid = data.optString("ap_ssid", ""),
            apPassword = data.optString("ap_password", ""),
            staSsid = data.optString("sta_ssid", ""),
            staPassword = data.optString("sta_password", ""),
            staConnected = data.optBooleanFlexible("sta_connected", false),
            staIp = data.optString("sta_ip", ""),
            apIp = data.optString("ap_ip", "")
        )
    }

    fun parseWifiScanResult(operation: OperationStatus): WifiScanResult {
        val payload = runCatching { E220Json.parseToJsonElement(operation.rawResult).jsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
        val scan = payload["scan"]?.jsonObject ?: JsonObject(emptyMap())
        val networks = payload["networks"]?.jsonArray ?: JsonArray(emptyList())
        val parsedNetworks = networks.mapNotNull { element ->
            val obj = element.jsonObject
            val ssid = obj.optString("ssid", "").trim()
            if (ssid.isBlank()) return@mapNotNull null
            val encryptionText = obj.optString("encryption", obj.optString("security", ""))
            val encrypted = obj.optBooleanFlexible("encrypted", default = encryptionText.isNotBlank() && !encryptionText.equals("open", ignoreCase = true))
            WifiNetwork(
                ssid = ssid,
                rssi = obj.optInt("rssi", 0),
                encrypted = encrypted,
                channel = obj.optInt("channel", 0)
            )
        }
        return WifiScanResult(
            scan = WifiScanInfo(
                status = scan.optString("status", operation.state.ifBlank { "idle" }),
                requestedAtMs = scan.optLong("requested_at_ms", 0L),
                completedAtMs = scan.optLong("completed_at_ms", 0L),
                durationMs = scan.optLong("duration_ms", 0L),
                networkCount = scan.optInt("network_count", parsedNetworks.size),
                errorCode = scan["error_code"]?.jsonPrimitive?.intOrNull,
                error = scan.optString("error", "")
            ),
            networks = parsedNetworks
        )
    }

    private fun requireData(response: JsonObject): JsonObject {
        if (!response.optBooleanFlexible("ok", false)) {
            throw ApiException(response.optString("error", "Request failed"))
        }
        return response["data"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private fun buildRequest(path: String, method: String, extras: Map<String, JsonElement> = emptyMap()): String {
        val payload = buildJsonObject {
            put("path", JsonPrimitive(path))
            put("method", JsonPrimitive(method))
            extras.forEach { (key, value) -> put(key, value) }
        }
        return payload.toString()
    }

    private fun E220Config.toJson(): JsonObject = buildJsonObject {
        put("freq", JsonPrimitive(freq.toDoubleOrNull() ?: 930.125))
        put("txpower", JsonPrimitive(txpower.toIntOrNull() ?: 21))
        put("baud", JsonPrimitive(baud.toIntOrNull() ?: 9600))
        put("addr", JsonPrimitive(addr))
        put("dest", JsonPrimitive(dest))
        put("airrate", JsonPrimitive(airrate.toIntOrNull() ?: 2))
        put("subpkt", JsonPrimitive(subpkt.toIntOrNull() ?: 0))
        put("parity", JsonPrimitive(parity.toIntOrNull() ?: 0))
        put("txmode", JsonPrimitive(txmode.toIntOrNull() ?: 0))
        put("rssi_noise", JsonPrimitive(rssiNoise.toIntOrNull() ?: 1))
        put("rssi_byte", JsonPrimitive(rssiByte.toIntOrNull() ?: 1))
        put("lbt", JsonPrimitive(lbt.toIntOrNull() ?: 0))
        put("lbr_rssi", JsonPrimitive(lbrRssi.toIntOrNull() ?: -85))
        put("lbr_timeout", JsonPrimitive(lbrTimeout.toIntOrNull() ?: 2000))
        put("urxt", JsonPrimitive(urxt.toIntOrNull() ?: 3))
        put("wor_cycle", JsonPrimitive(worCycle.toIntOrNull() ?: 3))
        put("crypt_h", JsonPrimitive(cryptH.toIntOrNull() ?: 0))
        put("crypt_l", JsonPrimitive(cryptL.toIntOrNull() ?: 0))
        put("savetype", JsonPrimitive(saveType.toIntOrNull() ?: 1))
        put("wifi_enabled", JsonPrimitive(wifiEnabled.toIntOrNull() ?: 0))
        put("wifi_mode", JsonPrimitive(wifiMode))
        put("wifi_ap_ssid", JsonPrimitive(wifiApSsid))
        put("wifi_ap_password", JsonPrimitive(wifiApPassword))
        put("wifi_sta_ssid", JsonPrimitive(wifiStaSsid))
        put("wifi_sta_password", JsonPrimitive(wifiStaPassword))
    }
}
