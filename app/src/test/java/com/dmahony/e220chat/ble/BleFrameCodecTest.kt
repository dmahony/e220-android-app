package com.dmahony.e220chat.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BleFrameCodecTest {
    @Test
    fun `decodeStream reassembles a frame split across mtu-sized chunks`() {
        val codec = BleFrameCodec()
        val payload = ByteArray(64) { index -> (index and 0xFF).toByte() }
        val frame = BleFrame(MsgType.CONFIG, seq = 0x42u, payload = payload)
        val encoded = codec.encode(frame)

        val first = encoded.copyOfRange(0, 20)
        val second = encoded.copyOfRange(20, 40)
        val third = encoded.copyOfRange(40, encoded.size)

        assertEquals(0, codec.decodeStream(first).size)
        assertEquals(0, codec.decodeStream(second).size)

        val decoded = codec.decodeStream(third)

        assertEquals(1, decoded.size)
        assertEquals(MsgType.CONFIG, decoded.single().type)
        assertEquals(0x42, decoded.single().seq.toInt())
        assertArrayEquals(payload, decoded.single().payload)
    }
}
