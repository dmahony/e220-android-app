
@file:OptIn(ExperimentalLayoutApi::class)
package com.dmahony.e220chat

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi

@Composable
internal fun AppearanceScreen(
    vm: E220ChatViewModel,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Text("\u2190 Back to Settings")
            }
        }

        Text(
            "Appearance",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        ConfigSectionCard(
            title = "Theme",
            subtitle = "Choose a color scheme or let the system decide."
        ) {
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

        ConfigSectionCard(
            title = "Text size",
            subtitle = "Adjust the size of text throughout the app."
        ) {
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
