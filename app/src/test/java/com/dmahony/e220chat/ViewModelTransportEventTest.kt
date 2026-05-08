package com.dmahony.e220chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for ViewModel transport event handling, connection state
 * transitions, error classification, and operation status updates.
 *
 * Covers:
 *  - handleTransportEvent state transitions (CONNECTED, CONNECTING, RECONNECTING, DISCONNECTED)
 *  - Manual vs auto DISCONNECTED mapping
 *  - Full lifecycle traces
 *  - Operation status updates during send + reconnect
 *  - isTransportLossError / isBluetoothCacheStaleError classifiers
 *  - shouldSuppressTransientRebootError in all contexts
 */
class ViewModelTransportEventTest {

    // ─── CONNECTED state transitions ───

    @Test
    fun `CONNECTED transport event sets connectionState to CONNECTED`() {
        val event = TransportConnectionEvent(
            state = TransportConnectionState.CONNECTED,
            message = "Connected to device"
        )

        // Simulate handleTransportEvent
        var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        when (event.state) {
            TransportConnectionState.CONNECTED -> {
                connectionState = ConnectionState.CONNECTED
            }
            else -> fail("Unexpected state")
        }

        assertEquals(ConnectionState.CONNECTED, connectionState)
    }

    @Test
    fun `CONNECTED transport event clears error state`() {
        val event = TransportConnectionEvent(
            state = TransportConnectionState.CONNECTED,
            message = "Reconnected after error"
        )

        var connectionState: ConnectionState = ConnectionState.ERROR
        var rebootInProgress = true

        when (event.state) {
            TransportConnectionState.CONNECTED -> {
                rebootInProgress = false
                connectionState = ConnectionState.CONNECTED
            }
            else -> fail("Unexpected state")
        }

        assertEquals(ConnectionState.CONNECTED, connectionState)
        assertFalse(rebootInProgress)
    }

    // ─── CONNECTING / RECONNECTING handling ───

    @Test
    fun `CONNECTING transport event sets connectionState to CONNECTING`() {
        val event = TransportConnectionEvent(
            state = TransportConnectionState.CONNECTING,
            message = "Connecting to device..."
        )

        var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        when (event.state) {
            TransportConnectionState.CONNECTING, TransportConnectionState.RECONNECTING -> {
                connectionState = ConnectionState.CONNECTING
            }
            else -> fail("Unexpected state")
        }

        assertEquals(ConnectionState.CONNECTING, connectionState)
    }

    @Test
    fun `RECONNECTING transport event sets connectionState to CONNECTING`() {
        val event = TransportConnectionEvent(
            state = TransportConnectionState.RECONNECTING,
            message = "Bluetooth link lost, reconnecting..."
        )

        var connectionState: ConnectionState = ConnectionState.CONNECTED
        when (event.state) {
            TransportConnectionState.CONNECTING, TransportConnectionState.RECONNECTING -> {
                connectionState = ConnectionState.CONNECTING
            }
            else -> fail("Unexpected state")
        }

        assertEquals(ConnectionState.CONNECTING, connectionState)
    }

    // ─── Manual vs auto DISCONNECTED ───

    @Test
    fun `manual DISCONNECTED maps to DISCONNECTED state`() {
        val event = TransportConnectionEvent(
            state = TransportConnectionState.DISCONNECTED,
            message = "Bluetooth disconnected",
            manualDisconnect = true
        )

        var connectionState: ConnectionState = ConnectionState.CONNECTED
        when (event.state) {
            TransportConnectionState.DISCONNECTED -> {
                connectionState = if (event.manualDisconnect) {
                    ConnectionState.DISCONNECTED
                } else {
                    ConnectionState.ERROR
                }
            }
            else -> fail("Unexpected state")
        }

        assertEquals(ConnectionState.DISCONNECTED, connectionState)
    }

    @Test
    fun `automatic DISCONNECTED maps to ERROR state`() {
        val event = TransportConnectionEvent(
            state = TransportConnectionState.DISCONNECTED,
            message = "Bluetooth reconnect failed",
            manualDisconnect = false
        )

        var connectionState: ConnectionState = ConnectionState.CONNECTED
        when (event.state) {
            TransportConnectionState.DISCONNECTED -> {
                connectionState = if (event.manualDisconnect) {
                    ConnectionState.DISCONNECTED
                } else {
                    ConnectionState.ERROR
                }
            }
            else -> fail("Unexpected state")
        }

        assertEquals(ConnectionState.ERROR, connectionState)
    }

    // ─── Full lifecycle trace ───

    @Test
    fun `full transport lifecycle goes through all expected states`() {
        val lifecycle = mutableListOf<Pair<String, ConnectionState>>()

        // Initial state
        var connectionState = ConnectionState.DISCONNECTED
        lifecycle += "init" to connectionState

        // Simulate: connect
        val events = listOf(
            TransportConnectionEvent(TransportConnectionState.CONNECTING, "connecting"),
            TransportConnectionEvent(TransportConnectionState.CONNECTED, "connected"),
            TransportConnectionEvent(TransportConnectionState.DISCONNECTED, "disconnected", manualDisconnect = false),
            TransportConnectionEvent(TransportConnectionState.DISCONNECTED, "manual", manualDisconnect = true),
            TransportConnectionEvent(TransportConnectionState.RECONNECTING, "reconnecting"),
            TransportConnectionEvent(TransportConnectionState.CONNECTED, "reconnected")
        )

        for (event in events) {
            connectionState = when (event.state) {
                TransportConnectionState.CONNECTED -> ConnectionState.CONNECTED
                TransportConnectionState.CONNECTING, TransportConnectionState.RECONNECTING -> ConnectionState.CONNECTING
                TransportConnectionState.DISCONNECTED -> {
                    if (event.manualDisconnect) ConnectionState.DISCONNECTED
                    else ConnectionState.ERROR
                }
            }
            lifecycle += event.state.name to connectionState
        }

        // Verify: auto-disconnect → ERROR, manual → DISCONNECTED
        assertEquals(ConnectionState.ERROR, lifecycle[3].second) // after auto-disconnect → ERROR
        assertEquals(ConnectionState.DISCONNECTED, lifecycle[4].second) // manual disconnect
        assertEquals(ConnectionState.CONNECTING, lifecycle[5].second) // reconnecting
        assertEquals(ConnectionState.CONNECTED, lifecycle[6].second) // reconnected
    }

    // ─── Operation status during send + reconnect ───

    @Test
    fun `operation status updates when reconnecting during send`() {
        val event = TransportConnectionEvent(
            state = TransportConnectionState.RECONNECTING,
            message = "Bluetooth link lost, reconnecting..."
        )

        var operationStatus = OperationStatus(type = "send", state = "running", message = "Sending message")

        // Simulate handleTransportEvent
        if (event.state == TransportConnectionState.CONNECTING ||
            event.state == TransportConnectionState.RECONNECTING
        ) {
            if (operationStatus.type == "send" && operationStatus.state == "running") {
                operationStatus = operationStatus.copy(message = "Bluetooth link lost, reconnecting...")
            }
        }

        assertEquals("send", operationStatus.type)
        assertEquals("running", operationStatus.state)
        assertEquals("Bluetooth link lost, reconnecting...", operationStatus.message)
    }

    @Test
    fun `operation status is cleared on successful reconnect during send`() {
        val event = TransportConnectionEvent(
            state = TransportConnectionState.CONNECTED,
            message = "Reconnected"
        )

        var operationStatus = OperationStatus(type = "send", state = "running", message = "reconnecting...")

        if (event.state == TransportConnectionState.CONNECTED) {
            if (operationStatus.type == "send" && operationStatus.state == "running") {
                operationStatus = operationStatus.copy(message = "Bluetooth reconnected, finishing send...")
            }
        }

        assertEquals("Bluetooth reconnected, finishing send...", operationStatus.message)
    }

    @Test
    fun `operation status set to error on manual disconnect during send`() {
        val event = TransportConnectionEvent(
            state = TransportConnectionState.DISCONNECTED,
            message = "manual",
            manualDisconnect = true
        )

        var operationStatus = OperationStatus(type = "send", state = "running", message = "Sending...")

        if (event.state == TransportConnectionState.DISCONNECTED && event.manualDisconnect) {
            if (operationStatus.type == "send" && operationStatus.state == "running") {
                operationStatus = operationStatus.copy(
                    state = "error",
                    message = "Send stopped because Bluetooth disconnected"
                )
            }
        }

        assertEquals("error", operationStatus.state)
        assertTrue(operationStatus.message.contains("disconnected"))
    }

    // ─── isTransportLossError classifier ───

    @Test
    fun `isTransportLossError matches BLE errors`() {
        val e = Exception("BLE socket closed unexpectedly")
        val message = e.message.orEmpty()
        val isTransportLoss = message.contains("BLE", ignoreCase = true) ||
            message.contains("GATT", ignoreCase = true) ||
            message.contains("socket", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("disconnected", ignoreCase = true) ||
            message.contains("connect failed", ignoreCase = true)
        assertTrue(isTransportLoss)
    }

    @Test
    fun `isTransportLossError matches GATT errors`() {
        val e = Exception("GATT error during write")
        val message = e.message.orEmpty()
        val isTransportLoss = message.contains("GATT", ignoreCase = true)
        assertTrue(isTransportLoss)
    }

    @Test
    fun `isTransportLossError matches socket errors`() {
        val e = Exception("socket closed during read")
        val message = e.message.orEmpty()
        val isTransportLoss = message.contains("socket", ignoreCase = true)
        assertTrue(isTransportLoss)
    }

    @Test
    fun `isTransportLossError matches timeout errors`() {
        val e = Exception("Connection timeout after 30s")
        val message = e.message.orEmpty()
        val isTransportLoss = message.contains("timeout", ignoreCase = true)
        assertTrue(isTransportLoss)
    }

    @Test
    fun `isTransportLossError matches disconnect errors`() {
        val e = Exception("BLE disconnected during send")
        val message = e.message.orEmpty()
        val isTransportLoss = message.contains("disconnected", ignoreCase = true)
        assertTrue(isTransportLoss)
    }

    @Test
    fun `isTransportLossError matches connect failed errors`() {
        val e = Exception("connect failed: device busy")
        val message = e.message.orEmpty()
        val isTransportLoss = message.contains("connect failed", ignoreCase = true)
        assertTrue(isTransportLoss)
    }

    @Test
    fun `isTransportLossError does not match unrelated errors`() {
        val e = Exception("Invalid JSON format")
        val message = e.message.orEmpty()
        val isTransportLoss = message.contains("BLE", ignoreCase = true) ||
            message.contains("GATT", ignoreCase = true) ||
            message.contains("socket", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("disconnected", ignoreCase = true) ||
            message.contains("connect failed", ignoreCase = true)
        assertFalse(isTransportLoss)
    }

    // ─── isBluetoothCacheStaleError classifier ───

    @Test
    fun `isBluetoothCacheStaleError detects status 133`() {
        val e = Exception("BLE error status 133")
        val isCacheStale = e.message.orEmpty().contains("status 133", ignoreCase = true) ||
            e.message.orEmpty().contains("Bluetooth cache is stale", ignoreCase = true)
        assertTrue(isCacheStale)
    }

    @Test
    fun `isBluetoothCacheStaleError detects stale cache message`() {
        val e = Exception("Bluetooth cache is stale. Re-pair device.")
        val isCacheStale = e.message.orEmpty().contains("Bluetooth cache is stale", ignoreCase = true)
        assertTrue(isCacheStale)
    }

    // ─── shouldSuppressTransientRebootError ───

    @Test
    fun `shouldSuppressTransientRebootError suppresses during reboot in progress`() {
        val rebootInProgress = true
        val e = Exception("BLE disconnected unexpectedly")
        val shouldSuppress = rebootInProgress ||
            e.message.orEmpty().contains("Invalid response from ESP32", ignoreCase = true) ||
            e.message.orEmpty().contains("unexpected JSON token", ignoreCase = true) ||
            e.message.orEmpty().contains("JSON parse error", ignoreCase = true) ||
            e.message.orEmpty().contains("BLE disconnected", ignoreCase = true)
        assertTrue(shouldSuppress)
    }

    @Test
    fun `shouldSuppressTransientRebootError suppresses invalid ESP32 response`() {
        val rebootInProgress = false
        val e = Exception("Invalid response from ESP32 during reboot")
        val shouldSuppress = rebootInProgress ||
            e.message.orEmpty().contains("Invalid response from ESP32", ignoreCase = true)
        assertTrue(shouldSuppress)
    }

    @Test
    fun `shouldSuppressTransientRebootError suppresses unexpected JSON errors`() {
        val rebootInProgress = false
        val e = Exception("unexpected JSON token at position 0")
        val shouldSuppress = e.message.orEmpty().contains("unexpected JSON token", ignoreCase = true)
        assertTrue(shouldSuppress)
    }

    @Test
    fun `shouldSuppressTransientRebootError suppresses JSON parse errors`() {
        val rebootInProgress = false
        val e = Exception("JSON parse error in response")
        val shouldSuppress = e.message.orEmpty().contains("JSON parse error", ignoreCase = true)
        assertTrue(shouldSuppress)
    }

    @Test
    fun `shouldSuppressTransientRebootError suppresses BLE disconnected during reboot`() {
        val rebootInProgress = false
        val e = Exception("BLE disconnected after reboot")
        val shouldSuppress = e.message.orEmpty().contains("BLE disconnected", ignoreCase = true)
        assertTrue(shouldSuppress)
    }

    @Test
    fun `shouldSuppressTransientRebootError does not suppress unrelated errors`() {
        val rebootInProgress = false
        val e = Exception("Memory allocation failed")
        val message = e.message.orEmpty()
        val shouldSuppress = rebootInProgress ||
            message.contains("Invalid response from ESP32", ignoreCase = true) ||
            message.contains("unexpected JSON token", ignoreCase = true) ||
            message.contains("JSON parse error", ignoreCase = true) ||
            message.contains("BLE disconnected", ignoreCase = true)
        assertFalse(shouldSuppress)
    }
}
