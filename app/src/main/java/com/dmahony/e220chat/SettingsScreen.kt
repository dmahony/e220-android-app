
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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

            // ---- Appearance section ----
            ConfigSectionCard(
                title = "Appearance",
                subtitle = "Theme, colors, and text size."
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Theme mode selector
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Theme", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Choose a color scheme or let the system decide.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ThemeMode.values().forEach { mode ->
                                FilterChip(
                                    selected = vm.themeMode == mode,
                                    onClick = { vm.selectTheme(mode) },
                                    label = { Text(mode.label) }
                                )
                            }
                        }
                    }

                    // Font scale selector
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Text size", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Adjust the size of text throughout the app.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FontScale.values().forEach { scale ->
                                FilterChip(
                                    selected = vm.fontScale == scale,
                                    onClick = { vm.updateFontScale(scale) },
                                    label = { Text(scale.label) }
                                )
                            }
                        }
                    }
                }
            }

            // ---- Debug Options section ----
            ConfigSectionCard(
                title = "Debug Options",
                subtitle = "ESP32 diagnostics, Bluetooth protocol transcript, and radio log viewer."
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Debug auto-refresh toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                            Text("Debug auto-refresh", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Enable live debug polling. Manual refresh still works when off.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = vm.debugEnabled,
                            onCheckedChange = vm::updateDebugEnabled
                        )
                    }

                    // Diagnostics display
                    if (vm.diagnosticsError != null) {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Text(
                                vm.diagnosticsError!!,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("ESP32 diagnostics", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MiniChip("Timeouts ${vm.diagnostics.e220Timeouts}")
                                MiniChip("RX ${vm.diagnostics.e220RxErrors}")
                                MiniChip("TX ${vm.diagnostics.e220TxErrors}")
                            }
                            Text("Bluetooth name: ${vm.diagnostics.btName.ifBlank { "Unknown" }}")
                            Text("Bluetooth client connected: ${if (vm.diagnostics.btHasClient) "Yes" else "No"}")
                            Text("Uptime: ${vm.diagnostics.uptimeMs} ms")
                            Text("Free heap: ${vm.diagnostics.freeHeap}")
                            Text("Min free heap: ${vm.diagnostics.minFreeHeap}")
                            Text("App requests seen by ESP32: ${vm.diagnostics.btRequestCount}")
                            Text("Parse errors on ESP32: ${vm.diagnostics.btParseErrors}")
                            Text("Raw radio messages: ${vm.diagnostics.btRawMessageCount}")
                            Text("Last RSSI: ${vm.diagnostics.lastRssi}")
                            FilledTonalButton(onClick = vm::refreshDebugNow) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Refresh")
                            }
                        }
                    }

                    // Bluetooth protocol transcript
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Bluetooth protocol transcript", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Everything the app sends to and receives from the ESP32 over Bluetooth.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
                                Text(
                                    text = vm.transportLogText.ifBlank { "No Bluetooth transcript yet." },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // ESP32 radio log
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("ESP32 radio log", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "This is the firmware log, including radio TX/RX lines coming from the E220 attached to the ESP32.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = vm::clearDebug) {
                                    Icon(Icons.Default.Clear, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Clear")
                                }
                            }
                            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
                                Text(
                                    text = vm.debugText.ifBlank { "No radio debug output yet." },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // ---- WiFi Status & Control section ----
            ConfigSectionCard(
                title = "WiFi Status & Control",
                subtitle = "Live WiFi status, scan for networks, connect, and manage connectivity."
            ) {
                WiFiStatusContent(vm)
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

@Composable
private fun WiFiStatusContent(vm: E220ChatViewModel) {
    val context = LocalContext.current
    var selectedNetwork by remember { mutableStateOf<WifiNetwork?>(null) }
    var wifiPassword by remember { mutableStateOf("") }
    var apPasswordDraft by remember(vm.wifiStatus.apPassword) { mutableStateOf(vm.wifiStatus.apPassword) }
    val wifiSupported = vm.wifiApiSupported

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // WiFi Status overview
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("WiFi enabled", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (vm.wifiStatus.enabled) "ESP32 WiFi is on" else "ESP32 WiFi is off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = vm.wifiStatus.enabled,
                    enabled = wifiSupported,
                    onCheckedChange = { enabled ->
                        vm.setWifiEnabled(
                            enabled = enabled,
                            onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                            onSuccess = {
                                if (!enabled) {
                                    selectedNetwork = null
                                    wifiPassword = ""
                                }
                            }
                        )
                    }
                )
            }
            Text("Enabled: ${if (vm.wifiStatus.enabled) "Yes" else "No"}", style = MaterialTheme.typography.bodyMedium)
            Text("Mode: ${vm.wifiStatus.mode}", style = MaterialTheme.typography.bodyMedium)
            if (vm.wifiStatus.mode == "AP") {
                Text("AP SSID: ${vm.wifiStatus.apSsid.ifBlank { "Not set" }}", style = MaterialTheme.typography.bodyMedium)
                Text("AP IP: ${vm.wifiStatus.apIp.ifBlank { "Not assigned" }}", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("STA SSID: ${vm.wifiStatus.staSsid.ifBlank { "Not set" }}", style = MaterialTheme.typography.bodyMedium)
                Text("Connected: ${if (vm.wifiStatus.staConnected) "Yes" else "No"}", style = MaterialTheme.typography.bodyMedium)
                Text("STA IP: ${vm.wifiStatus.staIp.ifBlank { "Not assigned" }}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // WiFi Control buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.refreshWifi() },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Refresh")
            }
            Button(
                onClick = {
                    vm.disconnectWifi(
                        onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                        onSuccess = { vm.refreshWifi() }
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Disconnect")
            }
        }

        if (vm.wifiStatus.mode == "AP") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigField(
                    label = "AP Password",
                    value = apPasswordDraft,
                    supportingText = "Set the password for the ESP32 Access Point.",
                    modifier = Modifier.fillMaxWidth(),
                    isPassword = true
                ) { pwd ->
                    apPasswordDraft = pwd
                }
                Button(
                    onClick = {
                        vm.setWifiApPassword(
                            apPasswordDraft,
                            onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                            onSuccess = { vm.refreshWifi() }
                        )
                    },
                    enabled = apPasswordDraft != vm.wifiStatus.apPassword,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save AP password")
                }
            }
        }

        // Station Mode - scan and connect
        if (!wifiSupported) {
            Text(
                text = "WiFi controls aren't supported by the current firmware build.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.scanWifiNetworks() },
                enabled = wifiSupported && vm.wifiStatus.enabled && !vm.wifiScanInProgress,
                modifier = Modifier.weight(1f)
            ) {
                if (vm.wifiScanInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (vm.wifiScanInProgress) "Scanning" else "Scan")
            }
            Button(
                onClick = {
                    val network = selectedNetwork
                    if (network != null) {
                        val connectPassword = if (network.encrypted) wifiPassword else ""
                        vm.connectWifi(
                            ssid = network.ssid,
                            password = connectPassword,
                            onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                            onSuccess = {
                                selectedNetwork = null
                                wifiPassword = ""
                                vm.refreshWifi()
                                vm.scanWifiNetworks()
                            }
                        )
                    }
                },
                enabled = wifiSupported && vm.wifiStatus.enabled && selectedNetwork != null,
                modifier = Modifier.weight(1f)
            ) {
                Text("Connect")
            }
        }

        // Scan result display
        if (vm.wifiScanInProgress || vm.wifiScanResult.scan.status != "idle") {
            val scan = vm.wifiScanResult.scan
            val isScanning = vm.wifiScanInProgress || scan.status.equals("scanning", ignoreCase = true)
            val isSuccess = scan.status.equals("success", ignoreCase = true)
            val isError = scan.status.equals("error", ignoreCase = true)
            val chipText = when {
                isScanning -> "SCANNING"
                isSuccess -> "SUCCESS"
                isError -> "ERROR"
                else -> scan.status.uppercase()
            }
            val chipColors = when {
                isScanning -> StatusChipColors(
                    container = Color(0xFFFFF4CC),
                    content = Color(0xFF8A6A00)
                )
                isSuccess -> StatusChipColors(
                    container = Color(0xFFDFF3E3),
                    content = Color(0xFF11662E)
                )
                isError -> StatusChipColors(
                    container = Color(0xFFFFE3E1),
                    content = Color(0xFFB3261E)
                )
                else -> StatusChipColors(
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (isScanning) "Scan in progress" else "Last scan",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        StatusChip(text = chipText, colors = chipColors)
                    }
                    if (isScanning) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Still scanning for networks. Older phones may need a little longer to receive the result.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text("Status: ${scan.status}", style = MaterialTheme.typography.bodyMedium)
                        Text("Networks: ${scan.networkCount}", style = MaterialTheme.typography.bodyMedium)
                        Text("Duration: ${scan.durationMs} ms", style = MaterialTheme.typography.bodyMedium)
                        Text("Requested at: ${scan.requestedAtMs} ms", style = MaterialTheme.typography.bodyMedium)
                        Text("Completed at: ${scan.completedAtMs} ms", style = MaterialTheme.typography.bodyMedium)
                        if (scan.errorCode != null) {
                            Text("ESP32 error code: ${scan.errorCode}", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (scan.error.isNotBlank()) {
                            Text(
                                text = scan.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Network list
        if (vm.wifiStatus.enabled && vm.wifiNetworks.isNotEmpty()) {
            val visibleNetworks = vm.wifiNetworks.sortedByDescending { it.rssi }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (visibleNetworks.size > 1) {
                    Text(
                        text = "Sorted by signal strength",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                visibleNetworks.forEach { net ->
                    val isSelected = selectedNetwork?.ssid == net.ssid
                    val isConnected = vm.wifiStatus.staSsid == net.ssid && vm.wifiStatus.staConnected
                    val savedPasswordAvailable = vm.wifiStatus.staSsid == net.ssid && vm.wifiStatus.staPassword.isNotBlank()
                    val cardContainer = when {
                        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        isConnected -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                    }
                    val cardBorderColor = when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        isConnected -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                    val signalTint = when {
                        isConnected -> MaterialTheme.colorScheme.tertiary
                        isSelected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = cardContainer),
                        border = BorderStroke(if (isSelected || isConnected) 2.dp else 1.dp, cardBorderColor),
                        onClick = {
                            selectedNetwork = net
                            wifiPassword = if (savedPasswordAvailable) vm.wifiStatus.staPassword else ""
                        }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(net.ssid, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "Channel ${net.channel} • ${if (net.encrypted) "Encrypted" else "Open"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    WifiSignalBars(
                                        rssi = net.rssi,
                                        tint = signalTint,
                                        modifier = Modifier.width(48.dp)
                                    )
                                    Text(
                                        text = "${net.rssi} dBm",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (isSelected) {
                                    StatusChip(
                                        text = "SELECTED",
                                        colors = StatusChipColors(
                                            container = MaterialTheme.colorScheme.primary,
                                            content = MaterialTheme.colorScheme.onPrimary
                                        )
                                    )
                                }
                                if (isConnected) {
                                    StatusChip(
                                        text = "CONNECTED",
                                        colors = StatusChipColors(
                                            container = MaterialTheme.colorScheme.tertiary,
                                            content = MaterialTheme.colorScheme.onTertiary
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (vm.wifiError == null && vm.wifiStatus.enabled) {
            Text("No networks scanned yet.", style = MaterialTheme.typography.bodySmall)
        } else if (!vm.wifiStatus.enabled) {
            Text("Turn WiFi on to scan for networks or connect to one.", style = MaterialTheme.typography.bodySmall)
        }

        // Selected network detail
        selectedNetwork?.let { network ->
            val savedPasswordAvailable = vm.wifiStatus.staSsid == network.ssid && vm.wifiStatus.staPassword.isNotBlank()
            val canConnect = !network.encrypted || wifiPassword.length >= 8
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Selected network", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(network.ssid, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Channel ${network.channel} • ${network.rssi} dBm • ${if (network.encrypted) "Encrypted" else "Open"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (network.encrypted) {
                        OutlinedTextField(
                            value = wifiPassword,
                            onValueChange = { wifiPassword = it },
                            label = { Text("Password") },
                            placeholder = { Text("Enter WiFi password") },
                            supportingText = {
                                Text(if (savedPasswordAvailable) "Saved password is available for this SSID." else "WPA2 passwords need at least 8 characters.")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedLabelColor = MaterialTheme.colorScheme.primary,
                                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                cursorColor = MaterialTheme.colorScheme.primary,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                focusedSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unfocusedSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { wifiPassword = vm.wifiStatus.staPassword },
                            enabled = savedPasswordAvailable,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Use saved password")
                        }
                        Button(
                            enabled = canConnect,
                            onClick = {
                                val connectPassword = if (network.encrypted) wifiPassword else ""
                                vm.connectWifi(
                                    ssid = network.ssid,
                                    password = connectPassword,
                                    onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                                    onSuccess = {
                                        selectedNetwork = null
                                        wifiPassword = ""
                                        vm.refreshWifi()
                                        vm.scanWifiNetworks()
                                    }
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Connect")
                        }
                    }
                    TextButton(
                        onClick = {
                            selectedNetwork = null
                            wifiPassword = ""
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Clear selection")
                    }
                }
            }
        }
    }
}
