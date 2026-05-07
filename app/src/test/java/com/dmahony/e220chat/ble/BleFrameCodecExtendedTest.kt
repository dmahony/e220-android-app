package com.dmahony.e220chat.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFrameCodecExtendedTest {
    private val codec = BleFrameCodec()

    // ─── CRC / corruption detection ───

    @Test
    fun `decodeStream detects CRC corruption and drops the frame`() {
        val frame = BleFrame(MsgType.TEXT, seq = 1u, payload = "hello".toByteArray())
        val encoded = codec.encode(frame)
        // Corrupt the payload byte
        encoded[encoded.size - 2] = (encoded[encoded.size - 2] + 1).toByte()
        val decoded = codec.decodeStream(encoded)
        assertEquals("corrupted frame should be dropped", 0, decoded.size)
    }

    @Test
    fun `decodeStream detects corrupted type-byte and resets parser`() {
        val frame = BleFrame(MsgType.TEXT, seq = 1u, payload = byteArrayOf(1))
        val encoded = codec.encode(frame)
        // Corrupt the frame-start byte in the middle
        val corrupted = ByteArray(encoded.size) { i ->
            if (i == 1) 0xFF.toByte() else encoded[i]  // invalid type byte
        }
        val decoded = codec.decodeStream(corrupted)
        assertEquals(0, decoded.size)
    }

    @Test
    fun `decodeStream correctly handles FRAME_START byte in payload`() {
        val payload = byteArrayOf(FRAME_START, 0x01, 0x02)
        val frame = BleFrame(MsgType.TEXT, seq = 3u, payload = payload)
        val encoded = codec.encode(frame)
        val decoded = codec.decodeStream(encoded)
        assertEquals(1, decoded.size)
        assertArrayEquals(payload, decoded[0].payload)
    }

    // ─── Multi-frame streams ───

    @Test
    fun `decodeStream parses two complete frames concatenated in a single buffer`() {
        val frame1 = BleFrame(MsgType.TEXT, seq = 1u, payload = byteArrayOf(10, 20))
        val frame2 = BleFrame(MsgType.ACK, seq = 2u, payload = byteArrayOf())
        val combined = codec.encode(frame1) + codec.encode(frame2)
        val decoded = codec.decodeStream(combined)
        assertEquals(2, decoded.size)
        assertEquals(MsgType.TEXT, decoded[0].type)
        assertEquals(1, decoded[0].seq.toInt())
        assertEquals(MsgType.ACK, decoded[1].type)
        assertEquals(2, decoded[1].seq.toInt())
    }

    @Test
    fun `decodeStream parses three frames in one buffer`() {
        val f1 = BleFrame(MsgType.CONFIG, seq = 10u, payload = byteArrayOf(1))
        val f2 = BleFrame(MsgType.PROFILE, seq = 11u, payload = byteArrayOf(2, 3))
        val f3 = BleFrame(MsgType.ERROR, seq = 12u, payload = byteArrayOf(4, 5, 6))
        val combined = codec.encode(f1) + codec.encode(f2) + codec.encode(f3)
        val decoded = codec.decodeStream(combined)
        assertEquals(3, decoded.size)
    }

    // ─── Payload edge cases ───

    @Test
    fun `encode handles zero-length payload`() {
        val frame = BleFrame(MsgType.ACK, seq = 0u, payload = byteArrayOf())
        val encoded = codec.encode(frame)
        assertEquals(5, encoded.size) // START + TYPE + SEQ + LEN(0) + CRC
        assertEquals(FRAME_START, encoded[0])
        assertEquals(MsgType.ACK.code.toByte(), encoded[1])
        assertEquals(0.toByte(), encoded[2])
        assertEquals(0.toByte(), encoded[3])
    }

    @Test
    fun `encode handles max 255-byte payload`() {
        val payload = ByteArray(255) { it.toByte() }
        val frame = BleFrame(MsgType.TEXT, seq = 42u, payload = payload)
        val encoded = codec.encode(frame)
        assertEquals(5 + 255, encoded.size)
        assertEquals(255.toByte(), encoded[3]) // length field
        val decoded = codec.decodeStream(encoded)
        assertEquals(1, decoded.size)
        assertArrayEquals(payload, decoded[0].payload)
    }

    @Test
    fun `encode rejects oversized payload`() {
        try {
            val payload = ByteArray(256) { it.toByte() }
            codec.encode(BleFrame(MsgType.TEXT, seq = 1u, payload = payload))
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Payload too large"))
        }
    }

    // ─── Resynchronization after garbage ───

    @Test
    fun `decodeStream resynchronizes after garbage bytes`() {
        val frame = BleFrame(MsgType.TEXT, seq = 1u, payload = byteArrayOf(99))
        val encoded = codec.encode(frame)
        val garbage = byteArrayOf(0x00.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val input = garbage + encoded
        val decoded = codec.decodeStream(input)
        assertEquals(1, decoded.size)
        assertEquals(99.toByte(), decoded[0].payload[0])
    }

    @Test
    fun `decodeStream returns empty for partial frame at end of stream`() {
        val frame = BleFrame(MsgType.TEXT, seq = 1u, payload = byteArrayOf(1, 2, 3, 4, 5))
        val encoded = codec.encode(frame)
        // Only give the first 4 bytes (incomplete frame)
        val partial = encoded.copyOfRange(0, 4)
        val decoded = codec.decodeStream(partial)
        assertEquals(0, decoded.size)
    }

    // ─── CRC calculation ───

    @Test
    fun `crc is consistent for known input`() {
        val data = byteArrayOf(FRAME_START, MsgType.TEXT.code.toByte(), 1, 5) + "hello".toByteArray()
        val crc1 = BleFrameCodec.crc(data, 0, data.size)
        val crc2 = BleFrameCodec.crc(data, 0, data.size)
        assertEquals(crc1, crc2)
    }

    @Test
    fun `crc changes when data changes`() {
        val data1 = byteArrayOf(FRAME_START, MsgType.TEXT.code.toByte(), 1, 3) + "abc".toByteArray()
        val data2 = byteArrayOf(FRAME_START, MsgType.TEXT.code.toByte(), 1, 3) + "abd".toByteArray()
        assertTrue(BleFrameCodec.crc(data1, 0, data1.size) != BleFrameCodec.crc(data2, 0, data2.size))
    }

    // ─── All MsgType round-trips ───

    @Test
    fun `all message types round-trip through encode and decodeStream`() {
        for (type in MsgType.entries) {
            val payload = when (type) {
                MsgType.ACK, MsgType.STATUS -> byteArrayOf()
                else -> byteArrayOf(1, 2, 3)
            }
            val frame = BleFrame(type, seq = type.code, payload = payload)
            val encoded = codec.encode(frame)
            val decoded = codec.decodeStream(encoded)
            assertEquals("type $type should round-trip", 1, decoded.size)
            assertEquals(type, decoded[0].type)
            assertEquals(type.code, decoded[0].seq)
        }
    }
}
