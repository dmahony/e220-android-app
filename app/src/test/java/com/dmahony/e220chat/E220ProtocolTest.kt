package com.dmahony.e220chat

import com.dmahony.e220chat.ble.BleConfig
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class E220ProtocolTest {
    @Test
    fun `build send request produces valid JSON with path, method, message, and body`() {
        val request = E220Protocol.buildSendRequest("hello radio")
        val parsed = E220Protocol.parseEnvelope(request)

        assertEquals("/api/send", parsed["path"]?.jsonPrimitive?.content)
        assertEquals("POST", parsed["method"]?.jsonPrimitive?.content)
        assertEquals("hello radio", parsed["message"]?.jsonPrimitive?.content)
        val body = parsed["body"]?.jsonObject
        assertEquals("hello radio", body?.get("message")?.jsonPrimitive?.content)
    }

    @Test
    fun `build config request produces valid JSON with nested config`() {
        val request = E220Protocol.buildConfigRequest(
            E220Config(
                freq = "915.125",
                txpower = "30",
                baud = "9600",
                addr = "0x0001",
                dest = "0x0001",
                airrate = "2",
                subpkt = "0",
                parity = "0",
                txmode = "1",
                rssiNoise = "0",
                rssiByte = "1",
                lbt = "1",
                worCycle = "3",
                cryptH = "12",
                cryptL = "34",
                saveType = "1"
            )
        )
        val parsed = E220Protocol.parseEnvelope(request)

        assertEquals("/api/config", parsed["path"]?.jsonPrimitive?.content)
        assertEquals("POST", parsed["method"]?.jsonPrimitive?.content)
        val config = parsed["config"]?.jsonObject
        assertEquals("915.125", config?.get("freq")?.jsonPrimitive?.content.toString())
        assertEquals("30", config?.get("txpower")?.jsonPrimitive?.content.toString())
        assertEquals("0x0001", config?.get("addr")?.jsonPrimitive?.content)
        assertEquals("1", config?.get("txmode")?.jsonPrimitive?.content.toString())
        assertEquals("34", config?.get("crypt_l")?.jsonPrimitive?.content.toString())
    }

    @Test
    fun `default radio config starts on channel 80 with RSSI helpers enabled`() {
        val defaults = E220Config()
        assertEquals("930.125", defaults.freq)
        assertEquals("1", defaults.rssiNoise)
        assertEquals("1", defaults.rssiByte)
        assertEquals("-85", defaults.lbrRssi)
    }

    @Test
    fun `default binary config uses shared group address and channel 80 in legacy form`() {
        val legacy = E220ConfigMapper.toLegacy(E220ConfigMapper.defaultBinaryConfig("3C:71:BF:6B:E4:9E"))

        assertEquals("930.125", legacy.freq)
        assertEquals("0x0001", legacy.addr)
        assertEquals("1", legacy.rssiNoise)
        assertEquals("1", legacy.rssiByte)
        assertEquals("-85", legacy.lbrRssi)
        assertFalse(validateConfig(legacy).containsKey("addr"))
    }

    @Test
    fun `binary mapping does not reuse status interval for lbt rssi`() {
        val current = BleConfig(
            userId24 = 0x123456,
            username = "node-A",
            statusIntervalMs = 1200
        )
        val config = E220Config(lbrRssi = "-128")

        val binary = E220ConfigMapper.toBinary(config, current)

        assertEquals(1200, binary.statusIntervalMs)
    }

    @Test
    fun `parse chat response reads nested data messages and marks sent ones delivered`() {
        val responseJson = buildJsonObject {
            put("ok", true)
            put("path", "/api/chat")
            putJsonObject("data") {
                put("sequence", 7)
                putJsonArray("messages") {
                    add(JsonPrimitive("[RX] hello"))
                    add(JsonPrimitive("[TX] hi back"))
                }
            }
        }

        val chat = E220Protocol.parseChatResponse(responseJson)

        assertEquals(7, chat.sequence)
        assertEquals(2, chat.messages.size)
        assertEquals("hello", chat.messages[0].text)
        assertTrue(!chat.messages[0].sent)
        assertEquals("hi back", chat.messages[1].text)
        assertTrue(chat.messages[1].sent)
        assertTrue(chat.messages[1].delivered)
    }

    @Test
    fun `parse diagnostics response reads nested firmware fields`() {
        val responseJson = buildJsonObject {
            put("ok", true)
            put("path", "/api/diagnostics")
            putJsonObject("data") {
                put("uptime_ms", 1234)
                put("free_heap", 45678)
                put("min_free_heap", 40000)
                put("bt_name", "E220-Chat-ABCDEF")
                put("bt_has_client", true)
                put("e220_timeout_count", 2)
                put("e220_rx_errors", 3)
                put("e220_tx_errors", 4)
                put("bt_request_count", 5)
                put("bt_parse_errors", 6)
                put("bt_raw_message_count", 7)
                put("last_rssi", -72)
            }
        }

        val diagnostics = E220Protocol.parseDiagnosticsResponse(responseJson)

        assertEquals(1234L, diagnostics.uptimeMs)
        assertEquals(45678L, diagnostics.freeHeap)
        assertEquals(40000L, diagnostics.minFreeHeap)
        assertEquals("E220-Chat-ABCDEF", diagnostics.btName)
        assertTrue(diagnostics.btHasClient)
        assertEquals(2, diagnostics.e220Timeouts)
        assertEquals(6, diagnostics.btParseErrors)
        assertEquals(-72, diagnostics.lastRssi)
    }

    @Test
    fun `parse debug response returns nested log text with newlines unescaped`() {
        val responseJson = buildJsonObject {
            put("ok", true)
            put("path", "/api/debug")
            putJsonObject("data") {
                put("log", "[TX] hello\\n[RX] hi")
            }
        }

        assertEquals("[TX] hello\n[RX] hi", E220Protocol.parseDebugLog(responseJson))
    }
}
