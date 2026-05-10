package com.dmahony.e220chat

private fun formatMHz(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)

val channelOptions: List<Pair<String, String>> = (0..80).map { channel ->
    val frequency = 850.125 + channel
    "Ch $channel — ${formatMHz(frequency)} MHz" to formatMHz(frequency)
}

val txPowerOptions = listOf(
    "30 dBm" to "30",
    "27 dBm" to "27",
    "24 dBm" to "24",
    "21 dBm" to "21"
)

val baudOptions = listOf(
    "1200" to "0",
    "2400" to "1",
    "4800" to "2",
    "9600" to "3",
    "19200" to "4",
    "38400" to "5",
    "57600" to "6",
    "115200" to "7"
)

val parityOptions = listOf(
    "None" to "0",
    "Odd" to "1",
    "Even" to "2"
)

val airRateOptions = listOf(
    "2.4 Kbps" to "0",
    "2.4 Kbps" to "1",
    "2.4 Kbps" to "2",
    "4.8 Kbps" to "3",
    "9.6 Kbps" to "4",
    "19.2 Kbps" to "5",
    "38.4 Kbps" to "6",
    "62.5 Kbps" to "7"
)

val txModeOptions = listOf(
    "Transparent" to "0",
    "Fixed-point" to "1"
)

val onOffOptions = listOf(
    "Off" to "0",
    "On" to "1"
)

val wifiModeOptions = listOf(
    "AP" to "AP",
    "STA" to "STA",
    "AP + STA" to "AP_STA"
)

val packetLengthOptions = listOf(
    "200 bytes" to "0",
    "128 bytes" to "1",
    "64 bytes" to "2",
    "32 bytes" to "3"
)

val wakeTimeOptions = listOf(
    "500 ms" to "0",
    "1000 ms" to "1",
    "1500 ms" to "2",
    "2000 ms" to "3",
    "2500 ms" to "4",
    "3000 ms" to "5",
    "3500 ms" to "6",
    "4000 ms" to "7"
)
