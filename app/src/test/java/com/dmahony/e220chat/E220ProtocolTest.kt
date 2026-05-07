package com.dmahony.e220chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class E220ProtocolTest {
    @Test
    fun `build send request nests message under body field`() {
        val request = E220Protocol.buildSendRequest("hello radio")

        assertEquals("/api/send", request.getString("path"))
        assertEquals("POST", request.getString("method"))
        val body = request.getJSONObject("body")
        assertEquals("hello radio", body.getString("message"))
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

        assertEquals("/api/config", request.getString("path"))
        assertEquals("POST", request.getString("method"))
        val config = request.getJSONObject("config")
        assertEquals(915.125, config.getDouble("freq"), 0.0)
        assertEquals(30, config.getInt("txpower"))
        assertEquals("0x0001", config.getString("addr"))
        assertEquals(1, config.getInt("txmode"))
        assertEquals(34, config.getInt("crypt_l"))
    }

    @Test
    fun `parse chat response reads nested data messages and marks sent ones delivered`() {
        val response = JSONObject(
            """
            {
              "ok": true,
              "path": "/api/chat",
              "data": {
                "sequence": 7,
                "messages": ["[RX] hello", "[TX] hi back"]
              }
            }
            """.trimIndent()
        )

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
        val response = JSONObject(
            """
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
        )

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
        val response = JSONObject(
            """
            {
              "ok": true,
              "path": "/api/debug",
              "data": {
                "log": "[TX] hello\\n[RX] hi"
              }
            }
            """.trimIndent()
        )

        assertEquals("[TX] hello\n[RX] hi", E220Protocol.parseDebugLog(response))
    }
}
