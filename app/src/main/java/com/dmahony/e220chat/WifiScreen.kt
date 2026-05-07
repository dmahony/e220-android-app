
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

@Composable
internal fun WifiScreen(
    vm: E220ChatViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    var selectedNetwork by remember { mutableStateOf<WifiNetwork?>(null) }
    var wifiPassword by remember { mutableStateOf("") }
    var apPasswordDraft by remember(vm.wifiStatus.apPassword) { mutableStateOf(vm.wifiStatus.apPassword) }
    val wifiSupported = vm.wifiApiSupported

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (vm.wifiError != null) ErrorBanner(vm.wifiError!!)

        ConfigSectionCard(
            title = "WiFi Status",
            subtitle = "Current connectivity and IP address."
        ) {
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
        }

        ConfigSectionCard(
            title = "WiFi Control",
            subtitle = "Switch modes and manage connectivity."
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        }

        ConfigSectionCard(
            title = "Station Mode",
            subtitle = "Connect the ESP32 to an existing WiFi network."
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
}
