package com.dmahony.e220chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for config validation and Compose form behavior in the
 * existing unit test harness (no Compose UI testing framework needed).
 *
 * Covers:
 *  - Password length boundaries (7/8/63/64 chars)
 *  - AP/STA/AP_STA SSID requirements
 *  - SSID length limits (32/33 chars)
 *  - Hex address validation (0x/BEEF/0X/FFFF/10000/negative/non-hex)
 *  - Dropdown choice validation (txpower/baud/wifi_enabled/wifi_mode)
 *  - Integer boundaries (lbr_timeout/lbr_rssi/crypt_h/urxt)
 *  - Multi-field error accumulation
 *  - requireValidConfig behavior
 *  - ConfigValidationException message format
 *  - Whitespace handling
 */
class ComposeFormValidationTest {

    private fun makeConfig(
        wifiEnabled: String = "0",
        wifiMode: String = "AP",
        wifiApSsid: String = "",
        wifiApPassword: String = "",
        wifiStaSsid: String = "",
        wifiStaPassword: String = "",
        freq: String = "868.125",
        txpower: String = "21",
        baud: String = "9600",
        addr: String = "0x0000",
        dest: String = "0xFFFF",
        lbrRssi: String = "-55",
        lbrTimeout: String = "2000",
        cryptH: String = "0",
        urxt: String = "3",
        saveType: String = "1"
    ) = E220Config(
        wifiEnabled = wifiEnabled,
        wifiMode = wifiMode,
        wifiApSsid = wifiApSsid,
        wifiApPassword = wifiApPassword,
        wifiStaSsid = wifiStaSsid,
        wifiStaPassword = wifiStaPassword,
        freq = freq,
        txpower = txpower,
        baud = baud,
        addr = addr,
        dest = dest,
        lbrRssi = lbrRssi,
        lbrTimeout = lbrTimeout,
        cryptH = cryptH,
        urxt = urxt,
        saveType = saveType
    )

    // ─── Password length boundaries ───

    @Test
    fun `AP password of 7 characters is rejected`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApPassword = "1234567")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_ap_password"))
    }

    @Test
    fun `AP password of 8 characters passes validation`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApPassword = "12345678")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("wifi_ap_password"))
    }

    @Test
    fun `AP password of 63 characters passes validation`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApPassword = "P".repeat(63))
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("wifi_ap_password"))
    }

    @Test
    fun `AP password of 64 characters is rejected`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApPassword = "P".repeat(64))
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_ap_password"))
    }

    @Test
    fun `blank AP password is rejected when AP mode enabled`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApPassword = "")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_ap_password"))
    }

    @Test
    fun `STA password of 63 characters passes validation`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "STA", wifiStaPassword = "S".repeat(63))
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("wifi_sta_password"))
    }

    @Test
    fun `STA password of 64 characters is rejected`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "STA", wifiStaPassword = "S".repeat(64))
        val errors = validateConfig(config)
        assertEquals("STA password must be 63 characters or fewer", errors["wifi_sta_password"])
    }

    // ─── AP/STA/AP_STA SSID requirements ───

    @Test
    fun `AP SSID is required when WiFi AP mode is enabled`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApSsid = "")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_ap_ssid"))
        assertEquals("AP SSID is required when WiFi AP mode is enabled", errors["wifi_ap_ssid"])
    }

    @Test
    fun `AP SSID passed to AP mode with valid config`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApSsid = "MyDevice", wifiApPassword = "password1")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("wifi_ap_ssid"))
    }

    @Test
    fun `STA SSID is required when WiFi STA mode is enabled`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "STA", wifiStaSsid = "")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_sta_ssid"))
    }

    @Test
    fun `both SSIDs are required in AP_STA mode`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP_STA", wifiApSsid = "", wifiStaSsid = "")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_ap_ssid"))
        assertTrue(errors.containsKey("wifi_sta_ssid"))
    }

    // ─── SSID length limits ───

    @Test
    fun `SSID of 32 characters passes validation`() {
        val ssid = "X".repeat(32)
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApSsid = ssid)
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("wifi_ap_ssid"))
    }

    @Test
    fun `SSID of 33 characters is rejected`() {
        val ssid = "X".repeat(33)
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApSsid = ssid)
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_ap_ssid"))
        assertEquals("AP SSID must be 32 characters or fewer", errors["wifi_ap_ssid"])
    }

    // ─── Hex address validation ───

    @Test
    fun `0x-prefixed hex address is valid`() {
        val config = makeConfig(addr = "0x1234")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("addr"))
    }

    @Test
    fun `BEEF hex address is valid`() {
        val config = makeConfig(addr = "BEEF")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("addr"))
    }

    @Test
    fun `0X-prefixed hex address is valid`() {
        val config = makeConfig(addr = "0XABCD")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("addr"))
    }

    @Test
    fun `FFFF hex address is valid`() {
        val config = makeConfig(addr = "FFFF")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("addr"))
    }

    @Test
    fun `10000 hex address is invalid`() {
        val config = makeConfig(addr = "10000")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("addr"))
    }

    @Test
    fun `negative hex value is invalid`() {
        val config = makeConfig(addr = "-1")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("addr"))
    }

    @Test
    fun `non-hex value is rejected`() {
        val config = makeConfig(addr = "ZYXW")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("addr"))
    }

    // ─── Dropdown choice validation ───

    @Test
    fun `valid wifi_enabled value passes`() {
        val errors = validateConfig(makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApSsid = "Test"))
        assertFalse(errors.containsKey("wifi_enabled"))
    }

    @Test
    fun `invalid wifi_enabled value is rejected`() {
        val errors = validateConfig(makeConfig(wifiEnabled = "2"))
        assertTrue(errors.containsKey("wifi_enabled"))
    }

    @Test
    fun `valid wifi_mode values pass`() {
        listOf("AP", "STA", "AP_STA").forEach { mode ->
            val config = makeConfig(
                wifiEnabled = "1", wifiMode = mode,
                wifiApSsid = "Test", wifiStaSsid = "Test"
            )
            val errors = validateConfig(config)
            assertFalse("Mode $mode should be valid", errors.containsKey("wifi_mode"))
        }
    }

    @Test
    fun `invalid wifi_mode value is rejected`() {
        val config = makeConfig(wifiMode = "CLIENT")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_mode"))
    }

    @Test
    fun `invalid txpower value is rejected`() {
        val config = makeConfig(txpower = "999")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("txpower"))
    }

    @Test
    fun `invalid baud value is rejected`() {
        val config = makeConfig(baud = "999999")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("baud"))
    }

    // ─── Integer boundaries ───

    @Test
    fun `lbr_rssi at minimum boundary passes`() {
        val config = makeConfig(lbrRssi = "-128")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("lbr_rssi"))
    }

    @Test
    fun `lbr_rssi below minimum is rejected`() {
        val config = makeConfig(lbrRssi = "-129")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("lbr_rssi"))
    }

    @Test
    fun `lbr_timeout at maximum boundary passes`() {
        val config = makeConfig(lbrTimeout = "65535")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("lbr_timeout"))
    }

    @Test
    fun `lbr_timeout above maximum is rejected`() {
        val config = makeConfig(lbrTimeout = "65536")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("lbr_timeout"))
    }

    @Test
    fun `crypt_h at maximum boundary passes`() {
        val config = makeConfig(cryptH = "255")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("crypt_h"))
    }

    @Test
    fun `crypt_h above maximum is rejected`() {
        val config = makeConfig(cryptH = "256")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("crypt_h"))
    }

    @Test
    fun `urxt at maximum boundary passes`() {
        val config = makeConfig(urxt = "255")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("urxt"))
    }

    @Test
    fun `urxt at zero is rejected`() {
        val config = makeConfig(urxt = "0")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("urxt"))
    }

    // ─── Multi-field error accumulation ───

    @Test
    fun `multiple invalid fields accumulate in errors map`() {
        val config = makeConfig(
            wifiEnabled = "1",
            wifiMode = "INVALID",
            addr = "ZZZZ",
            lbrRssi = "100",
            cryptH = "300",
            lbrTimeout = "99999"
        )
        val errors = validateConfig(config)
        assertTrue(errors.size >= 5)
    }

    @Test
    fun `all validation errors are collected for config with many problems`() {
        val config = makeConfig(
            wifiEnabled = "1",
            wifiMode = "AP_STA",
            wifiApSsid = "", // missing
            wifiStaSsid = "", // missing
            wifiApPassword = "short", // < 8
            addr = "GGGG", // invalid hex
            txpower = "1000", // invalid
            baud = "123456", // invalid
            lbrRssi = "50", // > 0
            lbrTimeout = "100000", // > 65535
            cryptH = "999", // > 255
            urxt = "0" // < 1
        )
        val errors = validateConfig(config)
        assertTrue(errors.size >= 9)
    }

    // ─── requireValidConfig ───

    @Test
    fun `requireValidConfig throws ConfigValidationException for invalid config`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApSsid = "", wifiApPassword = "abc")
        try {
            requireValidConfig(config)
            fail("Expected ConfigValidationException")
        } catch (e: ConfigValidationException) {
            assertTrue(e.fieldErrors.isNotEmpty())
        }
    }

    @Test
    fun `requireValidConfig does not throw for valid minimal config`() {
        val config = makeConfig()
        try {
            requireValidConfig(config)
        } catch (e: ConfigValidationException) {
            fail("Should not throw: " + e.message)
        }
    }

    // ─── ConfigValidationException message format ───

    @Test
    fun `ConfigValidationException message contains field errors`() {
        val errors = mapOf("field_a" to "error A", "field_b" to "error B")
        val ex = ConfigValidationException(errors)
        assertTrue(ex.message!!.contains("field_a: error A"))
        assertTrue(ex.message!!.contains("field_b: error B"))
        assertEquals(errors, ex.fieldErrors)
    }

    // ─── Whitespace handling ───

    @Test
    fun `freq with whitespace is trimmed during validation`() {
        val config = makeConfig(freq = "  868.125  ")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("freq"))
    }

    @Test
    fun `dest field whitespace handling`() {
        val config = makeConfig(dest = "  0xFFFF  ")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("dest"))
    }

    // ─── Edge cases for empty / blank ───

    @Test
    fun `validation returns empty map for fully valid config`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApSsid = "MyESP", wifiApPassword = "password123")
        val errors = validateConfig(config)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `STA mode with password and SSID is valid`() {
        val config = makeConfig(
            wifiEnabled = "1", wifiMode = "STA",
            wifiStaSsid = "HomeWiFi", wifiStaPassword = "homepass123"
        )
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("wifi_sta_ssid"))
        assertFalse(errors.containsKey("wifi_sta_password"))
    }

    @Test
    fun `saveType non-integer is rejected`() {
        val config = makeConfig(saveType = "not_a_number")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("savetype"))
    }

    @Test
    fun `saveType integer passes validation`() {
        val config = makeConfig(saveType = "1")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("savetype"))
    }
}
