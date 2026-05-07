package com.dmahony.e220chat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal suspend fun LegacyBleTransport.ensureConnectedLocked() {
    if (isConnected) return
    val address = selectedDeviceAddressProvider() ?: throw IOException("Select a nearby E220 BLE device first")
    if (!isDeviceVisibleInRecentScan(address)) {
        throw IOException("Saved BLE device is not visible in the current scan. Refresh Bluetooth devices and select the ESP32 again.")
    }

    val activeReconnectJob = reconnectJob
    if (activeReconnectJob?.isActive == true) {
        withTimeoutOrNull(LegacyBleTransport.AUTO_RECONNECT_WAIT_MS) {
            activeReconnectJob.join()
        }
        if (isConnected) return
    }

    delay(100)
    runBlockingConnect(address)
}

@SuppressLint("MissingPermission")
internal suspend fun LegacyBleTransport.runBlockingConnect(address: String) {
    val device = adapter?.getRemoteDevice(address)
        ?: throw IOException("Bluetooth LE is not available on this device")
    connectWithRetryLocked(device)
}

internal suspend fun LegacyBleTransport.connectWithRetryLocked(device: BluetoothDevice): BluetoothDeviceInfo {
    val deviceName = displayBluetoothName(device.name)
    var lastError: Exception? = null
    repeat(LegacyBleTransport.CONNECT_MAX_ATTEMPTS) { attempt ->
        if (attempt > 0) {
            appendTransportLog(TransportDirection.INFO, "Retrying BLE connect (${attempt + 1}/${LegacyBleTransport.CONNECT_MAX_ATTEMPTS})")
            delay(LegacyBleTransport.CONNECT_RETRY_BACKOFF_MS)
        }
        try {
            return connectGattOnceLocked(device, deviceName)
        } catch (e: Exception) {
            lastError = e
            closeGattLocked()
            if (isBluetoothCacheStaleError(e)) {
                throw e
            }
        }
    }
    throw IOException(lastError?.message ?: "Failed to connect to Bluetooth LE device")
}

@SuppressLint("MissingPermission")
internal suspend fun LegacyBleTransport.connectGattOnceLocked(device: BluetoothDevice, deviceName: String): BluetoothDeviceInfo {
    closeGattLocked(triggerDisconnect = false)
    delay(LegacyBleTransport.CONNECT_RETRY_DELAY_MS)
    val connectDeferred = CompletableDeferred<Unit>()
    pendingConnect = connectDeferred
    val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
    } else {
        device.connectGatt(appContext, false, callback)
    } ?: throw IOException("Failed to start BLE connection")
    bluetoothGatt = gatt
    appendTransportLog(TransportDirection.INFO, "Connecting to $deviceName")
    connectionEventListener(
        TransportConnectionEvent(
            state = TransportConnectionState.CONNECTING,
            message = "Connecting to $deviceName..."
        )
    )
    try {
        withTimeout(LegacyBleTransport.CONNECT_TIMEOUT_MS) { connectDeferred.await() }
    } catch (e: Exception) {
        closeGattLocked()
        throw IOException(e.message ?: "Failed to connect to Bluetooth LE device")
    }
    appendTransportLog(TransportDirection.INFO, "Connected to $deviceName")
    connectionEventListener(
        TransportConnectionEvent(
            state = TransportConnectionState.CONNECTED,
            message = "Connected to $deviceName"
        )
    )
    return BluetoothDeviceInfo(name = deviceName, address = device.address)
}

internal fun LegacyBleTransport.handleUnexpectedDisconnect(gatt: BluetoothGatt, status: Int) {
    synchronized(stateLock) {
        pendingResponse?.completeExceptionally(IOException("BLE disconnected"))
        pendingWrite?.completeExceptionally(IOException("BLE disconnected"))
        pendingDescriptorWrite?.completeExceptionally(IOException("BLE disconnected"))
    }
    if (pendingConnect?.isCompleted == false) {
        pendingConnect?.completeExceptionally(IOException("BLE disconnected"))
    }
    bluetoothGatt = null
    rxCharacteristic = null
    txCharacteristic = null
    try {
        gatt.close()
    } catch (_: Exception) {
    }

    val address = selectedDeviceAddressProvider()
    val shouldReconnect = !manualDisconnectRequested && !address.isNullOrBlank()
    appendTransportLog(
        TransportDirection.INFO,
        if (status == BluetoothGatt.GATT_SUCCESS) {
            "BLE disconnected"
        } else {
            "BLE disconnected ($status)"
        }
    )
    connectionEventListener(
        TransportConnectionEvent(
            state = if (shouldReconnect) TransportConnectionState.RECONNECTING else TransportConnectionState.DISCONNECTED,
            message = if (shouldReconnect) {
                "Bluetooth link lost, reconnecting..."
            } else {
                "Bluetooth disconnected"
            },
            manualDisconnect = manualDisconnectRequested
        )
    )

    if (shouldReconnect) {
        scheduleAutoReconnect(address!!)
    }
}

internal fun LegacyBleTransport.scheduleAutoReconnect(address: String) {
    if (manualDisconnectRequested) return
    if (!isDeviceVisibleInRecentScan(address)) {
        appendTransportLog(TransportDirection.INFO, "Skipping BLE auto-reconnect because $address is not visible in the current scan")
        connectionEventListener(
            TransportConnectionEvent(
                state = TransportConnectionState.DISCONNECTED,
                message = "Saved BLE device is not visible in the current scan"
            )
        )
        return
    }
    if (reconnectJob?.isActive == true) return
    reconnectJob = recoveryScope.launch {
        try {
            var attempt = 0
            while (!manualDisconnectRequested) {
                if (selectedDeviceAddressProvider() != address) return@launch
                if (!isDeviceVisibleInRecentScan(address)) {
                    appendTransportLog(TransportDirection.INFO, "Stopping BLE auto-reconnect because $address is no longer visible")
                    connectionEventListener(
                        TransportConnectionEvent(
                            state = TransportConnectionState.DISCONNECTED,
                            message = "Saved BLE device is not visible in the current scan"
                        )
                    )
                    return@launch
                }
                attempt++
                try {
                    connectionEventListener(
                        TransportConnectionEvent(
                            state = TransportConnectionState.RECONNECTING,
                            message = if (attempt == 1) {
                                "Bluetooth link lost, reconnecting..."
                            } else {
                                "Retrying Bluetooth reconnect ($attempt)"
                            }
                        )
                    )
                    runBlockingConnect(address)
                    connectionEventListener(
                        TransportConnectionEvent(
                            state = TransportConnectionState.CONNECTED,
                            message = "Reconnected to ${selectedDeviceNameProvider() ?: address}"
                        )
                    )
                    return@launch
                } catch (e: Exception) {
                    appendTransportLog(TransportDirection.INFO, "Bluetooth reconnect attempt $attempt failed: ${e.message ?: "unknown error"}")
                    if (isBluetoothCacheStaleError(e)) {
                        connectionEventListener(
                            TransportConnectionEvent(
                                state = TransportConnectionState.DISCONNECTED,
                                message = "Bluetooth cache is stale. Forget this device in Bluetooth settings, then re-pair and reconnect."
                            )
                        )
                        return@launch
                    }
                    if (manualDisconnectRequested) return@launch
                    if (attempt >= LegacyBleTransport.MAX_AUTO_RECONNECT_ATTEMPTS) {
                        connectionEventListener(
                            TransportConnectionEvent(
                                state = TransportConnectionState.DISCONNECTED,
                                message = "Bluetooth reconnect failed"
                            )
                        )
                        return@launch
                    }
                    delay(LegacyBleTransport.AUTO_RECONNECT_BACKOFF_MS * attempt)
                }
            }
        } finally {
            if (reconnectJob?.isActive == false) {
                reconnectJob = null
            }
        }
    }
}

internal fun LegacyBleTransport.isBluetoothCacheStaleError(e: Exception): Boolean {
    val message = e.message.orEmpty()
    return message.contains("status 133", ignoreCase = true) ||
        message.contains("Bluetooth cache is stale", ignoreCase = true)
}
