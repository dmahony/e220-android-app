
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.IntrinsicSize

@Composable
private fun CompactConnectionBanner(
    connectionHint: String,
    selectedDeviceName: String,
    onOpenBluetooth: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = selectedDeviceName.ifBlank { "No paired device selected" },
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = connectionHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onOpenBluetooth) {
                Text("Connect")
            }
        }
    }
}

@Composable
private fun EmptyThreadHint(connected: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (connected) "No messages yet" else "Chat stays clear until the link is live",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = if (connected) "Your conversation will appear here as messages move across the radio." else "Connect once, then use this screen like a real messenger.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BluetoothDeviceDialog(
    vm: E220ChatViewModel,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: (BluetoothDeviceInfo) -> Unit,
    onDisconnect: () -> Unit,
    onPick: (BluetoothDeviceInfo) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paired Bluetooth devices") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Pair the ESP32 in Android Bluetooth settings first, then select it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onRefresh) { Text("Refresh") }
                    if (vm.connectionState == ConnectionState.CONNECTED) {
                        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                    }
                }
                if (vm.bluetoothDevices.isEmpty()) {
                    Text("No paired Bluetooth devices found.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.bluetoothDevices.forEach { device ->
                            OutlinedCard(
                                onClick = {
                                    onPick(device)
                                    onConnect(device)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        device.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (device.address == vm.selectedBluetoothAddress) {
                                        Text(
                                            "Selected",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.sent) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = if (message.sent) {
                RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 8.dp)
            } else {
                RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 8.dp, bottomEnd = 22.dp)
            },
            color = if (message.sent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (message.sent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(0.78f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (message.sent && message.delivered) {
                    Icon(
                        imageVector = Icons.Default.Done,
                        contentDescription = "Sent",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    vm: E220ChatViewModel,
    onRefresh: () -> Unit,
    onSave: () -> Unit,
    onQuickSave: () -> Unit,
    onReboot: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("All firmware settings from the original web configurator are available here.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniChip("Freq ${vm.config.freq} MHz")
                MiniChip("Addr ${vm.config.addr}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Channel dropdown (0-83)
                val channelOptions = (0..83).map { it.toString() to it.toString() }
                DropdownConfigField("Channel", vm.config.freq, options = channelOptions, modifier = Modifier.weight(1f)) { vm.setConfigField("freq", it) }
                // TX power dropdown with common levels
                val powerOptions = listOf(
                    "22dBm (0)" to "0",
                    "17dBm (1)" to "1",
                    "14dBm (2)" to "2",
                    "10dBm (3)" to "3",
                    "30dBm (0)" to "0",
                    "27dBm (1)" to "1",
                    "24dBm (2)" to "2",
                    "21dBm (3)" to "3",
                    "18dBm (4)" to "4",
                    "15dBm (5)" to "5",
                    "12dBm (6)" to "6",
                    "9dBm (7)" to "7",
                    "6dBm (8)" to "8",
                    "3dBm (9)" to "9",
                    "0dBm (10)" to "10"
                )
                DropdownConfigField("TX power", vm.config.txpower, options = powerOptions, modifier = Modifier.weight(1f)) { vm.setConfigField("txpower", it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Baud rate dropdown
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
                DropdownConfigField("Baud", vm.config.baud, options = baudOptions, modifier = Modifier.weight(1f)) { vm.setConfigField("baud", it) }
                // Air rate dropdown
                val airRateOptions = listOf(
                    "2.4Kbps (0)" to "0",
                    "2.4Kbps (1)" to "1",
                    "2.4Kbps (2)" to "2",
                    "4.8Kbps (3)" to "3",
                    "9.6Kbps (4)" to "4",
                    "19.2Kbps (5)" to "5",
                    "38.4Kbps (6)" to "6",
                    "62.5Kbps (7)" to "7"
                )
                DropdownConfigField("Air rate", vm.config.airrate, options = airRateOptions, modifier = Modifier.weight(1f)) { vm.setConfigField("airrate", it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigField("Address", vm.config.addr, modifier = Modifier.weight(1f)) { vm.setConfigField("addr", it) }
                ConfigField("Dest Address", vm.config.dest, modifier = Modifier.weight(1f)) { vm.setConfigField("dest", it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigField("Packet Length", vm.config.subpkt, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { vm.setConfigField("subpkt", it) }
                ConfigField("Parity", vm.config.parity, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { vm.setConfigField("parity", it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // TX mode dropdown
                val txModeOptions = listOf(
                    "Transparent (0)" to "0",
                    "Fixed-point (1)" to "1"
                )
                DropdownConfigField("TX mode", vm.config.txmode, options = txModeOptions, modifier = Modifier.weight(1f)) { vm.setConfigField("txmode", it) }
                // LBT dropdown
                val lbtOptions = listOf(
                    "Off (0)" to "0",
                    "On (1)" to "1"
                )
                DropdownConfigField("LBT", vm.config.lbt, options = lbtOptions, modifier = Modifier.weight(1f)) { vm.setConfigField("lbt", it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigField("LBT RSSI", vm.config.lbrRssi, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { vm.setConfigField("lbr_rssi", it) }
                ConfigField("LBT Timeout", vm.config.lbrTimeout, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { vm.setConfigField("lbr_timeout", it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigField("Frame Drop", vm.config.urxt, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { vm.setConfigField("urxt", it) }
                ConfigField("RSSI noise", vm.config.rssiNoise, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { vm.setConfigField("rssi_noise", it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigField("RSSI byte", vm.config.rssiByte, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { vm.setConfigField("rssi_byte", it) }
                ConfigField("WOR cycle", vm.config.worCycle, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { vm.setConfigField("wor_cycle", it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigField("Crypto high", vm.config.cryptH, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { vm.setConfigField("crypt_h", it) }
                ConfigField("Crypto low", vm.config.cryptL, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { vm.setConfigField("crypt_l", it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigField("Save Type", vm.config.saveType, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { vm.setConfigField("savetype", it) }
            }
        }
    }
}

@Composable
private fun DebugScreen(
    vm: E220ChatViewModel,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (vm.diagnosticsError != null) ErrorBanner(vm.diagnosticsError!!)

        ElevatedCard(Modifier.fillMaxWidth()) {
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
                FilledTonalButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh")
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
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

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ESP32 radio log", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This is the firmware log, including radio TX/RX lines coming from the E220 attached to the ESP32.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onClear) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Clear")
                    }
                    FilledTonalButton(onClick = onTogglePause) {
                        Icon(if (vm.debugPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (vm.debugPaused) "Resume" else "Pause")
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

@Composable
private fun ErrorBanner(message: String) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(message, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun SuccessBanner(message: String) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Text(message, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun MiniChip(text: String) {
    AssistChip(onClick = {}, label = { Text(text) })
}

// Generic text field
@Composable
private fun ConfigField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Next
        )
    )
}

// Dropdown version for fields with limited options
@Composable
private fun DropdownConfigField(
    label: String,
    selectedValue: String,
    options: List<Pair<String, String>>, // display, actual
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = options.find { it.second == selectedValue }?.first ?: selectedValue
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = modifier.width(IntrinsicSize.Min)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (display, actual) ->
                DropdownMenuItem(onClick = {
                    onValueChange(actual)
                    expanded = false
                }) {
                    Text(display)
                }
            }
        }
    }
}
