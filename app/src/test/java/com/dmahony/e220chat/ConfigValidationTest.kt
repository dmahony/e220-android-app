package com.dmahony.e220chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigValidationTest {

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
        lbrRssi: String = "-85",
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

    // ─── AP password validation ───

    @Test
    fun `AP password must be at least 8 characters when WiFi AP is enabled`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApPassword = "1234567")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_ap_password"))
        assertEquals("AP password must be at least 8 characters", errors["wifi_ap_password"])
    }

    @Test
    fun `AP password of 8 characters passes validation`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApPassword = "12345678")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("wifi_ap_password"))
    }

    @Test
    fun `AP password of 63 characters passes validation`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApPassword = "A".repeat(63))
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("wifi_ap_password"))
    }

    @Test
    fun `AP password longer than 63 characters is rejected`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApPassword = "A".repeat(64))
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_ap_password"))
        assertEquals("AP password must be 63 characters or fewer", errors["wifi_ap_password"])
    }

    @Test
    fun `blank AP password is rejected when AP is enabled`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApPassword = "")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_ap_password"))
        assertEquals("AP password must be at least 8 characters", errors["wifi_ap_password"])
    }

    @Test
    fun `AP password is not validated when WiFi is disabled`() {
        val config = makeConfig(wifiEnabled = "0", wifiMode = "AP", wifiApPassword = "12")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("wifi_ap_password"))
    }

    @Test
    fun `AP password is not validated in STA-only mode`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "STA", wifiApPassword = "12")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("wifi_ap_password"))
    }

    @Test
    fun `AP password is validated in AP_STA mode`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP_STA", wifiApPassword = "1234567")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_ap_password"))
    }

    // ─── STA password validation ───

    @Test
    fun `STA password of 63 characters passes validation`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "STA", wifiStaPassword = "B".repeat(63))
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("wifi_sta_password"))
    }

    @Test
    fun `STA password longer than 63 characters is rejected`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "STA", wifiStaPassword = "B".repeat(64))
        val errors = validateConfig(config)
        assertEquals("STA password must be 63 characters or fewer", errors["wifi_sta_password"])
    }

    // ─── SSID validation ───

    @Test
    fun `AP SSID is required when AP mode is enabled`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApSsid = "")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_ap_ssid"))
        assertEquals("AP SSID is required when WiFi AP mode is enabled", errors["wifi_ap_ssid"])
    }

    @Test
    fun `STA SSID is required when STA mode is enabled`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "STA", wifiStaSsid = "")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_sta_ssid"))
    }

    @Test
    fun `both SSIDs can be required in AP_STA mode`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP_STA", wifiApSsid = "", wifiStaSsid = "")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_ap_ssid"))
        assertTrue(errors.containsKey("wifi_sta_ssid"))
    }

    @Test
    fun `SSID of 32 characters passes validation`() {
        val ssid = "A".repeat(32)
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApSsid = ssid)
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("wifi_ap_ssid"))
    }

    @Test
    fun `SSID longer than 32 characters is rejected`() {
        val ssid = "A".repeat(33)
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApSsid = ssid)
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_ap_ssid"))
        assertEquals("AP SSID must be 32 characters or fewer", errors["wifi_ap_ssid"])
    }

    @Test
    fun `SSID is not required when WiFi is disabled`() {
        val config = makeConfig(wifiEnabled = "0", wifiMode = "AP", wifiApSsid = "")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("wifi_ap_ssid"))
    }

    // ─── Hex field validation ───

    @Test
    fun `hex address with 0x prefix passes validation`() {
        val config = makeConfig(addr = "0x1234")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("addr"))
    }

    @Test
    fun `hex address with BEEF value passes validation`() {
        val config = makeConfig(addr = "BEEF")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("addr"))
    }

    @Test
    fun `hex address with 0X prefix passes validation`() {
        val config = makeConfig(addr = "0XABCD")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("addr"))
    }

    @Test
    fun `FFFF is valid hex address`() {
        val config = makeConfig(addr = "FFFF")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("addr"))
    }

    @Test
    fun `10000 is invalid hex address`() {
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
    fun `non-hex value is invalid`() {
        val config = makeConfig(addr = "ZYXW")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("addr"))
    }

    @Test
    fun `dest field is validated as hex`() {
        val config = makeConfig(dest = "GGGG")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("dest"))
    }

    // ─── Integer boundary validation ───

    @Test
    fun `lbr_rssi at minimum passes`() {
        val config = makeConfig(lbrRssi = "-128")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("lbr_rssi"))
    }

    @Test
    fun `lbr_rssi at maximum passes`() {
        val config = makeConfig(lbrRssi = "0")
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
    fun `lbr_timeout at maximum passes`() {
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
    fun `crypt_h at maximum passes`() {
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
    fun `urxt at maximum passes`() {
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

    // ─── Choice validation ───

    @Test
    fun `invalid wifi_enabled value is rejected`() {
        val config = makeConfig(wifiEnabled = "2")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_enabled"))
    }

    @Test
    fun `invalid wifi_mode value is rejected`() {
        val config = makeConfig(wifiMode = "CLIENT")
        val errors = validateConfig(config)
        assertTrue(errors.containsKey("wifi_mode"))
    }

    @Test
    fun `valid wifi_enabled values pass`() {
        val errorsOn = validateConfig(makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApSsid = "Test"))
        assertFalse(errorsOn.containsKey("wifi_enabled"))

        val errorsOff = validateConfig(makeConfig(wifiEnabled = "0"))
        assertFalse(errorsOff.containsKey("wifi_enabled"))
    }

    // ─── Validation summary / exception ───

    @Test
    fun `formatConfigValidationSummary formats errors correctly`() {
        val errors = mapOf("field1" to "error1", "field2" to "error2")
        val summary = formatConfigValidationSummary(errors)
        assertTrue(summary.contains("field1: error1"))
        assertTrue(summary.contains("field2: error2"))
    }

    @Test
    fun `ConfigValidationException carries field errors`() {
        val errors = mapOf("test_field" to "test error")
        val ex = ConfigValidationException(errors)
        assertEquals(errors, ex.fieldErrors)
        assertTrue(ex.message!!.contains("test_field: test error"))
    }

    @Test
    fun `requireValidConfig throws for invalid config`() {
        val config = makeConfig(wifiEnabled = "1", wifiMode = "AP", wifiApSsid = "", wifiApPassword = "short")
        try {
            requireValidConfig(config)
            fail("Expected ConfigValidationException")
        } catch (e: ConfigValidationException) {
            assertTrue(e.fieldErrors.isNotEmpty())
        }
    }

    @Test
    fun `requireValidConfig does not throw for valid config`() {
        val config = makeConfig() // defaults are valid
        requireValidConfig(config) // should not throw
    }

    // ─── Empty errors map ───

    @Test
    fun `empty errors map formats correctly`() {
        val summary = formatConfigValidationSummary(emptyMap())
        assertEquals("", summary)
    }

    // ─── Whitespace handling ───

    @Test
    fun `freq with whitespace is trimmed during validation`() {
        val config = makeConfig(freq = "  868.125  ")
        val errors = validateConfig(config)
        assertFalse(errors.containsKey("freq"))
    }
}
