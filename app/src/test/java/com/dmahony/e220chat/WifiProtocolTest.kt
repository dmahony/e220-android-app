package com.dmahony.e220chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `wifi status request targets the status endpoint`() {
        val request = E220Protocol.buildWifiGetRequest()

        val envelope = json.parseToJsonElement(request).jsonObject
        assertEquals("/api/wifi/status", envelope["path"]?.jsonPrimitive?.content)
        assertEquals("GET", envelope["method"]?.jsonPrimitive?.content)
    }

    @Test
    fun `wifi toggle request posts enabled state under body field`() {
        val request = E220Protocol.buildWifiToggleRequest(true)

        val envelope = json.parseToJsonElement(request).jsonObject
        assertEquals("/api/wifi/toggle", envelope["path"]?.jsonPrimitive?.content)
        assertEquals("POST", envelope["method"]?.jsonPrimitive?.content)
        assertEquals(true, envelope["body"]?.jsonObject?.get("enabled")?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `wifi status parser accepts boolean and numeric enabled fields`() {
        val booleanResponse = """
            {
              "ok": true,
              "data": {
                "enabled": true,
                "mode": "AP_STA",
                "sta_connected": true,
                "sta_ssid": "Home WiFi"
              }
            }
        """.trimIndent()

        val numericResponse = """
            {
              "ok": true,
              "data": {
                "enabled": 1,
                "sta_connected": 0
              }
            }
        """.trimIndent()

        val status = E220Protocol.parseWifiStatus(booleanResponse)
        val numericStatus = E220Protocol.parseWifiStatus(numericResponse)

        assertEquals(true, status.enabled)
        assertEquals("AP_STA", status.mode)
        assertEquals(true, status.staConnected)
        assertEquals("Home WiFi", status.staSsid)
        assertEquals(true, numericStatus.enabled)
        assertEquals(false, numericStatus.staConnected)
    }

    @Test
    fun `wifi scan result parser reads scanned networks from operation payload`() {
        val operation = OperationStatus(
            type = "wifi_scan",
            state = "success",
            message = "WiFi scan complete: 1 network(s) found",
            updatedAtMs = 123456L,
            rawResult = """
                {
                  "scan": {
                    "status": "success",
                    "requested_at_ms": 1000,
                    "completed_at_ms": 1500,
                    "duration_ms": 500,
                    "network_count": 1
                  },
                  "networks": [
                    {
                      "ssid": "Cafe WiFi",
                      "rssi": -42,
                      "encryption": "Open",
                      "channel": 6
                    }
                  ]
                }
            """.trimIndent()
        )

        val scan = E220Protocol.parseWifiScanResult(operation)

        assertEquals("success", scan.scan.status)
        assertEquals(1000L, scan.scan.requestedAtMs)
        assertEquals(1500L, scan.scan.completedAtMs)
        assertEquals(500L, scan.scan.durationMs)
        assertEquals(1, scan.scan.networkCount)
        assertEquals(1, scan.networks.size)
        assertEquals("Cafe WiFi", scan.networks.single().ssid)
        assertEquals(-42, scan.networks.single().rssi)
        assertEquals(false, scan.networks.single().encrypted)
        assertEquals(6, scan.networks.single().channel)
    }

    @Test
    fun `wifi scan result parser captures esp32 error details`() {
        val operation = OperationStatus(
            type = "wifi_scan",
            state = "error",
            message = "WiFi scan failed (error -2)",
            rawResult = """
                {
                  "scan": {
                    "status": "error",
                    "error_code": -2,
                    "error": "scan failed"
                  },
                  "networks": []
                }
            """.trimIndent()
        )

        val scan = E220Protocol.parseWifiScanResult(operation)

        assertEquals("error", scan.scan.status)
        assertEquals(-2, scan.scan.errorCode)
        assertEquals("scan failed", scan.scan.error)
        assertEquals(0, scan.networks.size)
    }
}
