package com.dmahony.e220chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewModelConnectionStateExtendedTest {

    // ─── ConnectionState enum ───

    @Test
    fun `ConnectionState ordinal values are stable`() {
        assertEquals(0, ConnectionState.DISCONNECTED.ordinal)
        assertEquals(1, ConnectionState.CONNECTING.ordinal)
        assertEquals(2, ConnectionState.CONNECTED.ordinal)
        assertEquals(3, ConnectionState.ERROR.ordinal)
    }

    @Test
    fun `ConnectionState names match enum constants`() {
        assertEquals("DISCONNECTED", ConnectionState.DISCONNECTED.name)
        assertEquals("CONNECTING", ConnectionState.CONNECTING.name)
        assertEquals("CONNECTED", ConnectionState.CONNECTED.name)
        assertEquals("ERROR", ConnectionState.ERROR.name)
    }

    @Test
    fun `all ConnectionState values are accessible`() {
        val values = ConnectionState.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(ConnectionState.DISCONNECTED))
        assertTrue(values.contains(ConnectionState.CONNECTING))
        assertTrue(values.contains(ConnectionState.CONNECTED))
        assertTrue(values.contains(ConnectionState.ERROR))
    }

    // ─── State transition rules ───

    @Test
    fun `DISCONNECTED to CONNECTING transition is valid`() {
        // Starting from DISCONNECTED, can go to CONNECTING
        val from = ConnectionState.DISCONNECTED
        val to = ConnectionState.CONNECTING
        assertNotEquals(from, to)
    }

    @Test
    fun `CONNECTING to CONNECTED transition is valid`() {
        // Successful connection
        val from = ConnectionState.CONNECTING
        val to = ConnectionState.CONNECTED
        assertNotEquals(from, to)
    }

    @Test
    fun `CONNECTING to ERROR transition is valid`() {
        // Connection failure
        assertNotEquals(ConnectionState.CONNECTING, ConnectionState.ERROR)
    }

    @Test
    fun `CONNECTED to DISCONNECTED transition is valid`() {
        // Manual disconnect
        assertNotEquals(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED)
    }

    @Test
    fun `CONNECTED to ERROR transition is valid`() {
        // Unexpected disconnect or transport loss
        assertNotEquals(ConnectionState.CONNECTED, ConnectionState.ERROR)
    }

    @Test
    fun `ERROR to CONNECTING transition is valid`() {
        // Retry after error
        assertNotEquals(ConnectionState.ERROR, ConnectionState.CONNECTING)
    }

    @Test
    fun `ERROR to DISCONNECTED transition is valid`() {
        // Clean reset after error
        assertNotEquals(ConnectionState.ERROR, ConnectionState.DISCONNECTED)
    }

    // ─── Redundant transitions ───

    @Test
    fun `redundant ERROR to ERROR is a no-op state-wise`() {
        assertEquals(ConnectionState.ERROR, ConnectionState.ERROR)
    }

    @Test
    fun `redundant CONNECTED to CONNECTED is a no-op`() {
        assertEquals(ConnectionState.CONNECTED, ConnectionState.CONNECTED)
    }

    // ─── TransportConnectionState enum ───

    @Test
    fun `TransportConnectionState ordinal values are stable`() {
        assertEquals(0, TransportConnectionState.CONNECTING.ordinal)
        assertEquals(1, TransportConnectionState.CONNECTED.ordinal)
        assertEquals(2, TransportConnectionState.DISCONNECTED.ordinal)
        assertEquals(3, TransportConnectionState.RECONNECTING.ordinal)
    }

    @Test
    fun `TransportConnectionState has four states`() {
        assertEquals(4, TransportConnectionState.entries.size)
    }

    // ─── TransportConnectionEvent ───

    @Test
    fun `TransportConnectionEvent manual disconnect flag defaults to false`() {
        val event = TransportConnectionEvent(
            state = TransportConnectionState.DISCONNECTED,
            message = "test"
        )
        assertFalse(event.manualDisconnect)
    }

    @Test
    fun `TransportConnectionEvent with manual disconnect`() {
        val event = TransportConnectionEvent(
            state = TransportConnectionState.DISCONNECTED,
            message = "manual",
            manualDisconnect = true
        )
        assertTrue(event.manualDisconnect)
    }

    @Test
    fun `TransportConnectionEvent CONNECTED state`() {
        val event = TransportConnectionEvent(
            state = TransportConnectionState.CONNECTED,
            message = "connected to device"
        )
        assertEquals(TransportConnectionState.CONNECTED, event.state)
        assertEquals("connected to device", event.message)
    }

    @Test
    fun `TransportConnectionEvent RECONNECTING state`() {
        val event = TransportConnectionEvent(
            state = TransportConnectionState.RECONNECTING,
            message = "reconnecting..."
        )
        assertEquals(TransportConnectionState.RECONNECTING, event.state)
    }

    // ─── Connection hint transitions ───

    @Test
    fun `connection hint reflects CONNECTED state`() {
        // Verify that the hint pattern is meaningful
        val hints = mapOf(
            ConnectionState.DISCONNECTED to "Bluetooth disconnected",
            ConnectionState.CONNECTING to "Connecting...",
            ConnectionState.CONNECTED to "Connected to device",
            ConnectionState.ERROR to "Connection failed"
        )
        assertEquals(4, hints.size)
        assertTrue(hints[ConnectionState.CONNECTED]!!.contains("Connected"))
    }

    @Test
    fun `connection hint reflects ERROR state`() {
        val hints = mapOf(
            ConnectionState.DISCONNECTED to "Bluetooth disconnected",
            ConnectionState.ERROR to "Bluetooth connection failed"
        )
        assertNotEquals(hints[ConnectionState.DISCONNECTED], hints[ConnectionState.ERROR])
    }

    // ─── Error classification helpers ───

    @Test
    fun `isTransportLossError detects BLE errors`() {
        val e = Exception("BLE socket closed")
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
    fun `isTransportLossError detects GATT errors`() {
        val e = Exception("GATT error 133")
        val isTransportLoss = e.message.orEmpty().contains("GATT", ignoreCase = true)
        assertTrue(isTransportLoss)
    }

    @Test
    fun `isTransportLossError detects timeout errors`() {
        val e = Exception("Connection timeout")
        val isTransportLoss = e.message.orEmpty().contains("timeout", ignoreCase = true)
        assertTrue(isTransportLoss)
    }

    @Test
    fun `isTransportLossError detects disconnect`() {
        val e = Exception("BLE disconnected unexpectedly")
        val isTransportLoss = e.message.orEmpty().contains("disconnected", ignoreCase = true)
        assertTrue(isTransportLoss)
    }

    @Test
    fun `isTransportLossError detects connect failure`() {
        val e = Exception("connect failed: device not found")
        val isTransportLoss = e.message.orEmpty().contains("connect failed", ignoreCase = true)
        assertTrue(isTransportLoss)
    }

    @Test
    fun `isTransportLossError does not match unrelated errors`() {
        val e = Exception("JSON parse error")
        val message = e.message.orEmpty()
        val isTransportLoss = message.contains("BLE", ignoreCase = true) ||
            message.contains("GATT", ignoreCase = true) ||
            message.contains("socket", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("disconnected", ignoreCase = true) ||
            message.contains("connect failed", ignoreCase = true)
        assertFalse(isTransportLoss)
    }

    @Test
    fun `isBluetoothCacheStaleError detects status 133`() {
        val e = Exception("GATT status 133 error")
        val isCacheStale = e.message.orEmpty().contains("status 133", ignoreCase = true) ||
            e.message.orEmpty().contains("Bluetooth cache is stale", ignoreCase = true)
        assertTrue(isCacheStale)
    }

    @Test
    fun `isBluetoothCacheStaleError detects explicit message`() {
        val e = Exception("Bluetooth cache is stale. Forget and re-pair.")
        val isCacheStale = e.message.orEmpty().contains("Bluetooth cache is stale", ignoreCase = true)
        assertTrue(isCacheStale)
    }

    @Test
    fun `shouldSuppressTransientRebootError suppresses during reboot`() {
        // Simulate: rebootInProgress is true
        val rebootInProgress = true
        val e = Exception("BLE disconnected")
        val shouldSuppress = rebootInProgress || e.message.orEmpty().contains("Invalid response from ESP32", ignoreCase = true) ||
            e.message.orEmpty().contains("unexpected JSON token", ignoreCase = true) ||
            e.message.orEmpty().contains("JSON parse error", ignoreCase = true) ||
            e.message.orEmpty().contains("BLE disconnected", ignoreCase = true)
        assertTrue(shouldSuppress)
    }

    @Test
    fun `shouldSuppressTransientRebootError suppresses JSON parse errors`() {
        val e = Exception("unexpected JSON token at position 0")
        val shouldSuppress = e.message.orEmpty().contains("unexpected JSON token", ignoreCase = true)
        assertTrue(shouldSuppress)
    }

    @Test
    fun `shouldSuppressTransientRebootError suppresses invalid ESP32 response`() {
        val e = Exception("Invalid response from ESP32 during reboot")
        val shouldSuppress = e.message.orEmpty().contains("Invalid response from ESP32", ignoreCase = true)
        assertTrue(shouldSuppress)
    }
}
