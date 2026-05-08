package com.dmahony.e220chat

import com.dmahony.e220chat.ble.BleConfig
import java.util.Locale
import kotlin.math.roundToInt

internal fun freqStringToChannelOrFallback(freq: String, fallbackChannel: Int): Int {
    val parsed = freq.trim().toDoubleOrNull()
    if (parsed == null || !parsed.isFinite()) return fallbackChannel.coerceIn(0, 80)
    val channel = (parsed - 850.125).roundToInt()
    return if (channel in 0..80) channel else fallbackChannel.coerceIn(0, 80)
}

internal object E220ConfigMapper {
    /** Convert a baud rate (1200-115200) to E220 register index (0-7).
     *  Values 0-7 pass through as-is (already register indices).
     *  Values >7 are treated as actual baud rates and mapped to their register index. */
    private fun baudToReg(baud: Int): Int = when (baud) {
        in 0..7 -> baud  // already a register index
        1200 -> 0; 2400 -> 1; 4800 -> 2; 9600 -> 3
        19200 -> 4; 38400 -> 5; 57600 -> 6; 115200 -> 7
        else -> 3  // default 9600 = reg 3
    }

    fun defaultBinaryConfig(address: String?): BleConfig {
        val clean = address.orEmpty().replace(":", "")
        val suffix = if (clean.length >= 6) clean.takeLast(6) else "000001"
        val id = suffix.toIntOrNull(16)?.coerceIn(1, 0xFFFFFF) ?: 1
        val addr16 = (id and 0xFFFF).let { if (it == 0) 1 else it }
        val user = "u${suffix.uppercase()}".take(19)
        return BleConfig(
            ackTimeoutMs = 180,
            maxRetries = 4,
            radioTxIntervalMs = 90,
            statusIntervalMs = 1000,
            profileIntervalSec = 900,
            userId24 = id,
            username = user,
            channel = 0,
            txpower = 21,
            baud = 3,  // register index for 9600 baud
            parity = 0,
            airrate = 2,
            txmode = 0,
            lbt = 0,
            subpkt = 0,
            rssiNoise = 0,
            rssiByte = 0,
            urxt = 3,
            worCycle = 3,
            cryptH = 0,
            cryptL = 0,
            saveType = 1,
            addr = 0x0000,
            dest = 0xFFFF
        )
    }

    fun toLegacy(cfg: BleConfig): E220Config {
        return E220Config(
            freq = channelToFreqString(cfg.channel),
            txpower = cfg.txpower.toString(),
            baud = cfg.baud.toString(),
            parity = cfg.parity.toString(),
            airrate = cfg.airrate.toString(),
            txmode = cfg.txmode.toString(),
            lbt = cfg.lbt.toString(),
            subpkt = cfg.subpkt.toString(),
            rssiNoise = cfg.rssiNoise.toString(),
            rssiByte = cfg.rssiByte.toString(),
            urxt = cfg.maxRetries.toString(),
            worCycle = cfg.worCycle.toString(),
            cryptH = cfg.cryptH.toString(),
            cryptL = cfg.cryptL.toString(),
            lbrTimeout = cfg.ackTimeoutMs.toString(),
            lbrRssi = cfg.statusIntervalMs.toString(),
            saveType = cfg.profileIntervalSec.toString(),
            addr = "0x${(cfg.addr and 0xFFFF).toString(16).padStart(4, '0').uppercase()}",
            dest = "0x${cfg.dest.toString(16).padStart(4, '0').uppercase()}",
            wifiEnabled = cfg.wifiEnabled.toString(),
            wifiMode = when (cfg.wifiMode) {
                1 -> "STA"
                2 -> "AP_STA"
                else -> "AP"
            },
            wifiApSsid = cfg.wifiApSsid,
            wifiApPassword = cfg.wifiApPassword,
            wifiStaSsid = cfg.wifiStaSsid,
            wifiStaPassword = cfg.wifiStaPassword
        )
    }

    fun toBinary(config: E220Config, current: BleConfig): BleConfig {
        requireValidConfig(config)
        val userId = parseHex24(config.addr, current.userId24)
        val username = (config.wifiApSsid.ifBlank { current.username }).take(19)
        return BleConfig(
            ackTimeoutMs = config.lbrTimeout.toIntOrNull()?.coerceIn(60, 2000) ?: current.ackTimeoutMs,
            maxRetries = config.urxt.toIntOrNull()?.coerceIn(1, 10) ?: current.maxRetries,
            radioTxIntervalMs = current.radioTxIntervalMs,
            statusIntervalMs = config.lbrRssi.toIntOrNull()?.coerceIn(200, 5000) ?: current.statusIntervalMs,
            profileIntervalSec = config.saveType.toIntOrNull()?.coerceIn(60, 3600) ?: current.profileIntervalSec,
            userId24 = userId,
            username = username,
            channel = freqStringToChannelOrFallback(config.freq, current.channel),
            txpower = config.txpower.toIntOrNull() ?: current.txpower,
            baud = baudToReg(config.baud.toIntOrNull() ?: current.baud),
            parity = config.parity.toIntOrNull() ?: current.parity,
            airrate = config.airrate.toIntOrNull() ?: current.airrate,
            txmode = config.txmode.toIntOrNull() ?: current.txmode,
            lbt = config.lbt.toIntOrNull() ?: current.lbt,
            subpkt = config.subpkt.toIntOrNull() ?: current.subpkt,
            rssiNoise = config.rssiNoise.toIntOrNull() ?: current.rssiNoise,
            rssiByte = config.rssiByte.toIntOrNull() ?: current.rssiByte,
            urxt = config.urxt.toIntOrNull() ?: current.urxt,
            worCycle = config.worCycle.toIntOrNull() ?: current.worCycle,
            cryptH = config.cryptH.toIntOrNull() ?: current.cryptH,
            cryptL = config.cryptL.toIntOrNull() ?: current.cryptL,
            saveType = config.saveType.toIntOrNull() ?: current.saveType,
            addr = parseHex16(config.addr, current.addr),
            dest = parseHex16(config.dest, current.dest),
            wifiEnabled = config.wifiEnabled.toIntOrNull() ?: current.wifiEnabled,
            wifiMode = config.wifiMode.toIntOrNull() ?: current.wifiMode,
            wifiApSsid = config.wifiApSsid,
            wifiApPassword = config.wifiApPassword,
            wifiStaSsid = config.wifiStaSsid,
            wifiStaPassword = config.wifiStaPassword
        )
    }

    private fun channelToFreqString(channel: Int): String = String.format(Locale.US, "%.3f", 850.125 + channel.coerceIn(0, 80))

    private fun parseHex16(value: String, fallback: Int): Int =
        value.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.coerceIn(0, 0xFFFF) ?: fallback

    private fun parseHex24(value: String, fallback: Int): Int =
        value.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.coerceIn(1, 0xFFFFFF) ?: fallback
}
