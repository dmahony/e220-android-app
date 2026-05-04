package com.dmahony.e220chat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dmahony.e220chat.ble.BleConfig
import com.dmahony.e220chat.ble.BleFrame
import com.dmahony.e220chat.ble.MsgType
import com.dmahony.e220chat.ble.StatusTelemetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal fun displayBluetoothName(name: String?): String = name?.takeIf { it.isNotBlank() } ?: "Unnamed device"

internal suspend fun E220Repository.exchange(request: String): String = withContext(Dispatchers.IO) {
    exchangeMutex.withLock {
            retryTransportFailure(
                block = {
                    ensureConnectedLocked()
                    executeExchangeLocked(request)
                },
                onRetry = {
                    appendTransportLog(TransportDirection.INFO, "BLE link stale, reconnecting")
                    connectionEventListener?.invoke(
                        TransportConnectionEvent(
                            state = TransportConnectionState.RECONNECTING,
                            message = "Bluetooth link lost, reconnecting..."
                        )
                    )
                    closeGattLocked()
                    val address = selectedDeviceAddress ?: throw ApiException("Select a nearby E220 BLE device first")
                    reconnectJob?.cancel()
                    reconnectJob = null
                    runBlockingConnect(address)
                }
            )
        }
    }

internal suspend fun E220Repository.executeExchangeLocked(request: String): String {
        appendTransportLog(TransportDirection.SENT, request)
        val line = writeRequestAndAwaitResponseLocked(request)
        appendTransportLog(TransportDirection.RECEIVED, line)
        return line
    }

internal suspend fun E220Repository.writeRequestAndAwaitResponseLocked(requestText: String): String {
        val gatt = bluetoothGatt ?: throw IOException("BLE GATT not connected")
        val characteristic = rxCharacteristic ?: throw IOException("BLE write characteristic not ready")
        val responseDeferred = CompletableDeferred<String>()
        synchronized(stateLock) {
            responseBuffer = StringBuilder()
            pendingResponse = responseDeferred
        }
        try {
            val payload = (requestText + "\n").toByteArray(Charsets.UTF_8)
            val chunkSize = 20
            var offset = 0
            while (offset < payload.size) {
                val end = minOf(offset + chunkSize, payload.size)
                val chunk = payload.copyOfRange(offset, end)
                val writeDeferred = CompletableDeferred<Unit>()
                pendingWrite = writeDeferred
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (!characteristic.setValue(chunk)) {
                    throw IOException("Failed to stage BLE request chunk")
                }
                if (!gatt.writeCharacteristic(characteristic)) {
                    throw IOException("Failed to write BLE request chunk")
                }
                withTimeout(E220Repository.RESPONSE_TIMEOUT_MS) { writeDeferred.await() }
                offset = end
            }
            return withTimeout(E220Repository.RESPONSE_TIMEOUT_MS) { responseDeferred.await() }
        } finally {
            synchronized(stateLock) {
                if (pendingResponse === responseDeferred) pendingResponse = null
            }
        }
    }

internal suspend fun E220Repository.ensureConnectedLocked() {
        if (isConnected) return
        val address = selectedDeviceAddress ?: throw ApiException("Select a nearby E220 BLE device first")
        if (!bleScanner.isDeviceVisibleInRecentScan(address)) {
            throw ApiException("Saved BLE device is not visible in the current scan. Refresh Bluetooth devices and select the ESP32 again.")
        }

        val activeReconnectJob = reconnectJob
        if (activeReconnectJob?.isActive == true) {
            withTimeoutOrNull(E220Repository.AUTO_RECONNECT_WAIT_MS) {
                activeReconnectJob.join()
            }
            if (isConnected) return
        }

        // Avoid rapid reconnect loops: check if we just disconnected
        delay(100)
        runBlockingConnect(address)
    }

@SuppressLint("MissingPermission")
internal suspend fun E220Repository.runBlockingConnect(address: String) {
        val device = adapter?.getRemoteDevice(address)
            ?: throw ApiException("Bluetooth LE is not available on this device")
        connectWithRetryLocked(device)
    }

internal suspend fun E220Repository.connectWithRetryLocked(device: BluetoothDevice): BluetoothDeviceInfo {
        val deviceName = displayBluetoothName(device.name)
        var lastError: Exception? = null
        repeat(E220Repository.CONNECT_MAX_ATTEMPTS) { attempt ->
            stopBleScan()
            if (attempt > 0) {
                appendTransportLog(TransportDirection.INFO, "Retrying BLE connect (${attempt + 1}/${E220Repository.CONNECT_MAX_ATTEMPTS})")
                delay(E220Repository.CONNECT_RETRY_BACKOFF_MS)
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
        throw ApiException(lastError?.message ?: "Failed to connect to Bluetooth LE device")
    }

@SuppressLint("MissingPermission")
internal suspend fun E220Repository.connectGattOnceLocked(device: BluetoothDevice, deviceName: String): BluetoothDeviceInfo {
        closeGattLocked(triggerDisconnect = false)
        delay(E220Repository.CONNECT_RETRY_DELAY_MS)
        val connectDeferred = CompletableDeferred<Unit>()
        pendingConnect = connectDeferred
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        } ?: throw ApiException("Failed to start BLE connection")
        bluetoothGatt = gatt
        appendTransportLog(TransportDirection.INFO, "Connecting to $deviceName")
        connectionEventListener?.invoke(
            TransportConnectionEvent(
                state = TransportConnectionState.CONNECTING,
                message = "Connecting to $deviceName..."
            )
        )
        try {
            withTimeout(E220Repository.CONNECT_TIMEOUT_MS) { connectDeferred.await() }
        } catch (e: Exception) {
            closeGattLocked()
            throw ApiException(e.message ?: "Failed to connect to Bluetooth LE device")
        }
        appendTransportLog(TransportDirection.INFO, "Connected to $deviceName")
        connectionEventListener?.invoke(
            TransportConnectionEvent(
                state = TransportConnectionState.CONNECTED,
                message = "Connected to $deviceName"
            )
        )
        return BluetoothDeviceInfo(name = deviceName, address = device.address)
    }

internal fun E220Repository.closeGattLocked(triggerDisconnect: Boolean = true) {
        synchronized(stateLock) {
            pendingConnect?.cancel()
            pendingWrite?.cancel()
            pendingDescriptorWrite?.cancel()
            pendingResponse?.cancel()
            pendingConnect = null
            pendingWrite = null
            pendingDescriptorWrite = null
            pendingResponse = null
            responseBuffer = StringBuilder()
        }
        val currentGatt = bluetoothGatt
        try {
            if (triggerDisconnect) {
                refreshGattCache()
            }
        } catch (_: Exception) {
        }
        try {
            if (triggerDisconnect) {
                currentGatt?.disconnect()
            }
        } catch (_: Exception) {
        }
        try {
            currentGatt?.close()
        } catch (_: Exception) {
        }
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
    }

internal fun E220Repository.refreshGattCache(): Boolean {
        val gatt = bluetoothGatt ?: return false
        return try {
            val refresh = gatt.javaClass.getMethod("refresh")
            refresh.isAccessible = true
            refresh.invoke(gatt) as Boolean
        } catch (_: Exception) {
            false
        }
    }

internal fun E220Repository.handleUnexpectedDisconnect(gatt: BluetoothGatt, status: Int) {
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

        val address = selectedDeviceAddress
        val shouldReconnect = !manualDisconnectRequested && !address.isNullOrBlank()
        appendTransportLog(
            TransportDirection.INFO,
            if (status == BluetoothGatt.GATT_SUCCESS) {
                "BLE disconnected"
            } else {
                "BLE disconnected ($status)"
            }
        )
        connectionEventListener?.invoke(
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

internal fun E220Repository.scheduleAutoReconnect(address: String) {
        if (manualDisconnectRequested) return
        if (!bleScanner.isDeviceVisibleInRecentScan(address)) {
            appendTransportLog(TransportDirection.INFO, "Skipping BLE auto-reconnect because $address is not visible in the current scan")
            connectionEventListener?.invoke(
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
                    if (selectedDeviceAddress != address) return@launch
                    if (!bleScanner.isDeviceVisibleInRecentScan(address)) {
                        appendTransportLog(TransportDirection.INFO, "Stopping BLE auto-reconnect because $address is no longer visible")
                        connectionEventListener?.invoke(
                            TransportConnectionEvent(
                                state = TransportConnectionState.DISCONNECTED,
                                message = "Saved BLE device is not visible in the current scan"
                            )
                        )
                        return@launch
                    }
                    attempt++
                    try {
                        connectionEventListener?.invoke(
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
                        connectionEventListener?.invoke(
                            TransportConnectionEvent(
                                state = TransportConnectionState.CONNECTED,
                                message = "Reconnected to ${selectedDeviceName ?: address}"
                            )
                        )
                        return@launch
                    } catch (e: Exception) {
                        appendTransportLog(TransportDirection.INFO, "Bluetooth reconnect attempt $attempt failed: ${e.message ?: "unknown error"}")
                        if (isBluetoothCacheStaleError(e)) {
                            connectionEventListener?.invoke(
                                TransportConnectionEvent(
                                    state = TransportConnectionState.DISCONNECTED,
                                    message = "Bluetooth cache is stale. Forget this device in Bluetooth settings, then re-pair and reconnect."
                                )
                            )
                            return@launch
                        }
                        if (manualDisconnectRequested) return@launch
                        if (attempt >= E220Repository.MAX_AUTO_RECONNECT_ATTEMPTS) {
                            connectionEventListener?.invoke(
                                TransportConnectionEvent(
                                    state = TransportConnectionState.DISCONNECTED,
                                    message = "Bluetooth reconnect failed"
                                )
                            )
                            return@launch
                        }
                        delay(E220Repository.AUTO_RECONNECT_BACKOFF_MS * attempt)
                    }
                }
            } finally {
                if (reconnectJob?.isActive == false) {
                    reconnectJob = null
                }
            }
        }
    }

internal fun E220Repository.isBluetoothCacheStaleError(e: Exception): Boolean {
        val message = e.message.orEmpty()
        return message.contains("status 133", ignoreCase = true) ||
            message.contains("Bluetooth cache is stale", ignoreCase = true)
    }

internal fun E220Repository.redactBluetoothAddress(value: String): String {
        val parts = value.split(":")
        return if (parts.size == 6 && parts.all { it.length == 2 }) {
            parts.take(3).joinToString(":") + ":**:**:**"
        } else {
            value
        }
    }

internal fun E220Repository.redactSensitiveFields(payload: String): String {
        fun redactField(input: String, fieldName: String): String =
            input.replace(Regex("""(?i)(\"$fieldName\"\s*:\s*\")[^\"]*(\")"""), "$1<redacted>$2")

        var sanitized = payload
        for (field in listOf("password", "wifi_ap_password", "wifi_sta_password")) {
            sanitized = redactField(sanitized, field)
        }
        if (!isDebuggableApp) {
            for (field in listOf("message", "ssid", "wifi_ap_ssid", "wifi_sta_ssid")) {
                sanitized = redactField(sanitized, field)
            }
        }
        sanitized = sanitized.replace(Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")) { matchResult ->
            redactBluetoothAddress(matchResult.value)
        }
        return sanitized
    }

internal fun E220Repository.appendTransportLog(direction: TransportDirection, payload: String) {
        val safePayload = redactSensitiveFields(payload)
        transportLogs = (transportLogs + TransportLogEntry(direction = direction, payload = safePayload)).takeLast(E220Repository.MAX_TRANSPORT_LOGS)
        val prefix = when (direction) {
            TransportDirection.SENT -> "APP -> ESP32"
            TransportDirection.RECEIVED -> "ESP32 -> APP"
            TransportDirection.INFO -> "INFO"
        }
        if (isDebuggableApp || direction == TransportDirection.INFO) {
            Log.d(tag, "[$prefix] $safePayload")
        }
    }

internal fun E220Repository.parseDestinationUserId(): Int {
        val cfg = binaryConfig ?: E220ConfigMapper.defaultBinaryConfig(selectedDeviceAddress)
        return cfg.userId24
    }

internal fun E220Repository.handleBinaryFrame(frame: BleFrame) {
        when (frame.type) {
            MsgType.TEXT -> {
                if (frame.payload.size >= 3) {
                    val userId = ((frame.payload[0].toInt() and 0xFF) shl 16) or
                        ((frame.payload[1].toInt() and 0xFF) shl 8) or
                        (frame.payload[2].toInt() and 0xFF)
                    val text = frame.payload.copyOfRange(3, frame.payload.size).toString(Charsets.UTF_8)
                    synchronized(binaryChatMessages) {
                        binaryChatMessages.add(ChatMessage(text = "[RX ${userId.toString(16).padStart(6, '0')}] $text", sent = false, delivered = true))
                        binaryChatSequence = binaryChatMessages.size
                    }
                    appendTransportLog(TransportDirection.RECEIVED, "TEXT src=${userId.toString(16).padStart(6, '0')} len=${text.length}")
                }
            }
            MsgType.PROFILE -> {
                appendTransportLog(TransportDirection.RECEIVED, "PROFILE len=${frame.payload.size}")
            }
            MsgType.CONFIG -> {
                runCatching { BleConfig.fromPayload(frame.payload) }.onSuccess { cfg ->
                    binaryConfig = cfg
                    appendTransportLog(TransportDirection.RECEIVED, "CONFIG ackTimeout=${cfg.ackTimeoutMs} retries=${cfg.maxRetries}")
                }
            }
            MsgType.ERROR -> {
                val code = frame.payload.getOrNull(0)?.toInt()?.and(0xFF) ?: -1
                val origin = frame.payload.getOrNull(1)?.toInt()?.and(0xFF) ?: -1
                appendTransportLog(TransportDirection.INFO, "BLE error code=$code originType=$origin")
            }
            MsgType.STATUS -> {
                runCatching { StatusTelemetry.fromPayload(frame.payload) }.onSuccess { st ->
                    binaryStatus = st
                }
            }
            MsgType.ACK, MsgType.WHOIS -> Unit
        }
    }

internal fun E220Repository.handleIncomingChunk(chunk: String) {
        val completeLine: String? = synchronized(stateLock) {
            if (pendingResponse == null) return
            responseBuffer.append(chunk)
            val buffer = responseBuffer.toString()
            val newlineIndex = buffer.indexOf('\n')
            if (newlineIndex >= 0) {
                val line = buffer.substring(0, newlineIndex).trimEnd('\r')
                responseBuffer = StringBuilder(buffer.substring(newlineIndex + 1))
                line
            } else {
                null
            }
        }
        if (completeLine != null) {
            pendingResponse?.let { deferred ->
                if (!deferred.isCompleted) deferred.complete(completeLine)
            }
        }
    }

