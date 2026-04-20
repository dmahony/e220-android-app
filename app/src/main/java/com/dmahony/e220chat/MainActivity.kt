
package com.dmahony.e220chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.dmahony.e220chat.ui.theme.E220ChatTheme
import java.util.Locale

private fun formatMHz(value: Double): String = String.format(Locale.US, "%.3f", value)

private val channelOptions: List<Pair<String, String>> = (0..80).map { channel ->
    val frequency = 850.125 + channel
    "Ch $channel — ${formatMHz(frequency)} MHz" to formatMHz(frequency)
}

private val txPowerOptions = listOf(
    "22 dBm (0)" to "0",
    "17 dBm (1)" to "1",
    "14 dBm (2)" to "2",
    "10 dBm (3)" to "3"
)

private val baudOptions = listOf(
    "1200 (0)" to "0",
    "2400 (1)" to "1",
    "4800 (2)" to "2",
    "9600 (3)" to "3",
    "19200 (4)" to "4",
    "38400 (5)" to "5",
    "57600 (6)" to "6",
    "115200 (7)" to "7"
)

private val parityOptions = listOf(
    "None (0)" to "0",
    "Odd (1)" to "1",
    "Even (2)" to "2"
)

private val airRateOptions = listOf(
    "2.4 Kbps (0)" to "0",
    "2.4 Kbps (1)" to "1",
    "2.4 Kbps (2)" to "2",
    "4.8 Kbps (3)" to "3",
    "9.6 Kbps (4)" to "4",
    "19.2 Kbps (5)" to "5",
    "38.4 Kbps (6)" to "6",
    "62.5 Kbps (7)" to "7"
)

private val txModeOptions = listOf(
    "Transparent (0)" to "0",
    "Fixed-point (1)" to "1"
)

private val onOffOptions = listOf(
    "Off (0)" to "0",
    "On (1)" to "1"
)

private val packetLengthOptions = listOf(
    "200 bytes (0)" to "0",
    "128 bytes (1)" to "1",
    "64 bytes (2)" to "2",
    "32 bytes (3)" to "3"
)

private val wakeTimeOptions = listOf(
    "500 ms (0)" to "0",
    "1000 ms (1)" to "1",
    "1500 ms (2)" to "2",
    "2000 ms (3)" to "3",
    "2500 ms (4)" to "4",
    "3000 ms (5)" to "5",
    "3500 ms (6)" to "6",
    "4000 ms (7)" to "7"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[E220ChatViewModel::class.java]

        setContent {
            E220ChatTheme(darkTheme = vm.darkTheme) {
                val terminalBackdrop = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF081018),
                        MaterialTheme.colorScheme.background,
                        Color(0xFF0D1720),
                        Color(0xFF081018)
                    )
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(terminalBackdrop)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Transparent
                    ) {
                        E220ChatRoot(vm)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun E220ChatRoot(vm: E220ChatViewModel) {
    val context = LocalContext.current
    var showBluetoothDialog by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.isNotEmpty() && grants.values.all { it }) {
            showBluetoothDialog = true
            vm.refreshBluetoothDevices()
        } else {
            Toast.makeText(context, "Bluetooth permissions are required for BLE scanning", Toast.LENGTH_LONG).show()
        }
    }
    val bluetoothPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val openBluetoothPicker: () -> Unit = {
        val missing = bluetoothPermissions.any { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            permissionLauncher.launch(bluetoothPermissions)
        } else {
            showBluetoothDialog = true
            vm.refreshBluetoothDevices()
        }
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.12f))
            ) {
                TabRow(
                    selectedTabIndex = vm.selectedTab.ordinal,
                    modifier = Modifier.height(26.dp),
                    containerColor = Color.Transparent,
                    divider = {},
                    indicator = { tabPositions ->
                        TabRowDefaults.PrimaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[vm.selectedTab.ordinal]),
                            height = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.68f)
                        )
                    }
                ) {
                    AppTab.values().forEach { tab ->
                        Tab(
                            selected = vm.selectedTab == tab,
                            onClick = { vm.setTab(tab) },
                            modifier = Modifier.height(26.dp),
                            text = {
                                Text(
                                    tab.label,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }

            when (vm.selectedTab) {
                AppTab.CHAT -> ChatScreen(
                    vm = vm,
                    modifier = Modifier.weight(1f),
                    onOpenBluetooth = openBluetoothPicker,
                    onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                )
                AppTab.SETTINGS -> SettingsScreen(
                    vm = vm,
                    onRefresh = vm::refreshConfig,
                    onSave = { vm.saveConfig(onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }, onSuccess = {}) },
                    onQuickSave = { vm.quickSave(onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }, onSuccess = {}) },
                    onReboot = { vm.reboot(onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }, onSuccess = {}) },
                    modifier = Modifier.weight(1f)
                )
                AppTab.DEBUG -> DebugScreen(
                    vm = vm,
                    onRefresh = vm::refreshDebugNow,
                    onClear = vm::clearDebug,
                    onTogglePause = vm::toggleDebugPause,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showBluetoothDialog) {
        BluetoothDeviceDialog(
            vm = vm,
            onDismiss = { showBluetoothDialog = false },
            onRefresh = vm::refreshBluetoothDevices,
            onConnect = { device ->
                vm.connectBluetooth(
                    device = device,
                    onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                    onSuccess = { showBluetoothDialog = false }
                )
            },
            onDisconnect = vm::disconnectBluetooth,
            onPick = vm::selectBluetoothDevice
        )
    }
}

@Composable
private fun ChatScreen(
    vm: E220ChatViewModel,
    modifier: Modifier = Modifier,
    onOpenBluetooth: () -> Unit,
    onError: (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var composerFocused by remember { mutableStateOf(false) }
    val connected = vm.connectionState == ConnectionState.CONNECTED
    val scroll = rememberScrollState()

    LaunchedEffect(composerFocused, vm.chatMessages.size) {
        if (vm.chatMessages.isNotEmpty() && (composerFocused || connected)) {
            scroll.scrollTo(scroll.maxValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        vm.chatError?.let { ErrorBanner(it) }

        CompactConnectionBanner(
            connectionHint = vm.connectionHint,
            selectedDeviceName = vm.selectedBluetoothName,
            connected = vm.connectionState == ConnectionState.CONNECTED,
            onOpenBluetooth = onOpenBluetooth
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
        ) {
            if (vm.chatMessages.isEmpty()) {
                EmptyThreadHint(connected = vm.connectionState == ConnectionState.CONNECTED)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    vm.chatMessages.forEach { message ->
                        MessageBubble(message)
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.12f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = connected,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .onFocusChanged { composerFocused = it.isFocused },
                    placeholder = {
                        Text(if (connected) "Message" else "Connect BLE to chat")
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.12f),
                        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.10f),
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                )
                FilledTonalButton(
                    onClick = {
                        if (connected) {
                            vm.sendMessage(
                                draft,
                                onError = onError,
                                onSuccess = { draft = "" }
                            )
                        } else {
                            onOpenBluetooth()
                        }
                    },
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = if (connected) 14.dp else 12.dp, vertical = 0.dp)
                ) {
                    if (connected) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    } else {
                        Text("Connect")
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactConnectionBanner(
    connectionHint: String,
    selectedDeviceName: String,
    connected: Boolean,
    onOpenBluetooth: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (connected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                border = BorderStroke(
                    1.dp,
                    if (connected) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.16f)
                )
            ) {
                Text(
                    text = if (connected) "LINK UP" else "LINK DOWN",
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (connected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = selectedDeviceName.ifBlank { "No BLE device selected" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = connectionHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(
                onClick = onOpenBluetooth,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(if (connected) "Manage" else "Connect")
            }
        }
    }
}

@Composable
private fun EmptyThreadHint(connected: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = if (connected) "No messages yet" else "Chat stays clear until the link is live",
            style = MaterialTheme.typography.titleSmall,
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
        title = { Text("Nearby BLE devices") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Turn on BLE on the ESP32, then refresh to scan and select it here. If Android scan results are empty, paired devices will still appear below.",
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
                    Text("No BLE devices found.")
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp),
        horizontalArrangement = if (message.sent) Arrangement.End else Arrangement.Start
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val bubbleMaxWidth = maxWidth * 0.82f
            Surface(
                shape = if (message.sent) {
                    RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 14.dp, bottomEnd = 8.dp)
                } else {
                    RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 8.dp, bottomEnd = 14.dp)
                },
                color = if (message.sent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f) else MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = if (message.sent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(
                    1.dp,
                    if (message.sent) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.12f)
                ),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .widthIn(max = bubbleMaxWidth)
                    .align(if (message.sent) Alignment.CenterEnd else Alignment.CenterStart)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                        color = if (message.sent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    if (message.sent && message.delivered) {
                        Text(
                            text = "✓ Delivered",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigSectionCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(
    vm: E220ChatViewModel,
    onRefresh: () -> Unit,
    onSave: () -> Unit,
    onQuickSave: () -> Unit,
    onReboot: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        vm.configError?.let { ErrorBanner(it) }
        vm.configStatus?.takeIf { it.isNotBlank() }?.let { SuccessBanner(it) }

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
                    MiniChip("Freq ${vm.config.freq} MHz")
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
                    modifier = Modifier.weight(1f)
                ) { vm.setConfigField("addr", it) }
                ConfigField(
                    label = "Destination",
                    value = vm.config.dest,
                    supportingText = "Manual range: 0–65535. Defaults to 65535.",
                    modifier = Modifier.weight(1f)
                ) { vm.setConfigField("dest", it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigField(
                    label = "Crypto high",
                    value = vm.config.cryptH,
                    supportingText = "App-specific 16-bit key high byte.",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Number
                ) { vm.setConfigField("crypt_h", it) }
                ConfigField(
                    label = "Crypto low",
                    value = vm.config.cryptL,
                    supportingText = "App-specific 16-bit key low byte.",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Number
                ) { vm.setConfigField("crypt_l", it) }
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
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Number
                ) { vm.setConfigField("lbr_rssi", it) }
                ConfigField(
                    label = "LBT timeout",
                    value = vm.config.lbrTimeout,
                    supportingText = "Manual range: 0–65535 ms. Default 2000.",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Number
                ) { vm.setConfigField("lbr_timeout", it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigField(
                    label = "Save type",
                    value = vm.config.saveType,
                    supportingText = "App-specific save mode. Keep the device default unless you know the firmware behavior.",
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
    supportingText: String? = null,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Next
        ),
        shape = RoundedCornerShape(14.dp),
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

// Dropdown version for fields with limited options
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownConfigField(
    label: String,
    selectedValue: String,
    options: List<Pair<String, String>>, // display, actual
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = options.find { it.second == selectedValue }?.first ?: selectedValue
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
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
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (display, actual) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        onValueChange(actual)
                        expanded = false
                    }
                )
            }
        }
    }
}
