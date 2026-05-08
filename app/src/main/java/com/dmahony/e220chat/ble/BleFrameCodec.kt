package com.dmahony.e220chat.ble

class BleFrameCodec {
    fun encode(frame: BleFrame): ByteArray {
        require(frame.payload.size <= 255) { "Payload too large" }
        val out = ByteArray(5 + frame.payload.size)
        out[0] = FRAME_START
        out[1] = frame.type.code.toByte()
        out[2] = frame.seq.toByte()
        out[3] = frame.payload.size.toByte()
        frame.payload.copyInto(out, destinationOffset = 4)
        out[4 + frame.payload.size] = crc(out, 0, 4 + frame.payload.size)
        return out
    }

    fun decodeStream(chunk: ByteArray): List<BleFrame> {
        val out = mutableListOf<BleFrame>()
        for (b in chunk) {
            val f = parser.push(b)
            if (f != null) out += f
        }
        return out
    }

    private val parser = Parser()

    private class Parser {
        private enum class State { WAIT_START, TYPE, SEQ, LEN, PAYLOAD, CRC }
        private var state = State.WAIT_START
        private var type: MsgType? = null
        private var seq: UByte = 0u
        private var len = 0
        private var idx = 0
        private val payload = ByteArray(255)

        fun push(b: Byte): BleFrame? {
            when (state) {
                State.WAIT_START -> if (b == FRAME_START) state = State.TYPE
                State.TYPE -> {
                    val t = MsgType.from((b.toInt() and 0xFF).toUByte())
                    if (t == null) {
                        reset()
                    } else {
                        type = t
                        state = State.SEQ
                    }
                }
                State.SEQ -> {
                    seq = (b.toInt() and 0xFF).toUByte()
                    state = State.LEN
                }
                State.LEN -> {
                    len = b.toInt() and 0xFF
                    idx = 0
                    state = if (len == 0) State.CRC else State.PAYLOAD
                }
                State.PAYLOAD -> {
                    payload[idx++] = b
                    if (idx >= len) state = State.CRC
                }
                State.CRC -> {
                    val t = type ?: run { reset(); return null }
                    val raw = ByteArray(4 + len)
                    raw[0] = FRAME_START
                    raw[1] = t.code.toByte()
                    raw[2] = seq.toByte()
                    raw[3] = len.toByte()
                    if (len > 0) payload.copyInto(raw, endIndex = len, destinationOffset = 4)
                    val expected = crc(raw, 0, raw.size)
                    val got = b
                    val frame = if (expected == got) {
                        BleFrame(t, seq, raw.copyOfRange(4, 4 + len))
                    } else {
                        null
                    }
                    reset()
                    return frame
                }
            }
            return null
        }

        private fun reset() {
            state = State.WAIT_START
            type = null
            seq = 0u
            len = 0
            idx = 0
        }
    }

    companion object {
        fun crc(data: ByteArray, offset: Int, len: Int): Byte {
            var c = 0
            for (i in offset until (offset + len)) c = c xor (data[i].toInt() and 0xFF)
            return c.toByte()
        }
    }
}
