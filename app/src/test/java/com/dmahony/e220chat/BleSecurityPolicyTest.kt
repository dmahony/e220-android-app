package com.dmahony.e220chat

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSecurityPolicyTest {
    @Test
    fun `hardened BLE support starts at Android 12`() {
        assertFalse(isHardenedBleSupported(Build.VERSION_CODES.R))
        assertTrue(isHardenedBleSupported(Build.VERSION_CODES.S))
    }

    @Test
    fun `unsupported message calls out Android 11 explicitly`() {
        val message = hardenedBleUnsupportedMessage(Build.VERSION_CODES.R)
        assertTrue(message.contains("Android 11"))
        assertTrue(message.contains("Android 12 or newer"))
    }
}
