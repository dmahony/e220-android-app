package com.dmahony.e220chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BinaryChatTextTest {
    @Test
    fun `decodeBinaryChatText returns text without rssi when disabled`() {
        val payload = byteArrayOf(
            0x00,
            0x12,
            0x34,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x01,
            'h'.code.toByte(),
            'i'.code.toByte()
        )

        val parsed = decodeBinaryChatText(payload, rssiEnabled = false)

        assertEquals(0x001234, parsed?.senderUserId24)
        assertEquals("hi", parsed?.text)
        assertNull(parsed?.rssi)
    }

    @Test
    fun `decodeBinaryChatText strips rssi byte when enabled`() {
        val payload = byteArrayOf(
            0x00,
            0xAB.toByte(),
            0xCD.toByte(),
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x02,
            'h'.code.toByte(),
            'e'.code.toByte(),
            'l'.code.toByte(),
            'l'.code.toByte(),
            'o'.code.toByte(),
            0xB8.toByte()
        )

        val parsed = decodeBinaryChatText(payload, rssiEnabled = true)

        assertEquals(0x00ABCD, parsed?.senderUserId24)
        assertEquals("hello", parsed?.text)
        assertEquals(-72, parsed?.rssi)
    }
}
