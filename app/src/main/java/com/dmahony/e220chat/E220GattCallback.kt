package com.dmahony.e220chat

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Build
import java.io.IOException

internal class E220GattCallback(private val repo: E220Repository) : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (status == 133) {
                repo.appendTransportLog(TransportDirection.INFO, "BLE status 133: refreshing Bluetooth cache")
                try {
                    repo.refreshGattCache()
                } catch (_: Exception) {
                }
                repo.closeGattLocked(triggerDisconnect = false)
                repo.pendingConnect?.completeExceptionally(
                    ApiException(
                        "Bluetooth cache is stale (status 133). Forget this device in Bluetooth settings, then re-pair and reconnect."
                    )
                )
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                repo.handleUnexpectedDisconnect(gatt, status)
            } else {
                repo.pendingConnect?.completeExceptionally(IOException("BLE connect failed ($status)"))
            }
            return
        }
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                repo.appendTransportLog(TransportDirection.INFO, "BLE connected, discovering services")
                if (!gatt.discoverServices()) {
                    repo.pendingConnect?.completeExceptionally(IOException("BLE service discovery failed to start"))
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                repo.handleUnexpectedDisconnect(gatt, status)
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            repo.pendingConnect?.completeExceptionally(IOException("BLE service discovery failed ($status)"))
            return
        }
        val service = gatt.getService(E220Repository.NUS_SERVICE_UUID)
            ?: run {
                repo.pendingConnect?.completeExceptionally(IOException("BLE UART service not found"))
                return
            }
        repo.rxCharacteristic = service.getCharacteristic(E220Repository.NUS_RX_UUID)
        repo.txCharacteristic = service.getCharacteristic(E220Repository.NUS_TX_UUID)
        if (repo.rxCharacteristic == null || repo.txCharacteristic == null) {
            repo.pendingConnect?.completeExceptionally(IOException("BLE UART characteristics not found"))
            return
        }
        val notifyChar = repo.txCharacteristic ?: return
        gatt.setCharacteristicNotification(notifyChar, true)
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        val descriptor = notifyChar.getDescriptor(E220Repository.CLIENT_CONFIG_UUID)
        if (descriptor == null) {
            repo.pendingConnect?.completeExceptionally(IOException("BLE notification descriptor not found"))
            return
        }
        val descriptorDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
        repo.pendingDescriptorWrite = descriptorDeferred
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt.writeDescriptor(descriptor)) {
            repo.pendingConnect?.completeExceptionally(IOException("BLE descriptor write failed"))
            return
        }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            repo.pendingWrite?.complete(Unit)
        } else {
            repo.pendingWrite?.completeExceptionally(IOException("BLE write failed ($status)"))
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        repo.handleIncomingChunk(value.toString(Charsets.UTF_8))
    }

    @Deprecated("Deprecated in Android 13 but still available for older callback signatures")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val value = characteristic.value ?: return
        repo.handleIncomingChunk(value.toString(Charsets.UTF_8))
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            repo.pendingDescriptorWrite?.complete(Unit)
            repo.pendingConnect?.complete(Unit)
        } else {
            repo.pendingDescriptorWrite?.completeExceptionally(IOException("BLE notification setup failed ($status)"))
            repo.pendingConnect?.completeExceptionally(IOException("BLE notification setup failed ($status)"))
        }
    }
}
