package com.dmahony.e220chat

import org.json.JSONArray
import org.json.JSONObject

object E220Protocol {
    fun buildChatRequest(): JSONObject = JSONObject()
        .put("path", "/api/chat")
        .put("method", "GET")

    fun buildSendRequest(message: String): JSONObject = JSONObject()
        .put("path", "/api/send")
        .put("method", "POST")
        .put("message", message)
        .put("body", JSONObject().put("message", message))

    fun buildConfigGetRequest(): JSONObject = JSONObject()
        .put("path", "/api/config")
        .put("method", "GET")

    fun buildConfigRequest(config: E220Config): JSONObject = JSONObject()
        .put("path", "/api/config")
        .put("method", "POST")
        .put("config", config.toJson())

    fun buildOperationRequest(): JSONObject = JSONObject()
        .put("path", "/api/operation")
        .put("method", "GET")

    fun buildDiagnosticsRequest(): JSONObject = JSONObject()
        .put("path", "/api/diagnostics")
        .put("method", "GET")

    fun buildDebugRequest(): JSONObject = JSONObject()
        .put("path", "/api/debug")
        .put("method", "GET")

    fun buildDebugClearRequest(): JSONObject = JSONObject()
        .put("path", "/api/debug/clear")
        .put("method", "POST")

    fun buildRebootRequest(): JSONObject = JSONObject()
        .put("path", "/api/reboot")
        .put("method", "POST")

    // WiFi API functions
    fun buildWifiGetRequest(): JSONObject = JSONObject()
        .put("path", "/api/wifi")
        .put("method", "GET")

    fun buildWifiScanRequest(): JSONObject = JSONObject()
        .put("path", "/api/wifi/scan")
        .put("method", "GET")

    fun buildWifiConnectRequest(ssid: String, password: String): JSONObject = JSONObject()
        .put("path", "/api/wifi/connect")
        .put("method", "POST")
        .put("body", JSONObject().put("ssid", ssid).put("password", password))

    fun buildWifiDisconnectRequest(): JSONObject = JSONObject()
        .put("path", "/api/wifi/disconnect")
        .put("method", "POST")

    fun buildWifiApRequest(password: String): JSONObject = JSONObject()
        .put("path", "/api/wifi/ap")
        .put("method", "POST")
        .put("body", JSONObject().put("password", password))

    fun parseEnvelope(line: String): JSONObject = JSONObject(line)

    fun parseChatResponse(response: JSONObject): ChatSnapshot {
        val data = requireData(response)
        val sequence = data.optInt("sequence", 0)
        val messages = data.optJSONArray("messages") ?: JSONArray()
        val parsed = buildList {
            for (i in 0 until messages.length()) {
                val raw = messages.optString(i, "")
                if (raw.isBlank()) continue
                val sent = raw.startsWith("[TX]")
                val cleaned = raw.replace(Regex("^\\[(TX|RX)\\]\\s*"), "").trim()
                add(ChatMessage(text = cleaned.ifBlank { raw }, sent = sent, delivered = sent))
            }
        }
        return ChatSnapshot(sequence = sequence, messages = parsed)
    }

    fun parseConfigResponse(response: JSONObject): E220Config {
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

    fun parseDiagnosticsResponse(response: JSONObject): Diagnostics {
        val data = requireData(response)
        return Diagnostics(
            e220Timeouts = data.optInt("e220_timeout_count", 0),
            e220RxErrors = data.optInt("e220_rx_errors", 0),
            e220TxErrors = data.optInt("e220_tx_errors", 0),
            uptimeMs = data.optLong("uptime_ms", 0L),
            freeHeap = data.optLong("free_heap", 0L),
            minFreeHeap = data.optLong("min_free_heap", 0L),
            btName = data.optString("bt_name", ""),
            btHasClient = data.optBoolean("bt_has_client", false),
            btRequestCount = data.optInt("bt_request_count", 0),
            btParseErrors = data.optInt("bt_parse_errors", 0),
            btRawMessageCount = data.optInt("bt_raw_message_count", 0),
            lastRssi = data.optInt("last_rssi", 0)
        )
    }

    fun parseOperationResponse(response: JSONObject): OperationStatus {
        val data = requireData(response)
        return OperationStatus(
            type = data.optString("type", "none"),
            state = data.optString("state", "idle"),
            message = data.optString("message", ""),
            updatedAtMs = data.optLong("updated_at_ms", 0L),
            rawResult = data.optJSONObject("result")?.toString() ?: data.optString("result_raw", "{}")
        )
    }

    fun parseDebugLog(response: JSONObject): String = requireData(response)
        .optString("log", "")
        .replace("\\n", "\n")

    fun parseSendAcknowledgement(response: JSONObject): String {
        if (!response.optBoolean("ok", false)) {
            throw ApiException(response.optString("error", "Send failed"))
        }
        return response.optJSONObject("data")?.optString("message", "")
            ?.ifBlank { response.optString("message", "") }
            ?: ""
    }

    fun parseWifiResponse(response: JSONObject): JSONObject {
        return requireData(response)
    }

    private fun requireData(response: JSONObject): JSONObject {
        if (!response.optBoolean("ok", false)) {
            throw ApiException(response.optString("error", "Request failed"))
        }
        return response.optJSONObject("data") ?: JSONObject()
    }

    private fun E220Config.toJson(): JSONObject = JSONObject()
        .put("freq", freq.toDoubleOrNull() ?: 868.125)
        .put("txpower", txpower.toIntOrNull() ?: 21)
        .put("baud", baud.toIntOrNull() ?: 9600)
        .put("addr", addr)
        .put("dest", dest)
        .put("airrate", airrate.toIntOrNull() ?: 2)
        .put("subpkt", subpkt.toIntOrNull() ?: 0)
        .put("parity", parity.toIntOrNull() ?: 0)
        .put("txmode", txmode.toIntOrNull() ?: 0)
        .put("rssi_noise", rssiNoise.toIntOrNull() ?: 0)
        .put("rssi_byte", rssiByte.toIntOrNull() ?: 0)
        .put("lbt", lbt.toIntOrNull() ?: 0)
        .put("lbr_rssi", lbrRssi.toIntOrNull() ?: -55)
        .put("lbr_timeout", lbrTimeout.toIntOrNull() ?: 2000)
        .put("urxt", urxt.toIntOrNull() ?: 3)
        .put("wor_cycle", worCycle.toIntOrNull() ?: 3)
        .put("crypt_h", cryptH.toIntOrNull() ?: 0)
        .put("crypt_l", cryptL.toIntOrNull() ?: 0)
        .put("savetype", saveType.toIntOrNull() ?: 1)
        .put("wifi_enabled", wifiEnabled.toIntOrNull() ?: 0)
        .put("wifi_mode", wifiMode)
        .put("wifi_ap_ssid", wifiApSsid)
        .put("wifi_ap_password", wifiApPassword)
        .put("wifi_sta_ssid", wifiStaSsid)
        .put("wifi_sta_password", wifiStaPassword)
}