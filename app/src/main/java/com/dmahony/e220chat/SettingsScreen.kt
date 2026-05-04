
@file:OptIn(ExperimentalLayoutApi::class)
package com.dmahony.e220chat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.core.content.ContextCompat
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.lifecycle.ViewModelProvider
import com.dmahony.e220chat.ui.theme.E220ChatTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(
    vm: E220ChatViewModel,
    onRefresh: () -> Unit,
    onSave: () -> Unit,
    onQuickSave: () -> Unit,
    onReboot: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()

    LaunchedEffect(vm.selectedTab, vm.connectionState) {
        if (vm.selectedTab == AppTab.RADIO && vm.connectionState == ConnectionState.CONNECTED) {
            onRefresh()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        vm.configError?.let { ErrorBanner(it) }
        vm.configStatus?.takeIf { it.isNotBlank() }?.let { SuccessBanner(it) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Manual-backed presets + ranges",
                        style = MaterialTheme.typography.titleSmall
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val channel = freqStringToChannelOrFallback(vm.config.freq, 0)
                        MiniChip("Ch $channel • ${vm.config.freq} MHz")
                        MiniChip("Power ${vm.config.txpower}")
                        MiniChip("Baud ${vm.config.baud}")
                        MiniChip("Mode ${vm.config.txmode}")
                    }
                }
            }

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

            ConfigSectionCard(
                title = "Addressing & encryption",
                subtitle = "Communication address, destination, and 16-bit key fields."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfigField(
                        label = "Address",
                        value = vm.config.addr,
                        supportingText = "Manual range: 0–65535. 65535 is broadcast.",
                        errorText = vm.configFieldErrors["addr"],
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("addr", it) }
                    ConfigField(
                        label = "Destination",
                        value = vm.config.dest,
                        supportingText = "Manual range: 0–65535. Defaults to 65535.",
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

            ConfigSectionCard(
                title = "WiFi settings",
                subtitle = "Mirror the ESP32 WiFi config fields returned by the firmware."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownConfigField(
                        label = "WiFi enabled",
                        selectedValue = vm.config.wifiEnabled,
                        options = onOffOptions,
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("wifi_enabled", it) }
                    DropdownConfigField(
                        label = "WiFi mode",
                        selectedValue = vm.config.wifiMode,
                        options = wifiModeOptions,
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("wifi_mode", it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfigField(
                        label = "AP SSID",
                        value = vm.config.wifiApSsid,
                        supportingText = "Access point name broadcast by the ESP32.",
                        errorText = vm.configFieldErrors["wifi_ap_ssid"],
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("wifi_ap_ssid", it) }
                    ConfigField(
                        label = "AP password",
                        value = vm.config.wifiApPassword,
                        isPassword = true,
                        supportingText = "Password for the ESP32 access point.",
                        errorText = vm.configFieldErrors["wifi_ap_password"],
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("wifi_ap_password", it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfigField(
                        label = "STA SSID",
                        value = vm.config.wifiStaSsid,
                        supportingText = "Upstream network name for station mode.",
                        errorText = vm.configFieldErrors["wifi_sta_ssid"],
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("wifi_sta_ssid", it) }
                    ConfigField(
                        label = "STA password",
                        value = vm.config.wifiStaPassword,
                        isPassword = true,
                        supportingText = "Password for the upstream WiFi network.",
                        errorText = vm.configFieldErrors["wifi_sta_password"],
                        modifier = Modifier.weight(1f)
                    ) { vm.setConfigField("wifi_sta_password", it) }
                }
            }

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
                        supportingText = "Manual range: 0 to -128 dBm. Default -55.",
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfigField(
                        label = "Save type",
                        value = vm.config.saveType,
                        supportingText = "App-specific save mode. Keep the device default unless you know the firmware behavior.",
                        errorText = vm.configFieldErrors["savetype"],
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number
                    ) { vm.setConfigField("savetype", it) }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh")
                }
                FilledTonalButton(onClick = onQuickSave, modifier = Modifier.weight(1f)) {
                    Text("Quick save")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Text("Save config")
                }
                FilledTonalButton(onClick = onReboot, modifier = Modifier.weight(1f)) {
                    Text("Reboot ESP32")
                }
            }
        }
    }
}
