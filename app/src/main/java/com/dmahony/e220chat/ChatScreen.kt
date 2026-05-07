
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
internal fun ChatScreen(
    vm: E220ChatViewModel,
    modifier: Modifier = Modifier,
    onOpenBluetooth: () -> Unit,
    onReconnectBluetooth: () -> Unit,
    onGpsCommand: () -> Unit,
    onClearMessages: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
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
    val canReconnect = vm.selectedBluetoothAddress.isNotBlank()
    var pendingSendDraft by remember { mutableStateOf<String?>(null) }
    val sendDraftNow: (String) -> Unit = { message ->
        vm.sendMessage(
            message,
            onError = onError,
            onSuccess = {
                draft = ""
                pendingSendDraft = null
            }
        )
    }
    val sendCurrentDraft: () -> Unit = {
        val trimmedDraft = draft.trim()
        when {
            trimmedDraft.isEmpty() -> {
                Toast.makeText(context, "Type a message first", Toast.LENGTH_SHORT).show()
            }
            connected -> {
                sendDraftNow(trimmedDraft)
            }
            canReconnect -> {
                val address = vm.selectedBluetoothAddress
                val name = vm.selectedBluetoothName.ifBlank { address }
                if (address.isBlank()) {
                    onOpenBluetooth()
                } else {
                    Toast.makeText(context, "Reconnecting to BLE, then sending the draft", Toast.LENGTH_SHORT).show()
                    vm.connectBluetooth(
                        device = BluetoothDeviceInfo(name = name, address = address),
                        onError = onError,
                        onSuccess = { sendDraftNow(trimmedDraft) }
                    )
                }
            }
            else -> {
                onOpenBluetooth()
            }
        }
    }
    LaunchedEffect(vm.connectionState, pendingSendDraft) {
        val queuedDraft = pendingSendDraft?.trim().orEmpty()
        if (queuedDraft.isNotEmpty() && connected) {
            pendingSendDraft = null
            sendDraftNow(queuedDraft)
        }
    }
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
            canReconnect = vm.selectedBluetoothAddress.isNotBlank(),
            onOpenBluetooth = onOpenBluetooth,
            onReconnectBluetooth = onReconnectBluetooth
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
                            if (pendingSendDraft != null) {
                                pendingSendDraft = newValue.trim().takeIf { it.isNotEmpty() }
                            }
                        },
                        enabled = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .onFocusChanged { composerFocused = it.isFocused },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { sendCurrentDraft() },
                            onDone = { sendCurrentDraft() }
                        ),
                        placeholder = {
                            Text(
                                text = when {
                                    connected -> "Message"
                                    canReconnect -> "Draft now; reconnect to send"
                                    else -> "Connect BLE to chat"
                                },
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
                                        SlashCommand.NAME -> {
                                            val name = draft.removePrefix("/name").trim()
                                            if (name.isEmpty()) {
                                                Toast.makeText(context, "Please provide a name: /name Alice", Toast.LENGTH_SHORT).show()
                                            } else {
                                                vm.setUsername(name, 
                                                    onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }, 
                                                    onSuccess = { Toast.makeText(context, "Username set to $name", Toast.LENGTH_SHORT).show() }
                                                )
                                            }
                                        }
                                    }
                                    draft = ""
                                    composerFocused = false
                                }
                            )
                        }
                    }
                }
                FilledTonalButton(
                    onClick = sendCurrentDraft,
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = if (connected) 14.dp else 12.dp, vertical = 0.dp)
                ) {
                    when {
                        connected -> {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                        pendingSendDraft != null -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Reconnecting")
                        }
                        canReconnect -> {
                            Text("Reconnect & Send")
                        }
                        else -> {
                            Text("Connect")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompactConnectionBanner(
    connectionHint: String,
    selectedDeviceName: String,
    connected: Boolean,
    canReconnect: Boolean,
    onOpenBluetooth: () -> Unit,
    onReconnectBluetooth: () -> Unit
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
            if (connected) {
                TextButton(
                    onClick = onOpenBluetooth,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Manage")
                }
            } else if (canReconnect) {
                TextButton(
                    onClick = onReconnectBluetooth,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Reconnect")
                }
            } else {
                TextButton(
                    onClick = onOpenBluetooth,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
internal fun EmptyThreadHint(connected: Boolean) {
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
internal fun BluetoothDeviceDialog(
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
                    if (vm.selectedBluetoothAddress.isNotBlank()) {
                        OutlinedButton(onClick = { onConnect(BluetoothDeviceInfo(name = vm.selectedBluetoothName.ifBlank { "Unnamed device" }, address = vm.selectedBluetoothAddress)) }) {
                            Text("Reconnect last device")
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

internal suspend fun sendGpsMessage(
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
internal fun MessageBubble(message: ChatMessage) {
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

