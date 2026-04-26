package com.dmahony.e220chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class E220ProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `build send request nests message under body field`() {
        val request = E220Protocol.buildSendRequest("hello radio")
        val envelope = json.parseToJsonElement(request).jsonObject

        assertEquals("/api/send", envelope["path"]?.jsonPrimitive?.content)
        assertEquals("POST", envelope["method"]?.jsonPrimitive?.content)
        assertEquals("hello radio", envelope["body"]?.jsonObject?.get("message")?.jsonPrimitive?.content)
    }

    @Test
    fun `build config request nests values under config field`() {
        val request = E220Protocol.buildConfigRequest(
            E220Config(
                freq = "915.125",
                txpower = "30",
                baud = "9600",
                addr = "0x0001",
                dest = "0xFFFF",
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
        val envelope = json.parseToJsonElement(request).jsonObject
        val config = envelope["config"]!!.jsonObject
        val bodyConfig = envelope["body"]!!.jsonObject["config"]!!.jsonObject

        assertEquals(915.125, requireNotNull(config["freq"]?.jsonPrimitive?.doubleOrNull), 0.0)
        assertEquals(30, config["txpower"]?.jsonPrimitive?.intOrNull)
        assertEquals("0x0001", config["addr"]?.jsonPrimitive?.content)
        assertEquals(1, config["txmode"]?.jsonPrimitive?.intOrNull)
        assertEquals(34, config["crypt_l"]?.jsonPrimitive?.intOrNull)
        assertEquals(915.125, requireNotNull(bodyConfig["freq"]?.jsonPrimitive?.doubleOrNull), 0.0)
    }

    @Test
    fun `build clear chat request posts to chat clear endpoint`() {
        val request = E220Protocol.buildClearChatRequest()
        val envelope = json.parseToJsonElement(request).jsonObject

        assertEquals("/api/chat/clear", envelope["path"]?.jsonPrimitive?.content)
        assertEquals("POST", envelope["method"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parse chat response reads nested data messages and marks sent ones delivered`() {
        val response = """
            {
              "ok": true,
              "path": "/api/chat",
              "data": {
                "sequence": 7,
                "messages": ["[RX] hello", "[TX] hi back"]
              }
            }
        """.trimIndent()

        val chat = E220Protocol.parseChatResponse(response)

        assertEquals(7, chat.sequence)
        assertEquals(2, chat.messages.size)
        assertEquals("hello", chat.messages[0].text)
        assertTrue(!chat.messages[0].sent)
        assertTrue(!chat.messages[0].delivered)
        assertEquals("hi back", chat.messages[1].text)
        assertTrue(chat.messages[1].sent)
        assertTrue(chat.messages[1].delivered)
    }

    @Test
    fun `parse diagnostics response reads nested firmware fields`() {
        val response = """
            {
              "ok": true,
              "path": "/api/diagnostics",
              "data": {
                "uptime_ms": 1234,
                "free_heap": 45678,
                "min_free_heap": 40000,
                "bt_name": "E220-Chat-ABCDEF",
                "bt_has_client": true,
                "e220_timeout_count": 2,
                "e220_rx_errors": 3,
                "e220_tx_errors": 4,
                "bt_request_count": 5,
                "bt_parse_errors": 6,
                "bt_raw_message_count": 7,
                "last_rssi": -72
              }
            }
        """.trimIndent()

        val diagnostics = E220Protocol.parseDiagnosticsResponse(response)

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
    fun `parse debug response returns nested log text`() {
        val response = """
            {
              "ok": true,
              "path": "/api/debug",
              "data": {
                "log": "[TX] hello\\n[RX] hi"
              }
            }
        """.trimIndent()

        assertEquals("[TX] hello\n[RX] hi", E220Protocol.parseDebugLog(response))
    }

    @Test
    fun `parse config response accepts nested data config object`() {
        val response = """
            {
              "ok": true,
              "data": {
                "config": {
                  "freq": 930.125,
                  "txpower": 30,
                  "baud": 9600,
                  "addr": "0x0001",
                  "dest": "0xFFFF",
                  "airrate": 2,
                  "wor_cycle": 3
                }
              }
            }
        """.trimIndent()

        val config = E220Protocol.parseConfigResponse(response)

        assertEquals("930.125", config.freq)
        assertEquals("30", config.txpower)
        assertEquals("0x0001", config.addr)
    }

    @Test
    fun `parse config response accepts flat web config object`() {
        val response = """
            {
              "config": {
                "freq": 915.125,
                "txpower": 27,
                "baud": 19200,
                "addr": "0x0002"
              }
            }
        """.trimIndent()

        val config = E220Protocol.parseConfigResponse(response)

        assertEquals("915.125", config.freq)
        assertEquals("27", config.txpower)
        assertEquals("19200", config.baud)
        assertEquals("0x0002", config.addr)
    }
}
