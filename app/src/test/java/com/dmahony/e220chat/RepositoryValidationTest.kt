package com.dmahony.e220chat

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RepositoryValidationTest {
    @Test
    fun `freqStringToChannelOrFallback preserves current channel for blank or invalid values`() {
        assertEquals(12, freqStringToChannelOrFallback("", 12))
        assertEquals(12, freqStringToChannelOrFallback("not-a-number", 12))
        assertEquals(12, freqStringToChannelOrFallback("NaN", 12))
    }

    @Test
    fun `freqStringToChannelOrFallback trims whitespace and maps valid in-range frequencies`() {
        assertEquals(65, freqStringToChannelOrFallback(" 915.125 ", 12))
        assertEquals(80, freqStringToChannelOrFallback("930.125", 12))
    }

    @Test
    fun `freqStringToChannelOrFallback clamps invalid fallback channels into range`() {
        assertEquals(80, freqStringToChannelOrFallback("invalid", 999))
        assertEquals(0, freqStringToChannelOrFallback("invalid", -4))
    }

    @Test
    fun `validateConfig flags invalid fields with field specific messages`() {
        val errors = validateConfig(
            E220Config(
                freq = "999.000",
                addr = "not-hex",
                wifiEnabled = "1",
                wifiMode = "AP",
                wifiApPassword = "short",
                lbrTimeout = "70000"
            )
        )

        assertEquals("Select a channel frequency from the manual", errors["freq"])
        assertEquals("Enter a valid 16-bit hexadecimal address", errors["addr"])
        assertEquals("AP password must be at least 8 characters", errors["wifi_ap_password"])
        assertEquals("LBT timeout must be between 0 and 65535 ms", errors["lbr_timeout"])
    }

    @Test
    fun `buildConfigRequest rejects invalid config instead of silently clamping`() {
        try {
            E220Protocol.buildConfigRequest(E220Config(freq = "999.000"))
            fail("Expected ConfigValidationException")
        } catch (e: ConfigValidationException) {
            assertEquals("Select a channel frequency from the manual", e.fieldErrors["freq"])
        }
    }
}
