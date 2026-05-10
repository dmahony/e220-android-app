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
import com.dmahony.e220chat.ble.ReceiptKind
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

@SuppressLint("MissingPermission")
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

@SuppressLint("MissingPermission")
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

@SuppressLint("MissingPermission")
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

@SuppressLint("MissingPermission")
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
        if (isDebuggableApp) {
            Log.d(tag, "[$prefix] $safePayload")
        }
    }

internal fun E220Repository.parseDestinationUserId(): Int {
        // Use the configured group destination so ESP32s share the same address.
        return binaryConfig?.dest ?: 0x0001
    }

internal data class BinaryChatText(
    val senderUserId24: Int,
    val messageId: Long,
    val text: String,
    val rssi: Int? = null
)

internal data class BinaryChatReceipt(
    val targetUserId24: Int,
    val messageId: Long,
    val kind: ReceiptKind
)

internal fun decodeBinaryChatText(payload: ByteArray, rssiEnabled: Boolean): BinaryChatText? {
    if (payload.size < 11) return null
    val senderUserId = ((payload[0].toInt() and 0xFF) shl 16) or
        ((payload[1].toInt() and 0xFF) shl 8) or
        (payload[2].toInt() and 0xFF)
    val messageId = java.nio.ByteBuffer.wrap(payload, 3, 8)
        .order(java.nio.ByteOrder.BIG_ENDIAN)
        .long
    val textStart = 11
    val textEnd = if (rssiEnabled && payload.size > textStart) payload.size - 1 else payload.size
    if (textEnd < textStart) return null
    val textBytes = payload.copyOfRange(textStart, textEnd)
    val text = textBytes.toString(Charsets.UTF_8)
    val rssi = if (rssiEnabled && payload.size > textStart) payload.last().toInt().toByte().toInt() else null
    return BinaryChatText(senderUserId24 = senderUserId, messageId = messageId, text = text, rssi = rssi)
}

internal fun decodeBinaryChatReceipt(payload: ByteArray): BinaryChatReceipt? {
    if (payload.size < 12) return null
    val targetUserId = ((payload[0].toInt() and 0xFF) shl 16) or
        ((payload[1].toInt() and 0xFF) shl 8) or
        (payload[2].toInt() and 0xFF)
    val messageId = java.nio.ByteBuffer.wrap(payload, 3, 8)
        .order(java.nio.ByteOrder.BIG_ENDIAN)
        .long
    val kind = ReceiptKind.from((payload[11].toInt() and 0xFF).toUByte()) ?: return null
    return BinaryChatReceipt(targetUserId24 = targetUserId, messageId = messageId, kind = kind)
}

private fun Long.formatBinaryMessageId(): String = java.lang.Long.toUnsignedString(this, 16).padStart(16, '0')

private fun E220Repository.markBinaryChatDirty() {
    synchronized(binaryChatMessages) {
        binaryChatSequence += 1
        binaryChatReset = true
    }
}

private fun E220Repository.upsertBinaryMessage(messageId: String, updater: (ChatMessage?) -> ChatMessage): ChatMessage {
    synchronized(binaryChatMessages) {
        val index = binaryChatMessages.indexOfFirst { it.messageId == messageId }
        val current = if (index >= 0) binaryChatMessages[index] else null
        val updated = updater(current)
        if (index >= 0) {
            binaryChatMessages[index] = updated
        } else {
            binaryChatMessages.add(updated)
        }
        binaryChatSequence += 1
        binaryChatReset = true
        return updated
    }
}

internal suspend fun E220Repository.markBinaryMessagesRead(visible: Boolean) {
    if (!useBinaryTransport || !visible) return
    val myUserId = binaryConfig?.userId24 ?: return
    val unread = synchronized(binaryChatMessages) {
        binaryChatMessages.filter { !it.sent && !it.read && it.deliveryStatus == DeliveryStatus.DELIVERED }
    }
    for (message in unread) {
        val targetUserId = message.senderUserId24 ?: myUserId
        runCatching { bleV2.sendReceipt(targetUserId, message.messageId.toLongUnsignedHex(), ReceiptKind.READ) }
    }
    if (unread.isNotEmpty()) {
        synchronized(binaryChatMessages) {
            unread.forEach { target ->
                val idx = binaryChatMessages.indexOfFirst { it.messageId == target.messageId }
                if (idx >= 0) {
                    binaryChatMessages[idx] = binaryChatMessages[idx].copy(read = true, deliveryStatus = DeliveryStatus.READ)
                }
            }
            binaryChatSequence += 1
            binaryChatReset = true
        }
    }
}

private fun String.toLongUnsignedHex(): Long = java.lang.Long.parseUnsignedLong(this, 16)

internal fun E220Repository.handleBinaryFrame(frame: BleFrame) {
    when (frame.type) {
        MsgType.TEXT -> {
            val parsed = decodeBinaryChatText(frame.payload, binaryConfig?.rssiByte == 1) ?: return
            val myUserId = binaryConfig?.userId24
            val isMine = myUserId != null && parsed.senderUserId24 == myUserId
            val messageId = parsed.messageId.formatBinaryMessageId()
            val displayText = if (isMine) parsed.text else "[RX ${parsed.senderUserId24.toString(16).padStart(6, '0')}] ${parsed.text}"
            val senderName = if (isMine) {
                binaryConfig?.username.orEmpty()
            } else {
                "u${parsed.senderUserId24.toString(16).padStart(6, '0')}"
            }
            upsertBinaryMessage(messageId) { current ->
                if (current != null) {
                    val existing = current
                    existing.copy(
                        text = existing.text.ifBlank { displayText },
                        sent = isMine || existing.sent,
                        delivered = existing.delivered || !isMine,
                        senderName = senderName,
                        senderUserId24 = parsed.senderUserId24,
                        read = existing.read,
                        messageId = messageId,
                        deliveryStatus = when {
                            existing.deliveryStatus == DeliveryStatus.READ -> DeliveryStatus.READ
                            isMine -> existing.deliveryStatus
                            else -> DeliveryStatus.DELIVERED
                        },
                        rssi = parsed.rssi ?: existing.rssi
                    )
                } else {
                    ChatMessage(
                        text = displayText,
                        sent = isMine,
                        delivered = isMine,
                        senderName = senderName,
                        senderUserId24 = parsed.senderUserId24,
                        read = false,
                        messageId = messageId,
                        deliveryStatus = if (isMine) DeliveryStatus.SENT else DeliveryStatus.DELIVERED,
                        rssi = parsed.rssi
                    )
                }
            }
            appendTransportLog(
                TransportDirection.RECEIVED,
                "TEXT src=${parsed.senderUserId24.toString(16).padStart(6, '0')} msg=${messageId} len=${parsed.text.length}${parsed.rssi?.let { " rssi=$it" } ?: ""}"
            )
            if (!isMine) {
                recoveryScope.launch {
                    runCatching { bleV2.sendReceipt(parsed.senderUserId24, parsed.messageId, ReceiptKind.DELIVERED) }
                }
            }
        }

        MsgType.RECEIPT -> {
            val parsed = decodeBinaryChatReceipt(frame.payload) ?: return
            val myUserId = binaryConfig?.userId24 ?: return
            if (parsed.targetUserId24 != myUserId) return
            val messageId = parsed.messageId.formatBinaryMessageId()
            val newStatus = when (parsed.kind) {
                ReceiptKind.DELIVERED -> DeliveryStatus.DELIVERED
                ReceiptKind.READ -> DeliveryStatus.READ
            }
            synchronized(binaryChatMessages) {
                val idx = binaryChatMessages.indexOfFirst { it.messageId == messageId }
                if (idx >= 0) {
                    val existing = binaryChatMessages[idx]
                    binaryChatMessages[idx] = existing.copy(
                        delivered = existing.delivered || parsed.kind == ReceiptKind.DELIVERED,
                        read = existing.read || parsed.kind == ReceiptKind.READ,
                        deliveryStatus = newStatus
                    )
                    binaryChatSequence += 1
                    binaryChatReset = true
                }
            }
            appendTransportLog(
                TransportDirection.RECEIVED,
                "RECEIPT target=${parsed.targetUserId24.toString(16).padStart(6, '0')} msg=$messageId kind=${parsed.kind.name}"
            )
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
                android.util.Log.d("E220Status", "flow=${st.flowState} rssi=${st.lastRssi} qBRx=${st.qBleRx} qRTx=${st.qRadioTx} qRRx=${st.qRadioRx} qBTx=${st.qBleTx} devId=${st.deviceId24.toString(16)}")
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

