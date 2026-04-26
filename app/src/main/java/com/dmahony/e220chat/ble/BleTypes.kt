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
        val nameBytes = username.toByteArray(Charsets.UTF_8).take(19).toByteArray()
        val apSsidBytes = wifiApSsid.toByteArray(Charsets.UTF_8).take(31).toByteArray()
        val apPwdBytes = wifiApPassword.toByteArray(Charsets.UTF_8).take(31).toByteArray()
        val staSsidBytes = wifiStaSsid.toByteArray(Charsets.UTF_8).take(31).toByteArray()
        val staPwdBytes = wifiStaPassword.toByteArray(Charsets.UTF_8).take(31).toByteArray()

        val fixedSize = 33
        val totalSize = fixedSize + nameBytes.size + 
                       (1 + apSsidBytes.size) + (1 + apPwdBytes.size) + 
                       (1 + staSsidBytes.size) + (1 + staPwdBytes.size)
        
        val out = ByteArray(totalSize)
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
        out[i++] = channel.toByte()
        out[i++] = txpower.toByte()
        out[i++] = baud.toByte()
        out[i++] = parity.toByte()
        out[i++] = airrate.toByte()
        out[i++] = txmode.toByte()
        out[i++] = lbt.toByte()
        out[i++] = subpkt.toByte()
        out[i++] = rssiNoise.toByte()
        out[i++] = rssiByte.toByte()
        out[i++] = urxt.toByte()
        out[i++] = worCycle.toByte()
        out[i++] = cryptH.toByte()
        out[i++] = cryptL.toByte()
        out[i++] = saveType.toByte()
        out[i++] = ((addr ushr 8) and 0xFF).toByte()
        out[i++] = (addr and 0xFF).toByte()
        out[i++] = ((dest ushr 8) and 0xFF).toByte()
        out[i++] = (dest and 0xFF).toByte()
        out[i++] = wifiEnabled.toByte()
        out[i++] = wifiMode.toByte()
        out[i++] = nameBytes.size.toByte()
        nameBytes.copyInto(out, i)
        i += nameBytes.size
        
        out[i++] = apSsidBytes.size.toByte()
        apSsidBytes.copyInto(out, i)
        i += apSsidBytes.size
        
        out[i++] = apPwdBytes.size.toByte()
        apPwdBytes.copyInto(out, i)
        i += apPwdBytes.size
        
        out[i++] = staSsidBytes.size.toByte()
        staSsidBytes.copyInto(out, i)
        i += staSsidBytes.size
        
        out[i++] = staPwdBytes.size.toByte()
        staPwdBytes.copyInto(out, i)
        
        return out
    }

    companion object {
        fun fromPayload(bytes: ByteArray): BleConfig {
            require(bytes.size >= 33) { "CONFIG payload too short" }
            var i = 0
            val ackTimeoutMs = ((bytes[i++].toInt() and 0xFF) shl 8) or (bytes[i++].toInt() and 0xFF)
            val maxRetries = bytes[i++].toInt() and 0xFF
            val radioTxIntervalMs = ((bytes[i++].toInt() and 0xFF) shl 8) or (bytes[i++].toInt() and 0xFF)
            val statusIntervalMs = ((bytes[i++].toInt() and 0xFF) shl 8) or (bytes[i++].toInt() and 0xFF)
            val profileIntervalSec = ((bytes[i++].toInt() and 0xFF) shl 8) or (bytes[i++].toInt() and 0xFF)
            val userId24 = ((bytes[i++].toInt() and 0xFF) shl 16) or ((bytes[i++].toInt() and 0xFF) shl 8) or (bytes[i++].toInt() and 0xFF)
            val channel = bytes[i++].toInt() and 0xFF
            val txpower = bytes[i++].toInt() and 0xFF
            val baud = bytes[i++].toInt() and 0xFF
            val parity = bytes[i++].toInt() and 0xFF
            val airrate = bytes[i++].toInt() and 0xFF
            val txmode = bytes[i++].toInt() and 0xFF
            val lbt = bytes[i++].toInt() and 0xFF
            val subpkt = bytes[i++].toInt() and 0xFF
            val rssiNoise = bytes[i++].toInt() and 0xFF
            val rssiByte = bytes[i++].toInt() and 0xFF
            val urxt = bytes[i++].toInt() and 0xFF
            val worCycle = bytes[i++].toInt() and 0xFF
            val cryptH = bytes[i++].toInt() and 0xFF
            val cryptL = bytes[i++].toInt() and 0xFF
            val saveType = bytes[i++].toInt() and 0xFF
            val addr = ((bytes[i++].toInt() and 0xFF) shl 8) or (bytes[i++].toInt() and 0xFF)
            val dest = ((bytes[i++].toInt() and 0xFF) shl 8) or (bytes[i++].toInt() and 0xFF)
            val wifiEnabled = bytes[i++].toInt() and 0xFF
            val wifiMode = bytes[i++].toInt() and 0xFF
            
            val nameLen = bytes[i++].toInt() and 0xFF
            val username = if (i + nameLen <= bytes.size) bytes.copyOfRange(i, i + nameLen).toString(Charsets.UTF_8) else ""
            i += nameLen
            
            fun readString(): String {
                if (i >= bytes.size) return ""
                val len = bytes[i++].toInt() and 0xFF
                return if (i + len <= bytes.size) bytes.copyOfRange(i, i + len).toString(Charsets.UTF_8).also { i += len } else ""
            }
            
            val wifiApSsid = readString()
            val wifiApPassword = readString()
            val wifiStaSsid = readString()
            val wifiStaPassword = readString()
            
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
