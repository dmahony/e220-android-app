package com.dmahony.e220chat.ble

import java.io.ByteArrayOutputStream
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
    val username: String,
    val channel: Int = 0,
    val txpower: Int = 0,
    val baud: Int = 0,
    val parity: Int = 0,
    val airrate: Int = 0,
    val txmode: Int = 0,
    val lbt: Int = 0,
    val subpkt: Int = 0,
    val rssiNoise: Int = 0,
    val rssiByte: Int = 0,
    val urxt: Int = 0,
    val worCycle: Int = 0,
    val cryptH: Int = 0,
    val cryptL: Int = 0,
    val saveType: Int = 0,
    val addr: Int = 0,
    val dest: Int = 0,
    val wifiEnabled: Int = 0,
    val wifiMode: Int = 0,
    val wifiApSsid: String = "",
    val wifiApPassword: String = "",
    val wifiStaSsid: String = "",
    val wifiStaPassword: String = ""
) {
    fun toPayload(): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeByte(value: Int) {
            out.write(value and 0xFF)
        }
        fun writeU16(value: Int) {
            writeByte(value ushr 8)
            writeByte(value)
        }
        fun writeU24(value: Int) {
            writeByte(value ushr 16)
            writeByte(value ushr 8)
            writeByte(value)
        }
        fun writeString(value: String, maxLen: Int) {
            val bytes = value.toByteArray(Charsets.UTF_8).take(maxLen).toByteArray()
            writeByte(bytes.size)
            out.write(bytes)
        }

        writeU16(ackTimeoutMs)
        writeByte(maxRetries)
        writeU16(radioTxIntervalMs)
        writeU16(statusIntervalMs)
        writeU16(profileIntervalSec)
        writeU24(userId24)

        writeByte(channel)
        writeByte(txpower)
        writeByte(baud)
        writeByte(parity)
        writeByte(airrate)
        writeByte(txmode)
        writeByte(lbt)
        writeByte(subpkt)
        writeByte(rssiNoise)
        writeByte(rssiByte)
        writeByte(urxt)
        writeByte(worCycle)
        writeByte(cryptH)
        writeByte(cryptL)
        writeByte(saveType)
        writeU16(addr)
        writeU16(dest)
        writeByte(wifiEnabled)
        writeByte(wifiMode)

        writeString(username, 19)
        writeString(wifiApSsid, 31)
        writeString(wifiApPassword, 31)
        writeString(wifiStaSsid, 31)
        writeString(wifiStaPassword, 31)
        return out.toByteArray()
    }

    companion object {
        private const val LEGACY_PAYLOAD_SIZE = 13
        private const val EXTENDED_PAYLOAD_SIZE = 33

        fun fromPayload(bytes: ByteArray): BleConfig {
            require(bytes.size >= LEGACY_PAYLOAD_SIZE) { "CONFIG payload too short" }
            return if (bytes.size >= EXTENDED_PAYLOAD_SIZE) {
                parseExtendedPayload(bytes)
            } else {
                parseLegacyPayload(bytes)
            }
        }

        private fun parseLegacyPayload(bytes: ByteArray): BleConfig {
            var i = 0
            val ackTimeoutMs = ((bytes[i++].toInt() and 0xFF) shl 8) or (bytes[i++].toInt() and 0xFF)
            val maxRetries = bytes[i++].toInt() and 0xFF
            val radioTxIntervalMs = ((bytes[i++].toInt() and 0xFF) shl 8) or (bytes[i++].toInt() and 0xFF)
            val statusIntervalMs = ((bytes[i++].toInt() and 0xFF) shl 8) or (bytes[i++].toInt() and 0xFF)
            val profileIntervalSec = ((bytes[i++].toInt() and 0xFF) shl 8) or (bytes[i++].toInt() and 0xFF)
            val userId24 = ((bytes[i++].toInt() and 0xFF) shl 16) or ((bytes[i++].toInt() and 0xFF) shl 8) or (bytes[i++].toInt() and 0xFF)
            val nameLen = bytes[i++].toInt() and 0xFF
            val username = if (i + nameLen <= bytes.size) {
                bytes.copyOfRange(i, i + nameLen).toString(Charsets.UTF_8)
            } else {
                ""
            }
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

        private fun parseExtendedPayload(bytes: ByteArray): BleConfig {
            var i = 0
            fun requireRemaining(count: Int) {
                require(i + count <= bytes.size) { "CONFIG payload truncated" }
            }
            fun readU8(): Int {
                requireRemaining(1)
                return bytes[i++].toInt() and 0xFF
            }
            fun readU16(): Int {
                return (readU8() shl 8) or readU8()
            }
            fun readU24(): Int {
                return (readU8() shl 16) or (readU8() shl 8) or readU8()
            }
            fun readString(maxLen: Int): String {
                val len = readU8().coerceAtMost(maxLen)
                requireRemaining(len)
                val value = bytes.copyOfRange(i, i + len).toString(Charsets.UTF_8)
                i += len
                return value
            }

            val ackTimeoutMs = readU16()
            val maxRetries = readU8()
            val radioTxIntervalMs = readU16()
            val statusIntervalMs = readU16()
            val profileIntervalSec = readU16()
            val userId24 = readU24()
            val channel = readU8()
            val txpower = readU8()
            val baud = readU8()
            val parity = readU8()
            val airrate = readU8()
            val txmode = readU8()
            val lbt = readU8()
            val subpkt = readU8()
            val rssiNoise = readU8()
            val rssiByte = readU8()
            val urxt = readU8()
            val worCycle = readU8()
            val cryptH = readU8()
            val cryptL = readU8()
            val saveType = readU8()
            val addr = readU16()
            val dest = readU16()
            val wifiEnabled = readU8()
            val wifiMode = readU8()
            val username = readString(19)
            val wifiApSsid = readString(31)
            val wifiApPassword = readString(31)
            val wifiStaSsid = readString(31)
            val wifiStaPassword = readString(31)

            return BleConfig(
                ackTimeoutMs = ackTimeoutMs,
                maxRetries = maxRetries,
                radioTxIntervalMs = radioTxIntervalMs,
                statusIntervalMs = statusIntervalMs,
                profileIntervalSec = profileIntervalSec,
                userId24 = userId24,
                username = username,
                channel = channel,
                txpower = txpower,
                baud = baud,
                parity = parity,
                airrate = airrate,
                txmode = txmode,
                lbt = lbt,
                subpkt = subpkt,
                rssiNoise = rssiNoise,
                rssiByte = rssiByte,
                urxt = urxt,
                worCycle = worCycle,
                cryptH = cryptH,
                cryptL = cryptL,
                saveType = saveType,
                addr = addr,
                dest = dest,
                wifiEnabled = wifiEnabled,
                wifiMode = wifiMode,
                wifiApSsid = wifiApSsid,
                wifiApPassword = wifiApPassword,
                wifiStaSsid = wifiStaSsid,
                wifiStaPassword = wifiStaPassword
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
