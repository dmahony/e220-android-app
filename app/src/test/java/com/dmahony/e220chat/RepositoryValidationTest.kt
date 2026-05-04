package com.dmahony.e220chat

import org.junit.Assert.assertEquals
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
    fun `freqStringToChannelOrFallback falls back instead of clamping out of range frequencies`() {
        assertEquals(12, freqStringToChannelOrFallback("999.000", 12))
        assertEquals(12, freqStringToChannelOrFallback("800.000", 12))
    }
}
