
package com.dmahony.e220chat

import android.Manifest
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[E220ChatViewModel::class.java]

        setContent {
            E220ChatTheme(darkTheme = vm.darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    E220ChatRoot(vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun E220ChatRoot(vm: E220ChatViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch {
                sendGpsMessage(context, vm) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
        } else {
            Toast.makeText(context, "Location permission is required for /gps", Toast.LENGTH_LONG).show()
        }
    }
    val bluetoothPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
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
    val requestGpsLocation: () -> Unit = {
        val missingLocationPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        if (missingLocationPermission) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            scope.launch {
                sendGpsMessage(context, vm) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
        }
    }
    val clearChatMessages: () -> Unit = {
        vm.clearChatMessages()
        Toast.makeText(context, "Chat cleared", Toast.LENGTH_SHORT).show()
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
                    onGpsCommand = requestGpsLocation,
                    onClearMessages = clearChatMessages,
                    onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                )
                AppTab.RADIO -> SettingsScreen(
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
                    onToggleDebug = vm::updateDebugEnabled,
                    modifier = Modifier.weight(1f)
                )
                AppTab.WIFI -> WifiScreen(
                    vm = vm,
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
    onGpsCommand: () -> Unit,
    onClearMessages: () -> Unit,
    onError: (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var composerFocused by remember { mutableStateOf(false) }
    val slashCommandQuery = remember(draft) { draft }
    val slashMenuExpanded = slashCommandQuery.startsWith("/")
    val slashCommands = remember(slashCommandQuery) {
        val all = SlashCommand.values().toList()
        if (!slashCommandQuery.startsWith("/") || slashCommandQuery.length <= 1) {
            all
        } else {
            val filtered = all.filter { command ->
                command.label.startsWith(slashCommandQuery, ignoreCase = true) ||
                    command.description.contains(slashCommandQuery.drop(1), ignoreCase = true)
            }
            if (filtered.isEmpty()) all else filtered
        }
    }
    val connected = vm.connectionState == ConnectionState.CONNECTED
    val listState = rememberLazyListState()
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val composerBottomPadding = with(LocalDensity.current) {
        composerHeightPx.toDp() + 12.dp
    }

    LaunchedEffect(composerFocused, vm.chatMessages.size) {
        if (vm.chatMessages.isNotEmpty() && (composerFocused || connected)) {
            listState.animateScrollToItem(vm.chatMessages.lastIndex)
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = composerBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    itemsIndexed(vm.chatMessages) { _, message ->
                        MessageBubble(message)
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { composerHeightPx = it.height },
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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { newValue ->
                            draft = newValue
                        },
                        enabled = connected,
                        modifier = Modifier
                            .fillMaxSize()
                            .onFocusChanged { composerFocused = it.isFocused },
                        placeholder = {
                            Text(
                                text = if (connected) "Message" else "Connect BLE to chat",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
                    DropdownMenu(
                        expanded = slashMenuExpanded,
                        onDismissRequest = { },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        slashCommands.forEach { command ->
                            DropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(command.label)
                                        Text(
                                            command.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    when (command) {
                                        SlashCommand.GPS -> onGpsCommand()
                                        SlashCommand.CLEAR -> onClearMessages()
                                    }
                                    draft = ""
                                    composerFocused = false
                                }
                            )
                        }
                    }
                }
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
    val transition = rememberInfiniteTransition(label = "linkGlow")
    val glowPulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )
    val glowColor = if (connected) Color(0xFF2BFF88) else Color(0xFFFF5A5A)
    val glowText = if (connected) "LINK UP" else "LINK DOWN"
    val glowStyle = MaterialTheme.typography.labelMedium.copy(
        color = glowColor,
        shadow = androidx.compose.ui.graphics.Shadow(
            color = glowColor.copy(alpha = 0.90f * glowPulse),
            offset = Offset.Zero,
            blurRadius = 10f * glowPulse + 2f
        )
    )

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
                    text = glowText,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    style = glowStyle
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
                    FilledTonalButton(onClick = onRefresh) {
                        if (vm.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Text("Refresh")
                        }
                    }
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

private suspend fun sendGpsMessage(
    context: android.content.Context,
    vm: E220ChatViewModel,
    onError: (String) -> Unit
) {
    val location = resolveCurrentLocation(context)
    if (location == null) {
        onError("Unable to read GPS location. Turn on location services and try again.")
        return
    }
    vm.sendMessage(buildGpsMessage(location.latitude, location.longitude), onError)
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val uriHandler = LocalUriHandler.current
    val links = remember(message.text) { extractClickableMessageLinks(message.text) }
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        lineHeight = 20.sp,
        color = if (message.sent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    )
    val annotatedText = buildAnnotatedString {
        append(message.text)
        links.forEach { link ->
            addStyle(
                SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline
                ),
                start = link.start,
                end = link.end
            )
            addStringAnnotation(
                tag = "URL",
                annotation = link.url,
                start = link.start,
                end = link.end
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp),
        horizontalArrangement = if (message.sent) Arrangement.End else Arrangement.Start
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val bubbleMaxWidth = maxWidth * 0.85f
            Surface(
                shape = if (message.sent) {
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
                } else {
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
                },
                color = if (message.sent) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 1f)
                },
                contentColor = if (message.sent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(
                    1.dp,
                    if (message.sent) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
                    }
                ),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .widthIn(max = bubbleMaxWidth)
                    .align(if (message.sent) Alignment.CenterEnd else Alignment.CenterStart)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    ClickableText(
                        text = annotatedText,
                        style = bodyStyle,
                        onClick = { offset ->
                            links.firstOrNull { offset >= it.start && offset < it.end }?.let { link ->
                                uriHandler.openUri(link.url)
                            }
                        }
                    )
                    if (message.sent) {
                        Text(
                            text = "✓ Sent",
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
}
@Composable
private fun DebugScreen(
    vm: E220ChatViewModel,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onToggleDebug: (Boolean) -> Unit,
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
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    Text("Debug auto-refresh", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Enable live debug polling. Manual refresh still works when off.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = vm.debugEnabled,
                    onCheckedChange = onToggleDebug
                )
            }
        }

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

@Composable
private fun WifiScreen(
    vm: E220ChatViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    var selectedNetwork by remember { mutableStateOf<WifiNetwork?>(null) }
    var wifiPassword by remember { mutableStateOf("") }
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
                    ConfigField(
                        label = "AP Password",
                        value = vm.wifiStatus.apPassword,
                        supportingText = "Set the password for the ESP32 Access Point.",
                        modifier = Modifier.fillMaxWidth()
                    ) { pwd ->
                        vm.setWifiApPassword(
                            pwd,
                            onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                            onSuccess = { vm.refreshWifi() }
                        )
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
                        enabled = wifiSupported && vm.wifiStatus.enabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan")
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

                if (vm.wifiStatus.enabled && vm.wifiNetworks.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        vm.wifiNetworks.forEach { net ->
                            val savedPasswordAvailable = vm.wifiStatus.staSsid == net.ssid && vm.wifiStatus.staPassword.isNotBlank()
                            OutlinedCard(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    selectedNetwork = net
                                    wifiPassword = if (savedPasswordAvailable) vm.wifiStatus.staPassword else ""
                                }
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(net.ssid, style = MaterialTheme.typography.bodyMedium)
                                        Text("${net.rssi} dBm", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(
                                        text = "Channel ${net.channel} • ${if (net.encrypted) "Encrypted" else "Open"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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
