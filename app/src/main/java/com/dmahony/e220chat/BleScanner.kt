package com.dmahony.e220chat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

private const val RECENT_SCAN_VISIBILITY_WINDOW_MS = 10000L
private const val BLE_NAME_PREFIX = "E220-BLE-"
private val NUS_SERVICE_UUID: UUID = UUID.fromString("9f6d0001-6f52-4d94-b43f-2ef6f3ed7a10")

internal class BleScanner(
    context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val tag: String,
    private val selectedDeviceAddressProvider: () -> String?,
    private val displayBluetoothName: (String?) -> String,
) {
    private val appContext = context.applicationContext
    private val stateLock = Any()

    private var cachedDevices: List<BluetoothDeviceInfo> = emptyList()
    private var lastScanCompletedAtMs: Long = 0L
    private var activeScanScanner: BluetoothLeScanner? = null
    private var activeScanCallback: ScanCallback? = null

    fun getPairedDevices(): List<BluetoothDeviceInfo> = cachedDevices

    fun isDeviceVisibleInRecentScan(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        if (System.currentTimeMillis() - lastScanCompletedAtMs > RECENT_SCAN_VISIBILITY_WINDOW_MS) return false
        return cachedDevices.any { it.address.equals(address, ignoreCase = true) }
    }

    @SuppressLint("MissingPermission")
    suspend fun scanBleDevices(scanMillis: Long = 20000L): List<BluetoothDeviceInfo> = withContext(Dispatchers.IO) {
        if (!hasBluetoothScanPermission()) {
            Log.w(tag, "Skipping BLE scan until Bluetooth permissions are granted")
            cachedDevices = emptyList()
            return@withContext emptyList()
        }

        val expectedResults = linkedMapOf<String, BluetoothDeviceInfo>()
        val fallbackResults = linkedMapOf<String, BluetoothDeviceInfo>()

        fun isExpectedName(name: String?): Boolean =
            name != null && (
                name.startsWith(BLE_NAME_PREFIX, ignoreCase = true) ||
                    name.contains("E220", ignoreCase = true) ||
                    name.contains("ESP32", ignoreCase = true)
                )

        fun putDevice(target: MutableMap<String, BluetoothDeviceInfo>, address: String, name: String?) {
            target[address] = BluetoothDeviceInfo(name = displayBluetoothName(name), address = address)
        }

        fun addBondedDevice(device: BluetoothDevice) {
            val name = device.name
            if (isExpectedName(name) || device.address == selectedDeviceAddressProvider()) {
                putDevice(expectedResults, device.address, name)
            } else {
                putDevice(fallbackResults, device.address, name)
            }
        }

        fun addScanResult(result: ScanResult) {
            val device = result.device
            val advertisedName = result.scanRecord?.deviceName ?: device.name
            val hasExpectedName = isExpectedName(advertisedName)
            val hasExpectedService = result.scanRecord?.serviceUuids?.any { it.uuid == NUS_SERVICE_UUID } == true
            val isSelectedDevice = device.address == selectedDeviceAddressProvider()
            Log.d(
                tag,
                "BLE scan found: addr=${redactBluetoothAddress(device.address)} name=$advertisedName expectedName=$hasExpectedName expectedService=$hasExpectedService"
            )
            if (hasExpectedName || hasExpectedService || isSelectedDevice) {
                putDevice(expectedResults, device.address, advertisedName)
                Log.d(tag, "BLE scan added expected device: ${redactBluetoothAddress(device.address)} (${displayBluetoothName(advertisedName)}) total=${expectedResults.size}")
            } else {
                putDevice(fallbackResults, device.address, advertisedName)
                Log.d(tag, "BLE scan added fallback device: ${redactBluetoothAddress(device.address)} (${displayBluetoothName(advertisedName)}) total=${fallbackResults.size}")
            }
        }

        bluetoothAdapter?.bondedDevices?.forEach { bonded ->
            addBondedDevice(bonded)
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner != null) {
            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    addScanResult(result)
                }

                override fun onBatchScanResults(resultsBatch: MutableList<ScanResult>) {
                    resultsBatch.forEach(::addScanResult)
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(tag, "BLE scan failed with code $errorCode")
                }
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            synchronized(stateLock) {
                activeScanScanner = scanner
                activeScanCallback = scanCallback
            }
            scanner.startScan(emptyList(), settings, scanCallback)
            try {
                delay(scanMillis)
            } finally {
                try {
                    scanner.stopScan(scanCallback)
                } catch (_: Exception) {
                }
                synchronized(stateLock) {
                    if (activeScanCallback === scanCallback) activeScanCallback = null
                    if (activeScanScanner === scanner) activeScanScanner = null
                }
            }
        }

        val chosen = if (expectedResults.isNotEmpty()) expectedResults else fallbackResults
        val discovered = chosen.values.sortedWith(compareBy<BluetoothDeviceInfo> { it.name.lowercase() }.thenBy { it.address })
        cachedDevices = discovered
        lastScanCompletedAtMs = System.currentTimeMillis()
        discovered
    }

    fun stopBleScan() {
        val scanner: BluetoothLeScanner?
        val callback: ScanCallback?
        synchronized(stateLock) {
            scanner = activeScanScanner
            callback = activeScanCallback
            activeScanScanner = null
            activeScanCallback = null
        }
        if (scanner != null && callback != null) {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun hasBluetoothScanPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        else ->
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun redactBluetoothAddress(value: String): String {
        val parts = value.split(":")
        return if (parts.size == 6 && parts.all { it.length == 2 }) {
            parts.take(3).joinToString(":") + ":**:**:**"
        } else {
            value
        }
    }
}
