package com.dmahony.e220chat

import android.os.Build

internal fun isHardenedBleSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.S

internal fun hardenedBleUnsupportedMessage(sdkInt: Int = Build.VERSION.SDK_INT): String = when {
    sdkInt == Build.VERSION_CODES.R -> {
        "Android 11 / API 30 cannot reliably use the hardened E220 BLE service; upgrade to Android 12 or newer."
    }
    else -> "Hardened E220 BLE requires Android 12 or newer."
}
