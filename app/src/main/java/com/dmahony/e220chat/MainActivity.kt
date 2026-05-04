
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> vm.onBluetoothAdapterStateChanged(true)
                    BluetoothAdapter.STATE_OFF -> vm.onBluetoothAdapterStateChanged(false)
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
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
                    onReconnectBluetooth = { vm.reconnectSavedDevice { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } },
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

