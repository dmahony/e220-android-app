package com.dmahony.e220chat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun E220Repository.getPairedDevices(): List<BluetoothDeviceInfo> = bleScanner.getPairedDevices()

internal fun E220Repository.getTransportLogs(): List<TransportLogEntry> = transportLogs

internal fun E220Repository.hasBluetoothConnectPermission(): Boolean = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
    else ->
        true
}

@SuppressLint("MissingPermission")
internal suspend fun E220Repository.scanBleDevices(scanMillis: Long = 20000L): List<BluetoothDeviceInfo> = bleScanner.scanBleDevices(scanMillis)

internal fun E220Repository.stopBleScan() = bleScanner.stopBleScan()

@SuppressLint("MissingPermission")
internal suspend fun E220Repository.connect(address: String): BluetoothDeviceInfo = withContext(Dispatchers.IO) {
    if (!isHardenedBleSupported()) {
        throw ApiException(hardenedBleUnsupportedMessage())
    }

    if (useBinaryTransport) {
        if (!hasBluetoothConnectPermission()) {
            throw ApiException("Grant Bluetooth permissions first")
        }
        val device = adapter?.getRemoteDevice(address)
            ?: throw ApiException("Bluetooth LE is not available on this device")
        val name = displayBluetoothName(device.name)
        selectedDeviceAddress = address
        selectedDeviceName = name
        manualDisconnectRequested = false
        appendTransportLog(TransportDirection.INFO, "Connecting to $name")
        connectionEventListener?.invoke(
            TransportConnectionEvent(
                state = TransportConnectionState.CONNECTING,
                message = "Connecting to $name..."
            )
        )
        bleV2.connect(address)
        appendTransportLog(TransportDirection.INFO, "Connected to $name")
        connectionEventListener?.invoke(
            TransportConnectionEvent(
                state = TransportConnectionState.CONNECTED,
                message = "Connected to $name"
            )
        )
        runCatching { bleV2.requestWhois() }
        runCatching { binaryConfig = bleV2.readConfigCharacteristic() }.onFailure { e ->
            appendTransportLog(TransportDirection.INFO, "Config read via GATT failed (will use frame-based config): ${e.message}")
        }
        return@withContext BluetoothDeviceInfo(name = name, address = address)
    }

    exchangeMutex.withLock {
        manualDisconnectRequested = false
        reconnectJob?.cancel()
        reconnectJob = null
        if (!hasBluetoothConnectPermission()) {
            throw ApiException("Grant Bluetooth permissions first")
        }
        val device = adapter?.getRemoteDevice(address)
            ?: throw ApiException("Bluetooth LE is not available on this device")
        selectedDeviceAddress = address
        selectedDeviceName = device.name ?: device.address
        connectWithRetryLocked(device)
    }
}

internal suspend fun E220Repository.disconnect() = withContext(Dispatchers.IO) {
    if (useBinaryTransport) {
        manualDisconnectRequested = true
        bleV2.disconnect()
        appendTransportLog(TransportDirection.INFO, "Disconnected")
        connectionEventListener?.invoke(
            TransportConnectionEvent(
                state = TransportConnectionState.DISCONNECTED,
                message = "Bluetooth disconnected",
                manualDisconnect = true
            )
        )
        return@withContext
    }

    exchangeMutex.withLock {
        manualDisconnectRequested = true
        reconnectJob?.cancel()
        reconnectJob = null
        appendTransportLog(TransportDirection.INFO, "Disconnected")
        closeGattLocked()
        connectionEventListener?.invoke(
            TransportConnectionEvent(
                state = TransportConnectionState.DISCONNECTED,
                message = "Bluetooth disconnected",
                manualDisconnect = true
            )
        )
    }
}

internal fun E220Repository.dispose() {
    if (useBinaryTransport) {
        bleV2.dispose()
    } else {
        runBlocking(Dispatchers.IO) {
            disconnect()
        }
        bleV2.dispose()
    }
}
