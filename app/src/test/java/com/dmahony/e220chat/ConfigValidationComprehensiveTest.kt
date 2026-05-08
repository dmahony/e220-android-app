package com.dmahony.e220chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigValidationComprehensiveTest {
    // ─── Password validation ───

    @Test
    fun `validatePassword rejects blank password when WiFi AP mode is active`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "AP",
                wifiApPassword = ""
            )
        )
        assertEquals("AP password must be at least 8 characters", errors["wifi_ap_password"])
    }

    @Test
    fun `validatePassword rejects short password when WiFi AP mode is active`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "AP",
                wifiApPassword = "short"
            )
        )
        assertEquals("AP password must be at least 8 characters", errors["wifi_ap_password"])
    }

    @Test
    fun `validatePassword accepts minimum 8 char password`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "AP",
                wifiApSsid = "MyAP",
                wifiApPassword = "12345678"
            )
        )
        assertEquals(null, errors["wifi_ap_password"])
    }

    @Test
    fun `validatePassword accepts password at maximum 63 chars`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "AP",
                wifiApSsid = "MyAP",
                wifiApPassword = "a".repeat(63)
            )
        )
        assertEquals(null, errors["wifi_ap_password"])
    }

    @Test
    fun `validatePassword rejects password exceeding 63 chars`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "AP",
                wifiApSsid = "MyAP",
                wifiApPassword = "a".repeat(64)
            )
        )
        assertEquals("AP password must be 63 characters or fewer", errors["wifi_ap_password"])
    }

    @Test
    fun `validatePassword does not require password when WiFi is disabled`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "0",
                wifiMode = "AP",
                wifiApPassword = ""
            )
        )
        assertEquals(null, errors["wifi_ap_password"])
    }

    @Test
    fun `validatePassword rejects password longer than 63 even when WiFi disabled`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "0",
                wifiApPassword = "a".repeat(64)
            )
        )
        assertEquals("AP password must be 63 characters or fewer", errors["wifi_ap_password"])
    }

    @Test
    fun `validatePassword requires password for AP_STA mode`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "AP_STA",
                wifiApSsid = "MyAP",
                wifiStaSsid = "MySTA",
                wifiApPassword = "",
                wifiStaPassword = "validpass"
            )
        )
        assertEquals("AP password must be at least 8 characters", errors["wifi_ap_password"])
    }

    @Test
    fun `validatePassword does not require AP password in STA only mode`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "STA",
                wifiStaSsid = "MySTA",
                wifiApPassword = "",
                wifiStaPassword = "validpass"
            )
        )
        assertEquals(null, errors["wifi_ap_password"])
    }

    @Test
    fun `validatePassword rejects STA password longer than 63 chars`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "STA",
                wifiStaSsid = "MySTA",
                wifiStaPassword = "a".repeat(64)
            )
        )
        assertEquals("STA password must be 63 characters or fewer", errors["wifi_sta_password"])
    }

    @Test
    fun `validatePassword allows STA password at 63 chars`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "STA",
                wifiStaSsid = "MySTA",
                wifiStaPassword = "a".repeat(63)
            )
        )
        assertEquals(null, errors["wifi_sta_password"])
    }

    // ─── SSID validation ───

    @Test
    fun `validateSsid rejects SSID longer than 32`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "AP",
                wifiApSsid = "a".repeat(33),
                wifiApPassword = "correcthorsebatterystaple"
            )
        )
        assertEquals("AP SSID must be 32 characters or fewer", errors["wifi_ap_ssid"])
    }

    @Test
    fun `validateSsid accepts SSID at exactly 32 chars`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "AP",
                wifiApSsid = "a".repeat(32),
                wifiApPassword = "correcthorsebatterystaple"
            )
        )
        assertEquals(null, errors["wifi_ap_ssid"])
    }

    @Test
    fun `validateSsid ignores empty SSID for length check`() {
        // Empty SSID triggers the "required" error, not the length error
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "AP",
                wifiApSsid = "",
                wifiApPassword = "correcthorsebatterystaple"
            )
        )
        assertEquals("AP SSID is required when WiFi AP mode is enabled", errors["wifi_ap_ssid"])
    }

    // ─── WiFi network requirements ───

    @Test
    fun `validateWiFi requires AP SSID when AP mode is enabled`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "AP",
                wifiApSsid = "",
                wifiApPassword = "validpass1"
            )
        )
        assertEquals("AP SSID is required when WiFi AP mode is enabled", errors["wifi_ap_ssid"])
    }

    @Test
    fun `validateWiFi requires STA SSID when STA mode is enabled`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "STA",
                wifiStaSsid = "",
                wifiStaPassword = "validpass1"
            )
        )
        assertEquals("STA SSID is required when WiFi STA mode is enabled", errors["wifi_sta_ssid"])
    }

    @Test
    fun `validateWiFi requires both SSIDs for AP_STA mode`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "1",
                wifiMode = "AP_STA",
                wifiApSsid = "",
                wifiStaSsid = "",
                wifiApPassword = "validpass1",
                wifiStaPassword = "validpass1"
            )
        )
        assertEquals("AP SSID is required when WiFi AP mode is enabled", errors["wifi_ap_ssid"])
        assertEquals("STA SSID is required when WiFi STA mode is enabled", errors["wifi_sta_ssid"])
    }

    @Test
    fun `validateWiFi does not require SSIDs when WiFi is disabled`() {
        val errors = validateConfig(
            E220Config(
                wifiEnabled = "0",
                wifiMode = "AP",
                wifiApSsid = "",
                wifiStaSsid = ""
            )
        )
        assertEquals(null, errors["wifi_ap_ssid"])
        assertEquals(null, errors["wifi_sta_ssid"])
    }

    // ─── Field range validation ───

    @Test
    fun `validateIntRange for crypt_h accepts boundary values`() {
        val errorsMin = validateConfig(E220Config(cryptH = "0"))
        assertEquals(null, errorsMin["crypt_h"])

        val errorsMax = validateConfig(E220Config(cryptH = "255"))
        assertEquals(null, errorsMax["crypt_h"])

        val errorsOver = validateConfig(E220Config(cryptH = "256"))
        assertEquals("Crypto high must be 0-255", errorsOver["crypt_h"])

        val errorsUnder = validateConfig(E220Config(cryptH = "-1"))
        assertEquals("Crypto high must be 0-255", errorsUnder["crypt_h"])
    }

    @Test
    fun `validateIntRange for lbr_rssi accepts boundary values`() {
        val errorsMin = validateConfig(E220Config(lbrRssi = "-128"))
        assertEquals(null, errorsMin["lbr_rssi"])

        val errorsMax = validateConfig(E220Config(lbrRssi = "0"))
        assertEquals(null, errorsMax["lbr_rssi"])

        val errorsUnder = validateConfig(E220Config(lbrRssi = "-129"))
        assertEquals("LBT RSSI must be between -128 and 0 dBm", errorsUnder["lbr_rssi"])

        val errorsOver = validateConfig(E220Config(lbrRssi = "1"))
        assertEquals("LBT RSSI must be between -128 and 0 dBm", errorsOver["lbr_rssi"])
    }

    @Test
    fun `validateIntRange for lbr_timeout accepts boundary values`() {
        val errorsMin = validateConfig(E220Config(lbrTimeout = "0"))
        assertEquals(null, errorsMin["lbr_timeout"])

        val errorsMax = validateConfig(E220Config(lbrTimeout = "65535"))
        assertEquals(null, errorsMax["lbr_timeout"])

        val errorsOver = validateConfig(E220Config(lbrTimeout = "65536"))
        assertEquals("LBT timeout must be between 0 and 65535 ms", errorsOver["lbr_timeout"])

        val errorsUnder = validateConfig(E220Config(lbrTimeout = "-1"))
        assertEquals("LBT timeout must be between 0 and 65535 ms", errorsUnder["lbr_timeout"])
    }

    @Test
    fun `validateIntRange for urxt accepts boundary values`() {
        val errorsMin = validateConfig(E220Config(urxt = "1"))
        assertEquals(null, errorsMin["urxt"])

        val errorsMax = validateConfig(E220Config(urxt = "255"))
        assertEquals(null, errorsMax["urxt"])

        val errorsUnder = validateConfig(E220Config(urxt = "0"))
        assertEquals("URXT must be between 1 and 255 byte times", errorsUnder["urxt"])

        val errorsOver = validateConfig(E220Config(urxt = "256"))
        assertEquals("URXT must be between 1 and 255 byte times", errorsOver["urxt"])
    }

    // ─── Hex address validation ───

    @Test
    fun `validateHex16 accepts boundary values`() {
        val errorsZero = validateConfig(E220Config(addr = "0x0000"))
        assertEquals(null, errorsZero["addr"])

        val errorsMax = validateConfig(E220Config(addr = "0xFFFF"))
        assertEquals(null, errorsMax["addr"])

        val errorsBroadcast = validateConfig(E220Config(dest = "0xFFFF"))
        assertEquals(null, errorsBroadcast["dest"])
    }

    @Test
    fun `validateHex16 accepts without 0x prefix`() {
        val errors = validateConfig(E220Config(addr = "ABCD"))
        assertEquals(null, errors["addr"])
    }

    @Test
    fun `validateHex16 rejects values exceeding 16-bit`() {
        val errors = validateConfig(E220Config(addr = "0x10000"))
        assertEquals("Enter a valid 16-bit hexadecimal address", errors["addr"])
    }

    // ─── Combined validation ───

    @Test
    fun `validateConfig returns multiple errors for multiple invalid fields`() {
        val errors = validateConfig(
            E220Config(
                freq = "999.000",
                addr = "not-hex",
                wifiEnabled = "1",
                wifiMode = "AP",
                wifiApPassword = "short",
                cryptH = "999",
                lbrTimeout = "100000",
                urxt = "0"
            )
        )

        assertTrue(errors.size >= 5)
        assertEquals("Select a channel frequency from the manual", errors["freq"])
        assertEquals("Enter a valid 16-bit hexadecimal address", errors["addr"])
        assertEquals("AP password must be at least 8 characters", errors["wifi_ap_password"])
        assertEquals("Crypto high must be 0-255", errors["crypt_h"])
        assertEquals("LBT timeout must be between 0 and 65535 ms", errors["lbr_timeout"])
        assertEquals("URXT must be between 1 and 255 byte times", errors["urxt"])
    }

    @Test
    fun `validateConfig returns empty errors for valid config`() {
        val cfg = E220Config(
            freq = "868.125",
            addr = "0x0000",
            dest = "0xFFFF",
            txpower = "21",
            baud = "9600",
            airrate = "2",
            subpkt = "0",
            parity = "0",
            txmode = "0",
            rssiNoise = "0",
            rssiByte = "0",
            lbt = "0",
            lbrRssi = "-55",
            lbrTimeout = "2000",
            urxt = "3",
            worCycle = "3",
            cryptH = "0",
            cryptL = "0",
            saveType = "1",
            wifiEnabled = "0",
            wifiMode = "AP",
            wifiApSsid = "",
            wifiApPassword = "",
            wifiStaSsid = "",
            wifiStaPassword = ""
        )
        val errors = validateConfig(cfg)
        assertEquals("unexpected errors: $errors", 0, errors.size)
    }

    // ─── ConfigValidationException ───

    @Test
    fun `requireValidConfig throws ConfigValidationException for invalid config`() {
        try {
            requireValidConfig(E220Config(freq = "999.000"))
            fail("Expected ConfigValidationException")
        } catch (e: ConfigValidationException) {
            assertEquals("Select a channel frequency from the manual", e.fieldErrors["freq"])
        }
    }

    @Test
    fun `requireValidConfig does not throw for valid config`() {
        requireValidConfig(E220Config()) // all defaults should be valid
    }

    // ─── formatConfigValidationSummary ───

    @Test
    fun `formatConfigValidationSummary joins field errors`() {
        val summary = formatConfigValidationSummary(
            linkedMapOf(
                "freq" to "Select a channel frequency",
                "addr" to "Enter a valid 16-bit hexadecimal address"
            )
        )
        assertTrue(summary.contains("freq: Select a channel frequency"))
        assertTrue(summary.contains("addr: Enter a valid 16-bit hexadecimal address"))
        assertTrue(summary.contains("; "))
    }

    @Test
    fun `formatConfigValidationSummary returns empty for no errors`() {
        assertEquals("", formatConfigValidationSummary(emptyMap()))
    }
}
