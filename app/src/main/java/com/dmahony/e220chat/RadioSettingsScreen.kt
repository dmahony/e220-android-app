@file:OptIn(ExperimentalLayoutApi::class)
package com.dmahony.e220chat

import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun RadioSettingsScreen(
    vm: E220ChatViewModel,
    onRefresh: () -> Unit,
    onSave: () -> Unit,
    onRestoreDefaults: () -> Unit,
    onReboot: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val scroll = rememberScrollState()

    fun optionLabel(options: List<Pair<String, String>>, value: String): String =
        options.firstOrNull { it.second == value }?.first ?: value

    LaunchedEffect(vm.selectedTab, vm.connectionState) {
        if (vm.selectedTab == AppTab.SETTINGS && vm.connectionState == ConnectionState.CONNECTED) {
            onRefresh()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Text("← Back to Settings")
            }
        }
        vm.configError?.let { ErrorBanner(it) }
        vm.configStatus?.takeIf { it.isNotBlank() }?.let { SuccessBanner(it) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---- Radio preset summary ----
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Current settings",
                        style = MaterialTheme.typography.titleSmall
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val channel = freqStringToChannelOrFallback(vm.config.freq, 0)
                        MiniChip("Ch $channel • ${vm.config.freq} MHz")
                        MiniChip("Power ${optionLabel(txPowerOptions, vm.config.txpower)}")
                        MiniChip("Baud ${optionLabel(baudOptions, vm.config.baud)}")
                        MiniChip("TX mode ${optionLabel(txModeOptions, vm.config.txmode)}")
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MiniChip("Model: ${vm.diagnostics.radioModel.ifBlank { "—" }}")
                        MiniChip("Software version: ${vm.diagnostics.softwareVersion.ifBlank { "—" }}")
                    }
                }
            }

            // ---- RF link ----
            ConfigSectionCard(
                title = "RF link",
                subtitle = "Carrier frequency, transmit power, air rate, transmission mode, and LBT."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownConfigField(
                        label = "Channel / frequency",
                        selectedValue = vm.config.freq,
                        options = channelOptions,
                        errorText = vm.configFieldErrors["freq"],
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("freq", it) }
                    DropdownConfigField(
                        label = "TX power",
                        selectedValue = vm.config.txpower,
                        options = txPowerOptions,
                        errorText = vm.configFieldErrors["txpower"],
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("txpower", it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownConfigField(
                        label = "Air rate",
                        selectedValue = vm.config.airrate,
                        options = airRateOptions,
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("airrate", it) }
                    DropdownConfigField(
                        label = "TX mode",
                        selectedValue = vm.config.txmode,
                        options = txModeOptions,
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("txmode", it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownConfigField(
                        label = "LBT",
                        selectedValue = vm.config.lbt,
                        options = onOffOptions,
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("lbt", it) }
                    DropdownConfigField(
                        label = "WOR cycle",
                        selectedValue = vm.config.worCycle,
                        options = wakeTimeOptions,
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("wor_cycle", it) }
                }
            }

            // ---- Serial link ----
            ConfigSectionCard(
                title = "Serial link",
                subtitle = "UART baud, parity, packet length, and frame-drop timing."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownConfigField(
                        label = "Baud",
                        selectedValue = vm.config.baud,
                        options = baudOptions,
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("baud", it) }
                    DropdownConfigField(
                        label = "Parity",
                        selectedValue = vm.config.parity,
                        options = parityOptions,
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("parity", it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownConfigField(
                        label = "Packet length",
                        selectedValue = vm.config.subpkt,
                        options = packetLengthOptions,
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("subpkt", it) }
                    ConfigField(
                        label = "URXT",
                        value = vm.config.urxt,
                        supportingText = "Manual range: 1–255 byte times. Default 3.",
                        errorText = vm.configFieldErrors["urxt"],
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number
                    ) { vm.setConfigField("urxt", it) }
                }
            }

            // ---- Addressing & encryption ----
            ConfigSectionCard(
                title = "Addressing & encryption",
                subtitle = "Communication address, destination, and 16-bit key fields."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfigField(
                        label = "Address",
                        value = vm.config.addr,
                        supportingText = "Manual range: 0–65535. Shared group address defaults to 1.",
                        errorText = vm.configFieldErrors["addr"],
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("addr", it) }
                    ConfigField(
                        label = "Destination",
                        value = vm.config.dest,
                        supportingText = "Manual range: 0–65535. Shared group address defaults to 1.",
                        errorText = vm.configFieldErrors["dest"],
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("dest", it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfigField(
                        label = "Crypto high",
                        value = vm.config.cryptH,
                        supportingText = "App-specific 16-bit key high byte.",
                        errorText = vm.configFieldErrors["crypt_h"],
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number
                    ) { vm.setConfigField("crypt_h", it) }
                    ConfigField(
                        label = "Crypto low",
                        value = vm.config.cryptL,
                        supportingText = "App-specific 16-bit key low byte.",
                        errorText = vm.configFieldErrors["crypt_l"],
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number
                    ) { vm.setConfigField("crypt_l", it) }
                }
            }

            // ---- RSSI and save ----
            ConfigSectionCard(
                title = "RSSI and save",
                subtitle = "Optional RSSI helpers, LBT threshold, timeout, and save mode."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownConfigField(
                        label = "RSSI noise",
                        selectedValue = vm.config.rssiNoise,
                        options = onOffOptions,
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("rssi_noise", it) }
                    DropdownConfigField(
                        label = "RSSI byte",
                        selectedValue = vm.config.rssiByte,
                        options = onOffOptions,
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("rssi_byte", it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfigField(
                        label = "LBT RSSI",
                        value = vm.config.lbrRssi,
                        supportingText = "Manual range: -128 to 0 dBm. Default -85.",
                        errorText = vm.configFieldErrors["lbr_rssi"],
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number
                    ) { vm.setConfigField("lbr_rssi", it) }
                    ConfigField(
                        label = "LBT timeout",
                        value = vm.config.lbrTimeout,
                        supportingText = "Manual range: 0–65535 ms. Default 2000.",
                        errorText = vm.configFieldErrors["lbr_timeout"],
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number
                    ) { vm.setConfigField("lbr_timeout", it) }
                }
            }

            // ---- Action buttons ----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh")
                }
                Spacer(modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = onRestoreDefaults, modifier = Modifier.weight(1f)) {
                    Text("Restore defaults")
                }
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Text("Save config")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = onReboot, modifier = Modifier.weight(1f)) {
                    Text("Reboot ESP32")
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
