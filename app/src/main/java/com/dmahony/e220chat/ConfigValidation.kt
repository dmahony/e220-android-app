package com.dmahony.e220chat


internal class ConfigValidationException(
    val fieldErrors: Map<String, String>
) : IllegalArgumentException(formatConfigValidationSummary(fieldErrors))

internal fun validateConfig(config: E220Config): Map<String, String> {
    val errors = linkedMapOf<String, String>()

    validateChoice(errors, "freq", config.freq, channelOptions.mapTo(linkedSetOf()) { it.second }, "Select a channel frequency from the manual")
    validateChoice(errors, "txpower", config.txpower, txPowerOptions.mapTo(linkedSetOf()) { it.second }.apply {
        addAll(listOf("30", "27", "24", "21"))
    }, "Select a supported TX power")
    val baudValues = baudOptions.mapTo(linkedSetOf()) { it.second }.apply {
        addAll(listOf("1200", "2400", "4800", "9600", "19200", "38400", "57600", "115200"))
    }
    validateChoice(errors, "baud", config.baud, baudValues, "Select a supported UART baud rate")
    validateChoice(errors, "airrate", config.airrate, airRateOptions.mapTo(linkedSetOf()) { it.second }, "Select a supported air rate")
    validateChoice(errors, "subpkt", config.subpkt, packetLengthOptions.mapTo(linkedSetOf()) { it.second }, "Select a supported packet length")
    validateChoice(errors, "parity", config.parity, parityOptions.mapTo(linkedSetOf()) { it.second }, "Select a supported parity mode")
    validateChoice(errors, "txmode", config.txmode, txModeOptions.mapTo(linkedSetOf()) { it.second }, "Select a supported transmission mode")
    validateChoice(errors, "rssi_noise", config.rssiNoise, onOffOptions.mapTo(linkedSetOf()) { it.second }, "Choose On or Off")
    validateChoice(errors, "rssi_byte", config.rssiByte, onOffOptions.mapTo(linkedSetOf()) { it.second }, "Choose On or Off")
    validateChoice(errors, "lbt", config.lbt, onOffOptions.mapTo(linkedSetOf()) { it.second }, "Choose On or Off")
    validateChoice(errors, "wor_cycle", config.worCycle, wakeTimeOptions.mapTo(linkedSetOf()) { it.second }, "Select a supported WOR cycle")
    validateChoice(errors, "wifi_enabled", config.wifiEnabled, onOffOptions.mapTo(linkedSetOf()) { it.second }, "Choose On or Off")
    validateChoice(errors, "wifi_mode", config.wifiMode, wifiModeOptions.mapTo(linkedSetOf()) { it.second }, "Select AP, STA, or AP + STA")

    validateHex16(errors, "addr", config.addr, allowBroadcast = true)
    validateHex16(errors, "dest", config.dest, allowBroadcast = true)
    validateIntRange(errors, "crypt_h", config.cryptH, 0, 255, "Crypto high must be 0-255")
    validateIntRange(errors, "crypt_l", config.cryptL, 0, 255, "Crypto low must be 0-255")
    validateIntRange(errors, "lbr_rssi", config.lbrRssi, -128, 0, "LBT RSSI must be between -128 and 0 dBm")
    validateIntRange(errors, "lbr_timeout", config.lbrTimeout, 0, 65535, "LBT timeout must be between 0 and 65535 ms")
    validateIntRange(errors, "urxt", config.urxt, 1, 255, "URXT must be between 1 and 255 byte times")
    validateOptionalInt(errors, "savetype", config.saveType, "Save type must be an integer")

    validateSsid(errors, "wifi_ap_ssid", config.wifiApSsid, "AP SSID")
    validateSsid(errors, "wifi_sta_ssid", config.wifiStaSsid, "STA SSID")
    validateWifiNetworkRequirements(errors, config)
    validatePassword(errors, config)

    return errors
}

internal fun requireValidConfig(config: E220Config) {
    val errors = validateConfig(config)
    if (errors.isNotEmpty()) {
        throw ConfigValidationException(errors)
    }
}

internal fun formatConfigValidationSummary(fieldErrors: Map<String, String>): String {
    return fieldErrors.entries.joinToString("; ") { (field, message) ->
        "$field: $message"
    }
}

private fun validateChoice(
    errors: MutableMap<String, String>,
    field: String,
    value: String,
    validValues: Set<String>,
    message: String
) {
    if (value.trim() !in validValues) {
        errors[field] = message
    }
}

private fun validateIntRange(
    errors: MutableMap<String, String>,
    field: String,
    value: String,
    min: Int,
    max: Int,
    message: String
) {
    val parsed = value.trim().toIntOrNull()
    if (parsed == null || parsed !in min..max) {
        errors[field] = message
    }
}

private fun validateOptionalInt(
    errors: MutableMap<String, String>,
    field: String,
    value: String,
    message: String
) {
    if (value.trim().toIntOrNull() == null) {
        errors[field] = message
    }
}

private fun validateHex16(
    errors: MutableMap<String, String>,
    field: String,
    value: String,
    allowBroadcast: Boolean
) {
    val parsed = value.trim()
        .removePrefix("0x")
        .removePrefix("0X")
        .toIntOrNull(16)

    val isValid = when {
        parsed == null -> false
        allowBroadcast -> parsed in 0..0xFFFF
        else -> parsed in 0..0xFFFE
    }

    if (!isValid) {
        errors[field] = "Enter a valid 16-bit hexadecimal address"
    }
}

private fun validateSsid(errors: MutableMap<String, String>, field: String, value: String, label: String) {
    val trimmed = value.trim()
    if (trimmed.isNotEmpty() && trimmed.length > 32) {
        errors[field] = "$label must be 32 characters or fewer"
    }
}

private fun validatePassword(errors: MutableMap<String, String>, config: E220Config) {
    val enabled = config.wifiEnabled.trim() == "1"
    val mode = config.wifiMode.trim()
    val apActive = enabled && (mode == "AP" || mode == "AP_STA")

    if (apActive) {
        val password = config.wifiApPassword
        if (password.isBlank()) {
            errors["wifi_ap_password"] = "AP password must be at least 8 characters"
        } else if (password.length < 8) {
            errors["wifi_ap_password"] = "AP password must be at least 8 characters"
        } else if (password.length > 63) {
            errors["wifi_ap_password"] = "AP password must be 63 characters or fewer"
        }
    } else if (config.wifiApPassword.length > 63) {
        errors["wifi_ap_password"] = "AP password must be 63 characters or fewer"
    }

    if (config.wifiStaPassword.length > 63) {
        errors["wifi_sta_password"] = "STA password must be 63 characters or fewer"
    }
}

private fun validateWifiNetworkRequirements(errors: MutableMap<String, String>, config: E220Config) {
    val enabled = config.wifiEnabled.trim() == "1"
    val mode = config.wifiMode.trim()

    if (!enabled) return

    if ((mode == "AP" || mode == "AP_STA") && config.wifiApSsid.trim().isBlank()) {
        errors["wifi_ap_ssid"] = "AP SSID is required when WiFi AP mode is enabled"
    }

    if ((mode == "STA" || mode == "AP_STA") && config.wifiStaSsid.trim().isBlank()) {
        errors["wifi_sta_ssid"] = "STA SSID is required when WiFi STA mode is enabled"
    }
}
