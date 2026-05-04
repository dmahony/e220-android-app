package com.dmahony.e220chat

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Build
import java.io.IOException

internal class LegacyBleGattCallback(private val transport: LegacyBleTransport) : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (status == 133) {
                transport.appendTransportLog(TransportDirection.INFO, "BLE status 133: refreshing Bluetooth cache")
                try {
                    transport.refreshGattCache()
                } catch (_: Exception) {
                }
                transport.closeGattLocked(triggerDisconnect = false)
                transport.pendingConnect?.completeExceptionally(
                    IOException(
                        "Bluetooth cache is stale (status 133). Forget this device in Bluetooth settings, then re-pair and reconnect."
                    )
                )
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                transport.handleUnexpectedDisconnect(gatt, status)
            } else {
                transport.pendingConnect?.completeExceptionally(IOException("BLE connect failed ($status)"))
            }
            return
        }
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                transport.appendTransportLog(TransportDirection.INFO, "BLE connected, discovering services")
                if (!gatt.discoverServices()) {
                    transport.pendingConnect?.completeExceptionally(IOException("BLE service discovery failed to start"))
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                transport.handleUnexpectedDisconnect(gatt, status)
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            transport.pendingConnect?.completeExceptionally(IOException("BLE service discovery failed ($status)"))
            return
        }
        val service = gatt.getService(LegacyBleTransport.NUS_SERVICE_UUID)
            ?: run {
                transport.pendingConnect?.completeExceptionally(IOException("BLE UART service not found"))
                return
            }
        transport.rxCharacteristic = service.getCharacteristic(LegacyBleTransport.NUS_RX_UUID)
        transport.txCharacteristic = service.getCharacteristic(LegacyBleTransport.NUS_TX_UUID)
        if (transport.rxCharacteristic == null || transport.txCharacteristic == null) {
            transport.pendingConnect?.completeExceptionally(IOException("BLE UART characteristics not found"))
            return
        }
        val notifyChar = transport.txCharacteristic ?: return
        gatt.setCharacteristicNotification(notifyChar, true)
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        val descriptor = notifyChar.getDescriptor(LegacyBleTransport.CLIENT_CONFIG_UUID)
        if (descriptor == null) {
            transport.pendingConnect?.completeExceptionally(IOException("BLE notification descriptor not found"))
            return
        }
        val descriptorDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
        transport.pendingDescriptorWrite = descriptorDeferred
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt.writeDescriptor(descriptor)) {
            transport.pendingConnect?.completeExceptionally(IOException("BLE descriptor write failed"))
            return
        }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            transport.pendingWrite?.complete(Unit)
        } else {
            transport.pendingWrite?.completeExceptionally(IOException("BLE write failed ($status)"))
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        transport.handleIncomingChunk(value.toString(Charsets.UTF_8))
    }

    @Deprecated("Deprecated in Android 13 but still available for older callback signatures")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val value = characteristic.value ?: return
        transport.handleIncomingChunk(value.toString(Charsets.UTF_8))
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            transport.pendingDescriptorWrite?.complete(Unit)
            transport.pendingConnect?.complete(Unit)
        } else {
            transport.pendingDescriptorWrite?.completeExceptionally(IOException("BLE notification setup failed ($status)"))
            transport.pendingConnect?.completeExceptionally(IOException("BLE notification setup failed ($status)"))
        }
    }
}
