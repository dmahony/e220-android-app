package com.dmahony.e220chat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

internal class LegacyBleTransport(
    context: Context,
    internal val bluetoothAdapter: BluetoothAdapter?,
    private val tag: String,
    private val isDebuggableApp: Boolean,
    internal val selectedDeviceAddressProvider: () -> String?,
    internal val selectedDeviceNameProvider: () -> String?,
    internal val isDeviceVisibleInRecentScan: (String) -> Boolean,
    internal val connectionEventListener: (TransportConnectionEvent) -> Unit,
) {
    internal val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    internal val adapter: BluetoothAdapter? = bluetoothAdapter ?: bluetoothManager.adapter
    internal val exchangeMutex = Mutex()
    internal val stateLock = Any()
    internal val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal var bluetoothGatt: BluetoothGatt? = null
    internal var rxCharacteristic: BluetoothGattCharacteristic? = null
    internal var txCharacteristic: BluetoothGattCharacteristic? = null
    internal var pendingConnect: CompletableDeferred<Unit>? = null
    internal var pendingWrite: CompletableDeferred<Unit>? = null
    internal var pendingDescriptorWrite: CompletableDeferred<Unit>? = null
    internal var pendingResponse: CompletableDeferred<String>? = null
    internal var responseBuffer = StringBuilder()
    internal var transportLogs: List<TransportLogEntry> = emptyList()
    internal var reconnectJob: Job? = null
    internal var manualDisconnectRequested = false

    val isConnected: Boolean
        get() = bluetoothGatt != null && rxCharacteristic != null && txCharacteristic != null

    fun getTransportLogs(): List<TransportLogEntry> = transportLogs

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String): BluetoothDeviceInfo = withContext(Dispatchers.IO) {
        val device = adapter?.getRemoteDevice(address) ?: throw IOException("No BLE adapter/device")
        val connectDeferred = CompletableDeferred<Unit>()
        val descriptorDeferred = CompletableDeferred<Unit>()

        exchangeMutex.withLock {
            reconnectJob?.cancel()
            closeGattLocked()
            manualDisconnectRequested = false
            pendingConnect = connectDeferred
            pendingDescriptorWrite = descriptorDeferred
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, callback)
            }
        }

        withTimeout(CONNECT_TIMEOUT_MS) {
            connectDeferred.await()
            descriptorDeferred.await()
        }
        BluetoothDeviceInfo(name = displayBluetoothName(device.name), address = address)
    }

    @SuppressLint("MissingPermission")
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        exchangeMutex.withLock {
            reconnectJob?.cancel()
            reconnectJob = null
            manualDisconnectRequested = true
            closeGattLocked()
        }
    }

    fun dispose() {
        reconnectJob?.cancel()
        reconnectJob = null
        manualDisconnectRequested = true
        closeGattLocked()
        recoveryScope.cancel()
    }

    suspend fun exchange(request: String): String = withContext(Dispatchers.IO) {
        exchangeMutex.withLock {
            retryTransportFailure(
                block = {
                    ensureConnectedLocked()
                    executeExchangeLocked(request)
                },
                onRetry = {
                    appendTransportLog(TransportDirection.INFO, "BLE link stale, reconnecting")
                    connectionEventListener(
                        TransportConnectionEvent(
                            state = TransportConnectionState.RECONNECTING,
                            message = "Bluetooth link lost, reconnecting..."
                        )
                    )
                    closeGattLocked()
                    val address = selectedDeviceAddressProvider() ?: throw IOException("Select a nearby E220 BLE device first")
                    reconnectJob?.cancel()
                    reconnectJob = null
                    runBlockingConnect(address)
                }
            )
        }
    }

    private suspend fun executeExchangeLocked(request: String): String {
        appendTransportLog(TransportDirection.SENT, request)
        val line = writeRequestAndAwaitResponseLocked(request)
        appendTransportLog(TransportDirection.RECEIVED, line)
        return line
    }

    private suspend fun writeRequestAndAwaitResponseLocked(requestText: String): String {
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
                @Suppress("DEPRECATION")
                if (!characteristic.setValue(chunk)) {
                    throw IOException("Failed to stage BLE request chunk")
                }
                if (!gatt.writeCharacteristic(characteristic)) {
                    throw IOException("Failed to write BLE request chunk")
                }
                withTimeout(RESPONSE_TIMEOUT_MS) { writeDeferred.await() }
                offset = end
            }
            return withTimeout(RESPONSE_TIMEOUT_MS) { responseDeferred.await() }
        } finally {
            synchronized(stateLock) {
                if (pendingResponse === responseDeferred) pendingResponse = null
            }
        }
    }

    internal fun closeGattLocked(triggerDisconnect: Boolean = true) {
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

    internal fun refreshGattCache(): Boolean {
        val gatt = bluetoothGatt ?: return false
        return try {
            val refresh = gatt.javaClass.getMethod("refresh")
            refresh.isAccessible = true
            refresh.invoke(gatt) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    internal fun redactBluetoothAddress(value: String): String {
        val parts = value.split(":")
        return if (parts.size == 6 && parts.all { it.length == 2 }) {
            parts.take(3).joinToString(":") + ":**:**:**"
        } else {
            value
        }
    }

    internal fun redactSensitiveFields(payload: String): String {
        fun redactField(input: String, fieldName: String): String =
            input.replace(Regex("(?i)(\\\"$fieldName\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")"), "$1<redacted>$2")

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

    internal fun appendTransportLog(direction: TransportDirection, payload: String) {
        val safePayload = redactSensitiveFields(payload)
        transportLogs = (transportLogs + TransportLogEntry(direction = direction, payload = safePayload)).takeLast(MAX_TRANSPORT_LOGS)
        val prefix = when (direction) {
            TransportDirection.SENT -> "APP -> ESP32"
            TransportDirection.RECEIVED -> "ESP32 -> APP"
            TransportDirection.INFO -> "INFO"
        }
        if (isDebuggableApp) {
            android.util.Log.d(tag, "[$prefix] $safePayload")
        }
    }

    internal fun handleIncomingChunk(chunk: String) {
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

    internal val callback = LegacyBleGattCallback(this)

    companion object {
        internal const val MAX_TRANSPORT_LOGS = 200
        internal const val RESPONSE_TIMEOUT_MS = 30000L
        internal const val CONNECT_TIMEOUT_MS = 20000L
        internal const val CONNECT_RETRY_DELAY_MS = 250L
        internal const val CONNECT_MAX_ATTEMPTS = 2
        internal const val CONNECT_RETRY_BACKOFF_MS = 600L
        internal const val AUTO_RECONNECT_WAIT_MS = 10000L
        internal const val AUTO_RECONNECT_BACKOFF_MS = 1200L
        internal const val MAX_AUTO_RECONNECT_ATTEMPTS = 5
        internal val NUS_SERVICE_UUID: UUID = UUID.fromString("9f6d0001-6f52-4d94-b43f-2ef6f3ed7a10")
        internal val NUS_RX_UUID: UUID = UUID.fromString("9f6d0002-6f52-4d94-b43f-2ef6f3ed7a10")
        internal val NUS_TX_UUID: UUID = UUID.fromString("9f6d0003-6f52-4d94-b43f-2ef6f3ed7a10")
        internal val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
