package com.dmahony.e220chat.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val FRAME_START: Byte = 0xAA.toByte()

enum class MsgType(val code: UByte) {
    TEXT(0x01u),
    ACK(0x02u),
    STATUS(0x03u),
    CONFIG(0x04u),
    PROFILE(0x05u),
    ERROR(0x06u),
    WHOIS(0x07u);

    companion object {
        fun from(code: UByte): MsgType? = entries.firstOrNull { it.code == code }
    }
}

enum class FlowState(val code: UByte) {
    READY(0u),
    BUSY(1u),
    TX_IN_PROGRESS(2u),
    TX_DONE(3u),
    TX_FAILED(4u);

    companion object {
        fun from(code: UByte): FlowState = entries.firstOrNull { it.code == code } ?: READY
    }
}

data class BleFrame(
    val type: MsgType,
    val seq: UByte,
    val payload: ByteArray = byteArrayOf(),
    val requireAck: Boolean = type != MsgType.ACK && type != MsgType.STATUS
)

data class BleConfig(
    val ackTimeoutMs: Int = 180,
    val maxRetries: Int = 4,
    val radioTxIntervalMs: Int = 90,
    val statusIntervalMs: Int = 1000,
    val profileIntervalSec: Int = 900,
    val userId24: Int,
    val username: String
) {
    fun toPayload(): ByteArray {
        val nameBytes = username.toByteArray(Charsets.UTF_8).take(19).toByteArray()
        val out = ByteArray(13 + nameBytes.size)
        var i = 0
        out[i++] = ((ackTimeoutMs ushr 8) and 0xFF).toByte()
        out[i++] = (ackTimeoutMs and 0xFF).toByte()
        out[i++] = maxRetries.toByte()
        out[i++] = ((radioTxIntervalMs ushr 8) and 0xFF).toByte()
        out[i++] = (radioTxIntervalMs and 0xFF).toByte()
        out[i++] = ((statusIntervalMs ushr 8) and 0xFF).toByte()
        out[i++] = (statusIntervalMs and 0xFF).toByte()
        out[i++] = ((profileIntervalSec ushr 8) and 0xFF).toByte()
        out[i++] = (profileIntervalSec and 0xFF).toByte()
        out[i++] = ((userId24 ushr 16) and 0xFF).toByte()
        out[i++] = ((userId24 ushr 8) and 0xFF).toByte()
        out[i++] = (userId24 and 0xFF).toByte()
        out[i++] = nameBytes.size.toByte()
        nameBytes.copyInto(out, i)
        return out
    }

    companion object {
        fun fromPayload(bytes: ByteArray): BleConfig {
            require(bytes.size >= 13) { "CONFIG payload too short" }
            val ackTimeoutMs = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            val maxRetries = bytes[2].toInt() and 0xFF
            val radioTxIntervalMs = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
            val statusIntervalMs = ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
            val profileIntervalSec = ((bytes[7].toInt() and 0xFF) shl 8) or (bytes[8].toInt() and 0xFF)
            val userId24 = ((bytes[9].toInt() and 0xFF) shl 16) or ((bytes[10].toInt() and 0xFF) shl 8) or (bytes[11].toInt() and 0xFF)
            val nameLen = (bytes[12].toInt() and 0xFF).coerceAtMost(bytes.size - 13)
            val username = if (nameLen > 0) bytes.copyOfRange(13, 13 + nameLen).toString(Charsets.UTF_8) else ""
            return BleConfig(
                ackTimeoutMs = ackTimeoutMs,
                maxRetries = maxRetries,
                radioTxIntervalMs = radioTxIntervalMs,
                statusIntervalMs = statusIntervalMs,
                profileIntervalSec = profileIntervalSec,
                userId24 = userId24,
                username = username
            )
        }
    }
}

data class StatusTelemetry(
    val flowState: FlowState,
    val batteryMv: Int,
    val lastRssi: Int,
    val qBleRx: Int,
    val qRadioTx: Int,
    val qRadioRx: Int,
    val qBleTx: Int,
    val uptimeSec: Long,
    val fwMajor: Int,
    val fwMinor: Int,
    val fwPatch: Int,
    val deviceId24: Int
) {
    companion object {
        fun fromPayload(payload: ByteArray): StatusTelemetry {
            require(payload.size >= 18) { "STATUS payload too short" }
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val fs = FlowState.from((buf.get().toInt() and 0xFF).toUByte())
            val batt = buf.short.toInt() and 0xFFFF
            val rssi = buf.get().toInt()
            val qBleRx = buf.get().toInt() and 0xFF
            val qRadioTx = buf.get().toInt() and 0xFF
            val qRadioRx = buf.get().toInt() and 0xFF
            val qBleTx = buf.get().toInt() and 0xFF
            val uptime = buf.int.toLong() and 0xFFFFFFFFL
            val fwMaj = buf.get().toInt() and 0xFF
            val fwMin = buf.get().toInt() and 0xFF
            val fwPat = buf.get().toInt() and 0xFF
            val id0 = buf.get().toInt() and 0xFF
            val id1 = buf.get().toInt() and 0xFF
            val id2 = buf.get().toInt() and 0xFF
            val id = (id0 shl 16) or (id1 shl 8) or id2
            return StatusTelemetry(fs, batt, rssi, qBleRx, qRadioTx, qRadioRx, qBleTx, uptime, fwMaj, fwMin, fwPat, id)
        }
    }
}

data class ProfilePacket(val userId24: Int, val username: String)

data class TextPacket(val userId24: Int, val text: String)
