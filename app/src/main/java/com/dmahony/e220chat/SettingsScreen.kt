@file:OptIn(ExperimentalLayoutApi::class)
package com.dmahony.e220chat

import android.widget.Toast
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
 import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Palette

/**
 * Sub-pages within the Settings tab.
 */
private enum class SettingsPage { HUB, RADIO, WIFI, DEBUG, APPEARANCE }

/**
 * Settings hub screen. Shows a list of category cards that navigate to dedicated sub-pages:
 * Radio Settings, WiFi, and Debug.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    vm: E220ChatViewModel,
    onRefresh: () -> Unit,
    onSave: () -> Unit,
    onRestoreDefaults: () -> Unit,
    onReboot: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentPage by remember { mutableStateOf(SettingsPage.HUB) }

    when (currentPage) {
        SettingsPage.HUB -> SettingsHub(
            vm = vm,
            onNavigateToRadio = { currentPage = SettingsPage.RADIO },
            onNavigateToWifi = { currentPage = SettingsPage.WIFI },
            onNavigateToDebug = { currentPage = SettingsPage.DEBUG },
            onNavigateToAppearance = { currentPage = SettingsPage.APPEARANCE },
            modifier = modifier
        )
        SettingsPage.RADIO -> RadioSettingsScreen(
            vm = vm,
            onRefresh = onRefresh,
            onSave = onSave,
            onRestoreDefaults = onRestoreDefaults,
            onReboot = onReboot,
            modifier = modifier
        )

        SettingsPage.WIFI -> WifiScreen(
            vm = vm,
            onBack = { currentPage = SettingsPage.HUB },
            modifier = modifier
        )
        SettingsPage.DEBUG -> DebugScreen(
            vm = vm,
            onRefresh = vm::refreshDebugNow,
            onClear = vm::clearDebug,
            onToggleDebug = vm::updateDebugEnabled,
            onBack = { currentPage = SettingsPage.HUB },
            modifier = modifier
        )
        SettingsPage.APPEARANCE -> AppearanceScreen(
            vm = vm,
            onBack = { currentPage = SettingsPage.HUB },
            modifier = modifier
        )
    }
}

/**
 * The main Settings hub page showing category cards with live summaries.
 */
@Composable
private fun SettingsHub(
    vm: E220ChatViewModel,
    onNavigateToRadio: () -> Unit,
    onNavigateToWifi: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val channel = freqStringToChannelOrFallback(vm.config.freq, 0)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        vm.configError?.let { ErrorBanner(it) }
        vm.configStatus?.takeIf { it.isNotBlank() }?.let { SuccessBanner(it) }

        Text(
            "Settings",
                        color = Color.White,
             style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // ---- Radio Settings card ----
        SettingsCategoryCard(
            icon = { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = "Radio Settings",
            summary = buildString {
                append("Ch $channel • ${vm.config.freq} MHz")
                val powerDisplay = txPowerOptions.find { it.second == vm.config.txpower }?.first?.substringBefore(" dBm") ?: vm.config.txpower
                append(" • ${powerDisplay} dBm")
                val baudVal = vm.config.baud
                val baudDisplay = baudOptions.find { it.second == baudVal }?.first?.removeSuffix(" ($baudVal)") ?: baudVal
                append(" • Baud $baudDisplay")
            },
            subtitle = "Frequency, power, air rate, addressing, appearance",
            onClick = onNavigateToRadio
        )

        // ---- WiFi card ----
        val wifiSummary = when {
            !vm.wifiStatus.enabled -> "WiFi is off"
            vm.wifiStatus.mode == "AP" -> {
                val ssid = vm.wifiStatus.apSsid.ifBlank { "broadcasting" }
                "AP mode: $ssid"
            }
            vm.wifiStatus.staConnected -> "STA: connected to ${vm.wifiStatus.staSsid}"
            else -> "STA mode: ${vm.wifiStatus.staSsid.ifBlank { "not connected" }}"
        }
        SettingsCategoryCard(
            icon = { Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = "WiFi",
            summary = wifiSummary,
            subtitle = "WiFi mode, AP/STA config, scan, connect",
            onClick = onNavigateToWifi
        )

        // ---- Debug card ----
        val debugSummary = if (vm.debugEnabled) "Auto-refresh: on" else "Auto-refresh: off"
        SettingsCategoryCard(
            icon = { Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = "Debug",
            summary = debugSummary,
            subtitle = "Transport logging, firmware info, hex dumps, connection status",
            onClick = onNavigateToDebug
        )

        // ---- Appearance card ----
        val themeSummary = when (vm.themeMode) {
            ThemeMode.LIGHT -> "Light theme"
            ThemeMode.DARK -> "Dark theme"
            ThemeMode.SYSTEM -> "System default"
            ThemeMode.AMOLED -> "AMOLED"
        }
        SettingsCategoryCard(
            icon = { Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = "Appearance",
            summary = "$themeSummary \u2022 ${vm.fontScale.label}",
            subtitle = "Theme, colors, and text size",
            onClick = onNavigateToAppearance
        )

        // ---- Action buttons at bottom ----
        Spacer(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = onNavigateToRadio, modifier = Modifier.weight(1f)) {
                Text("Radio config")
            }
        }
    }
}

/**
 * A clickable card for a settings category on the hub page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCategoryCard(
    icon: @Composable () -> Unit,
    title: String,
    summary: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            icon()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
