package com.dmahony.e220chat

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

suspend fun <T> retryTransportFailure(
    block: suspend () -> T,
    onRetry: suspend () -> Unit
): T {
    return try {
        block()
    } catch (e: Exception) {
        if (e is IOException || e.message?.contains("socket", ignoreCase = true) == true) {
            onRetry()
            block()
        } else {
            throw e
        }
    }
}

class ApiException(message: String) : Exception(message)

class E220Repository(context: Context) {
    private val prefs = context.getSharedPreferences("e220_chat_prefs", Context.MODE_PRIVATE)
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val mutex = Mutex()

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var transportLogs: List<TransportLogEntry> = emptyList()
    private val tag = "E220ChatRepo"

    var darkTheme: Boolean
        get() = prefs.getBoolean(KEY_DARK_THEME, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DARK_THEME, value).apply()
        }

    var selectedDeviceAddress: String?
        get() = prefs.getString(KEY_BT_DEVICE_ADDRESS, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_BT_DEVICE_ADDRESS) else putString(KEY_BT_DEVICE_ADDRESS, value)
            }.apply()
        }

    var selectedDeviceName: String?
        get() = prefs.getString(KEY_BT_DEVICE_NAME, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_BT_DEVICE_NAME) else putString(KEY_BT_DEVICE_NAME, value)
            }.apply()
        }

    val isConnected: Boolean
        get() = socket?.isConnected == true

    fun getPairedDevices(): List<BluetoothDeviceInfo> {
        val bonded = try {
            adapter?.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }
        return bonded
            .map { BluetoothDeviceInfo(name = it.name ?: it.address, address = it.address) }
            .sortedWith(compareBy<BluetoothDeviceInfo> { it.name.lowercase() }.thenBy { it.address })
    }

    fun getTransportLogs(): List<TransportLogEntry> = transportLogs

    suspend fun connect(address: String): BluetoothDeviceInfo = withContext(Dispatchers.IO) {
        mutex.withLock {
            val device = adapter?.getRemoteDevice(address)
                ?: throw ApiException("Bluetooth is not available on this device")

            closeSocketLocked()
            adapter.cancelDiscovery()

            val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                newSocket.connect()
            } catch (e: IOException) {
                closeQuietly(newSocket)
                Log.e(tag, "Bluetooth connect failed to $address", e)
                throw ApiException(e.message ?: "Failed to connect to Bluetooth device")
            }

            socket = newSocket
            inputStream = newSocket.inputStream
            outputStream = newSocket.outputStream
            selectedDeviceAddress = address
            selectedDeviceName = device.name ?: device.address
            appendTransportLog(TransportDirection.INFO, "Connected to ${selectedDeviceName ?: address}")
            Log.i(tag, "Connected to ${selectedDeviceName ?: address}")
            BluetoothDeviceInfo(name = selectedDeviceName ?: device.address, address = address)
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            appendTransportLog(TransportDirection.INFO, "Disconnected")
            closeSocketLocked()
        }
    }

    suspend fun getChat(): ChatSnapshot = E220Protocol.parseChatResponse(exchange(E220Protocol.buildChatRequest()))

    suspend fun sendMessage(message: String): String {
        val response = exchange(E220Protocol.buildSendRequest(message))
        return E220Protocol.parseSendAcknowledgement(response)
    }

    suspend fun getConfig(): E220Config = E220Protocol.parseConfigResponse(exchange(E220Protocol.buildConfigGetRequest()))

    suspend fun saveConfig(config: E220Config): E220Config = E220Protocol.parseConfigResponse(exchange(E220Protocol.buildConfigRequest(config)))

    suspend fun getOperation(): OperationStatus = E220Protocol.parseOperationResponse(exchange(E220Protocol.buildOperationRequest()))

    suspend fun reboot() {
        exchange(E220Protocol.buildRebootRequest())
    }

    suspend fun getDiagnostics(): Diagnostics = E220Protocol.parseDiagnosticsResponse(exchange(E220Protocol.buildDiagnosticsRequest()))

    suspend fun getDebug(): String = E220Protocol.parseDebugLog(exchange(E220Protocol.buildDebugRequest()))

    suspend fun clearDebug() {
        exchange(E220Protocol.buildDebugClearRequest())
    }

    private suspend fun exchange(request: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            retryTransportFailure(
                block = {
                    ensureConnectedLocked()
                    executeExchangeLocked(request)
                },
                onRetry = {
                    appendTransportLog(TransportDirection.INFO, "Bluetooth link stale, reconnecting")
                    closeSocketLocked()
                    val address = selectedDeviceAddress ?: throw ApiException("Select a paired Bluetooth device first")
                    runBlockingConnect(address)
                }
            )
        }
    }

    private fun executeExchangeLocked(request: JSONObject): JSONObject {
        val requestText = request.toString()
        val bytes = (requestText + "\n").toByteArray(Charsets.UTF_8)
        val out = outputStream ?: throw IOException("Bluetooth output stream not available")
        out.write(bytes)
        out.flush()
        appendTransportLog(TransportDirection.SENT, requestText)

        val line = readLineWithTimeout(inputStream ?: throw IOException("Bluetooth input stream not available"), RESPONSE_TIMEOUT_MS)
        appendTransportLog(TransportDirection.RECEIVED, line)

        try {
            return E220Protocol.parseEnvelope(line)
        } catch (e: Exception) {
            throw ApiException("Invalid response from ESP32: ${e.message ?: line}")
        }
    }

    private fun ensureConnectedLocked() {
        if (socket?.isConnected == true) return
        val address = selectedDeviceAddress ?: throw ApiException("Select a paired Bluetooth device first")
        runBlockingConnect(address)
    }

    private fun runBlockingConnect(address: String) {
        val device = adapter?.getRemoteDevice(address)
            ?: throw ApiException("Bluetooth is not available on this device")
        adapter.cancelDiscovery()
        val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            newSocket.connect()
        } catch (e: IOException) {
            closeQuietly(newSocket)
            Log.e(tag, "Bluetooth reconnect failed to $address", e)
            throw ApiException(e.message ?: "Failed to connect to Bluetooth device")
        }
        socket = newSocket
        inputStream = newSocket.inputStream
        outputStream = newSocket.outputStream
        selectedDeviceName = device.name ?: device.address
        appendTransportLog(TransportDirection.INFO, "Reconnected to ${selectedDeviceName ?: address}")
        Log.i(tag, "Reconnected to ${selectedDeviceName ?: address}")
    }

    private fun appendTransportLog(direction: TransportDirection, payload: String) {
        transportLogs = (transportLogs + TransportLogEntry(direction = direction, payload = payload)).takeLast(MAX_TRANSPORT_LOGS)
        val prefix = when (direction) {
            TransportDirection.SENT -> "APP -> ESP32"
            TransportDirection.RECEIVED -> "ESP32 -> APP"
            TransportDirection.INFO -> "INFO"
        }
        Log.d(tag, "[$prefix] $payload")
    }

    private fun closeSocketLocked() {
        closeQuietly(outputStream)
        closeQuietly(inputStream)
        closeQuietly(socket)
        outputStream = null
        inputStream = null
        socket = null
    }

    private fun closeQuietly(closeable: Any?) {
        try {
            when (closeable) {
                is BluetoothSocket -> closeable.close()
                is InputStream -> closeable.close()
                is OutputStream -> closeable.close()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_BT_DEVICE_ADDRESS = "bt_device_address"
        private const val KEY_BT_DEVICE_NAME = "bt_device_name"
        private const val MAX_TRANSPORT_LOGS = 200
        private const val RESPONSE_TIMEOUT_MS = 4000L
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

fun readLineWithTimeout(input: InputStream, timeoutMs: Long): String {
    val deadline = System.currentTimeMillis() + timeoutMs
    val bytes = mutableListOf<Byte>()
    while (System.currentTimeMillis() < deadline) {
        while (input.available() > 0) {
            val value = input.read()
            if (value < 0) throw IOException("Bluetooth input stream closed")
            if (value == '\n'.code) {
                return bytes.toByteArray().toString(Charsets.UTF_8).trimEnd('\r')
            }
            bytes += value.toByte()
        }
        Thread.sleep(20)
    }
    if (bytes.isNotEmpty()) {
        val partial = bytes.toByteArray().toString(Charsets.UTF_8).trimEnd('\r')
        val trimmed = partial.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }
        throw IOException("Timed out waiting for ESP32 response; partial=$trimmed")
    }
    throw IOException("Timed out waiting for ESP32 response")
}
