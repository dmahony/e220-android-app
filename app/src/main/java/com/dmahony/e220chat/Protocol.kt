package com.dmahony.e220chat

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object E220Protocol {
    fun buildChatRequest(): String = request("/api/chat", "GET")

    fun buildSendRequest(message: String): String = request("/api/send", "POST") {
        put("message", message)
        putJsonObject("body") { put("message", message) }
    }

    fun buildConfigGetRequest(): String = request("/api/config", "GET")

    fun buildConfigRequest(config: E220Config): String = request("/api/config", "POST") {
        putJsonObject("config") {
            put("freq", config.freq.toDoubleOrNull() ?: 868.125)
            put("txpower", config.txpower.toIntOrNull() ?: 21)
            put("baud", config.baud.toIntOrNull() ?: 9600)
            put("addr", config.addr)
            put("dest", config.dest)
            put("airrate", config.airrate.toIntOrNull() ?: 2)
            put("subpkt", config.subpkt.toIntOrNull() ?: 0)
            put("parity", config.parity.toIntOrNull() ?: 0)
            put("txmode", config.txmode.toIntOrNull() ?: 0)
            put("rssi_noise", config.rssiNoise.toIntOrNull() ?: 0)
            put("rssi_byte", config.rssiByte.toIntOrNull() ?: 0)
            put("lbt", config.lbt.toIntOrNull() ?: 0)
            put("lbr_rssi", config.lbrRssi.toIntOrNull() ?: -55)
            put("lbr_timeout", config.lbrTimeout.toIntOrNull() ?: 2000)
            put("urxt", config.urxt.toIntOrNull() ?: 3)
            put("wor_cycle", config.worCycle.toIntOrNull() ?: 3)
            put("crypt_h", config.cryptH.toIntOrNull() ?: 0)
            put("crypt_l", config.cryptL.toIntOrNull() ?: 0)
            put("savetype", config.saveType.toIntOrNull() ?: 1)
            put("wifi_enabled", config.wifiEnabled.toIntOrNull() ?: 0)
            put("wifi_mode", config.wifiMode)
            put("wifi_ap_ssid", config.wifiApSsid)
            put("wifi_ap_password", config.wifiApPassword)
            put("wifi_sta_ssid", config.wifiStaSsid)
            put("wifi_sta_password", config.wifiStaPassword)
        }
    }

    fun buildOperationRequest(): String = request("/api/operation", "GET")

    fun buildDiagnosticsRequest(): String = request("/api/diagnostics", "GET")

    fun buildDebugRequest(): String = request("/api/debug", "GET")

    fun buildDebugClearRequest(): String = request("/api/debug/clear", "POST")

    fun buildRebootRequest(): String = request("/api/reboot", "POST")

    fun buildWifiGetRequest(): String = request("/api/wifi/status", "GET")

    fun buildWifiToggleRequest(enabled: Boolean): String = request("/api/wifi/toggle", "POST") {
        putJsonObject("body") { put("enabled", enabled) }
    }

    fun buildWifiScanRequest(): String = request("/api/wifi/scan", "GET")

    fun buildWifiConnectRequest(ssid: String, password: String): String = request("/api/wifi/connect", "POST") {
        putJsonObject("body") {
            put("ssid", ssid)
            put("password", password)
        }
    }

    fun buildWifiDisconnectRequest(): String = request("/api/wifi/disconnect", "POST")

    fun buildWifiApRequest(password: String): String = request("/api/wifi/ap", "POST") {
        putJsonObject("body") { put("password", password) }
    }

    fun parseChatResponse(response: String): ChatSnapshot {
        val data = requireData(response)
        val sequence = data.optInt("sequence", 0)
        val messages = data["messages"]?.jsonArray ?: JsonArray(emptyList())
        val parsed = buildList {
            for (element in messages) {
                val raw = element.jsonPrimitive.contentOrNull ?: continue
                if (raw.isBlank()) continue
                val sent = raw.startsWith("[TX]")
                val cleaned = raw.replace(Regex("^\\[(TX|RX)\\]\\s*"), "").trim()
                add(ChatMessage(text = cleaned.ifBlank { raw }, sent = sent, delivered = sent))
            }
        }
        return ChatSnapshot(sequence = sequence, messages = parsed)
    }

    fun parseConfigResponse(response: String): E220Config {
        val data = requireData(response)
        return E220Config(
            freq = data.optDouble("freq", 868.125).toString(),
            txpower = data.optInt("txpower", 21).toString(),
            baud = data.optInt("baud", 9600).toString(),
            addr = data.optString("addr", "0x0000"),
            dest = data.optString("dest", "0xFFFF"),
            airrate = data.optInt("airrate", 2).toString(),
            subpkt = data.optInt("subpkt", 0).toString(),
            parity = data.optInt("parity", 0).toString(),
            txmode = data.optInt("txmode", 0).toString(),
            rssiNoise = data.optInt("rssi_noise", 0).toString(),
            rssiByte = data.optInt("rssi_byte", 0).toString(),
            lbt = data.optInt("lbt", 0).toString(),
            lbrRssi = data.optInt("lbr_rssi", -55).toString(),
            lbrTimeout = data.optInt("lbr_timeout", 2000).toString(),
            urxt = data.optInt("urxt", 3).toString(),
            worCycle = data.optInt("wor_cycle", 3).toString(),
            cryptH = data.optInt("crypt_h", 0).toString(),
            cryptL = data.optInt("crypt_l", 0).toString(),
            saveType = data.optInt("savetype", 1).toString(),
            wifiEnabled = data.optInt("wifi_enabled", 0).toString(),
            wifiMode = data.optString("wifi_mode", "AP"),
            wifiApSsid = data.optString("wifi_ap_ssid", ""),
            wifiApPassword = data.optString("wifi_ap_password", ""),
            wifiStaSsid = data.optString("wifi_sta_ssid", ""),
            wifiStaPassword = data.optString("wifi_sta_password", "")
        )
    }

    fun parseDiagnosticsResponse(response: String): Diagnostics {
        val data = requireData(response)
        return Diagnostics(
            e220Timeouts = data.optInt("e220_timeout_count", 0),
            e220RxErrors = data.optInt("e220_rx_errors", 0),
            e220TxErrors = data.optInt("e220_tx_errors", 0),
            uptimeMs = data.optLong("uptime_ms", 0L),
            freeHeap = data.optLong("free_heap", 0L),
            minFreeHeap = data.optLong("min_free_heap", 0L),
            btName = data.optString("bt_name", ""),
            btHasClient = data.optBooleanFlexible("bt_has_client"),
            btRequestCount = data.optInt("bt_request_count", 0),
            btParseErrors = data.optInt("bt_parse_errors", 0),
            btRawMessageCount = data.optInt("bt_raw_message_count", 0),
            lastRssi = data.optInt("last_rssi", 0)
        )
    }

    fun parseOperationResponse(response: String): OperationStatus {
        val data = requireData(response)
        return OperationStatus(
            type = data.optString("type", "none"),
            state = data.optString("state", "idle"),
            message = data.optString("message", ""),
            updatedAtMs = data.optLong("updated_at_ms", 0L),
            rawResult = when (val result = data["result"]) {
                null, kotlinx.serialization.json.JsonNull -> data.optString("result_raw", "{}")
                else -> result.toString()
            }
        )
    }

    fun parseDebugLog(response: String): String = requireData(response)
        .optString("log", "")
        .replace("\\n", "\n")

    fun parseSendAcknowledgement(response: String): String {
        val envelope = parseEnvelope(response)
        if (!envelope.optBooleanFlexible("ok", false)) {
            throw ApiException(envelope.optString("error", "Send failed"))
        }
        val data = envelope["data"]?.jsonObject ?: JsonObject(emptyMap())
        return data.optString("message", envelope.optString("message", ""))
    }

    fun parseWifiStatus(response: String): WifiStatus {
        val data = requireData(response)
        return WifiStatus(
            enabled = data.optBooleanFlexible("enabled"),
            mode = data.optString("mode", "AP"),
            apSsid = data.optString("ap_ssid", ""),
            apPassword = data.optString("ap_password", ""),
            staSsid = data.optString("sta_ssid", ""),
            staPassword = data.optString("sta_password", ""),
            staConnected = data.optBooleanFlexible("sta_connected"),
            staIp = data.optString("sta_ip", ""),
            apIp = data.optString("ap_ip", "")
        )
    }

    fun parseWifiScanNetworks(operation: OperationStatus): List<WifiNetwork> {
        if (operation.type != "wifi_scan") return emptyList()
        if (operation.state != "success") {
            throw ApiException(operation.message.ifBlank { "WiFi scan failed" })
        }

        val raw = operation.rawResult.trim()
        if (raw.isBlank()) return emptyList()

        val result = try {
            E220Json.parseToJsonElement(raw)
        } catch (_: Exception) {
            return emptyList()
        }

        val resultObject = result.jsonObject
        val networks = resultObject["networks"]?.jsonArray ?: JsonArray(emptyList())
        return buildList {
            for (element in networks) {
                val net = element.jsonObject
                val encryption = net.optString("encryption", "").trim()
                val encrypted = when {
                    net.containsKey("encrypted") -> net.optBooleanFlexible("encrypted")
                    encryption.isNotBlank() -> !encryption.equals("open", ignoreCase = true)
                    else -> false
                }
                add(
                    WifiNetwork(
                        ssid = net.optString("ssid", "Unknown"),
                        rssi = net.optInt("rssi", 0),
                        encrypted = encrypted,
                        channel = net.optInt("channel", 0)
                    )
                )
            }
        }
    }

    private fun parseEnvelope(response: String): JsonObject {
        return try {
            E220Json.parseToJsonElement(response).jsonObject
        } catch (e: Exception) {
            throw ApiException("Invalid response from ESP32: ${e.message ?: response}")
        }
    }

    private fun requireData(response: String): JsonObject {
        val envelope = parseEnvelope(response)
        if (!envelope.optBooleanFlexible("ok", false)) {
            throw ApiException(envelope.optString("error", "Request failed"))
        }
        return envelope["data"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private fun request(path: String, method: String, extra: JsonObjectBuilder.() -> Unit = {}): String {
        val envelope = buildJsonObject {
            put("path", path)
            put("method", method)
            extra()
        }
        return E220Json.encodeToString(envelope)
    }
}
