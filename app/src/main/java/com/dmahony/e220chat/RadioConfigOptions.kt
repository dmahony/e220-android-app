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
    "1200 (0)" to "0",
    "2400 (1)" to "1",
    "4800 (2)" to "2",
    "9600 (3)" to "3",
    "19200 (4)" to "4",
    "38400 (5)" to "5",
    "57600 (6)" to "6",
    "115200 (7)" to "7"
)

val parityOptions = listOf(
    "None (0)" to "0",
    "Odd (1)" to "1",
    "Even (2)" to "2"
)

val airRateOptions = listOf(
    "2.4 Kbps (0)" to "0",
    "2.4 Kbps (1)" to "1",
    "2.4 Kbps (2)" to "2",
    "4.8 Kbps (3)" to "3",
    "9.6 Kbps (4)" to "4",
    "19.2 Kbps (5)" to "5",
    "38.4 Kbps (6)" to "6",
    "62.5 Kbps (7)" to "7"
)

val txModeOptions = listOf(
    "Transparent (0)" to "0",
    "Fixed-point (1)" to "1"
)

val onOffOptions = listOf(
    "Off (0)" to "0",
    "On (1)" to "1"
)

val packetLengthOptions = listOf(
    "200 bytes (0)" to "0",
    "128 bytes (1)" to "1",
    "64 bytes (2)" to "2",
    "32 bytes (3)" to "3"
)

val wakeTimeOptions = listOf(
    "500 ms (0)" to "0",
    "1000 ms (1)" to "1",
    "1500 ms (2)" to "2",
    "2000 ms (3)" to "3",
    "2500 ms (4)" to "4",
    "3000 ms (5)" to "5",
    "3500 ms (6)" to "6",
    "4000 ms (7)" to "7"
)
