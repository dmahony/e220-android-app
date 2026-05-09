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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.dmahony.e220chat.ui.theme.E220ChatTheme

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

@Composable
private fun E220ChatRoot(vm: E220ChatViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showBluetoothDialog by remember { mutableStateOf(false) }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.isNotEmpty() && grants.values.all { it }) {
            showBluetoothDialog = true
            vm.refreshBluetoothDevices()
        } else {
            Toast.makeText(context, "Bluetooth permissions are required", Toast.LENGTH_LONG).show()
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

    fun openBluetoothPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missingPermissions = buildList {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            if (missingPermissions.isNotEmpty()) {
                bluetoothPermissionLauncher.launch(missingPermissions.toTypedArray())
            } else {
                showBluetoothDialog = true
                vm.refreshBluetoothDevices()
            }
        } else {
            showBluetoothDialog = true
            vm.refreshBluetoothDevices()
        }
    }

    fun requestGpsLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            scope.launch {
                sendGpsMessage(context, vm) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun clearChatMessages() {
        vm.clearChatMessages()
        Toast.makeText(context, "Chat cleared", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.12f))
        ) {
            TabRow(
                selectedTabIndex = if (vm.selectedTab == AppTab.CHAT) 0 else 1,
                modifier = Modifier.height(26.dp),
                containerColor = Color.Transparent,
                divider = {},
                indicator = { tabPositions ->
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[if (vm.selectedTab == AppTab.CHAT) 0 else 1]),
                        height = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.68f)
                    )
                }
            ) {
                AppTab.values().forEachIndexed { index, tab ->
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
                onOpenBluetooth = ::openBluetoothPicker,
                onReconnectBluetooth = ::openBluetoothPicker,
                onGpsCommand = ::requestGpsLocation,
                onClearMessages = ::clearChatMessages,
                onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            )
            AppTab.SETTINGS -> SettingsScreen(
                vm = vm,
                onRefresh = vm::refreshConfig,
                onSave = { vm.saveConfig(onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }, onSuccess = {}) },
                onQuickSave = { vm.quickSave(onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }, onSuccess = {}) },
                onRestoreDefaults = { vm.restoreDefaultRadioConfig(onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }, onSuccess = {}) },
                onReboot = { vm.reboot(onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }, onSuccess = {}) },
                modifier = Modifier.weight(1f)
            )
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
