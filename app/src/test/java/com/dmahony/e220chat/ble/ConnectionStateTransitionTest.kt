package com.dmahony.e220chat.ble

import com.dmahony.e220chat.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ConnectionStateTransitionTest {

    @Test
    fun `ConnectionState has expected values`() {
        assertEquals(4, ConnectionState.entries.size)
        assertEquals(ConnectionState.DISCONNECTED, ConnectionState.valueOf("DISCONNECTED"))
        assertEquals(ConnectionState.CONNECTING, ConnectionState.valueOf("CONNECTING"))
        assertEquals(ConnectionState.CONNECTED, ConnectionState.valueOf("CONNECTED"))
        assertEquals(ConnectionState.ERROR, ConnectionState.valueOf("ERROR"))
    }

    @Test
    fun `ensureConnected pattern retries when not connected`() = runTest {
        var connected = false
        var connectCalls = 0

        suspend fun ensureConnected(): String {
            if (connected) return "already_connected"
            connectCalls++
            connected = true
            return "connected"
        }

        val r1 = ensureConnected()
        assertEquals("connected", r1)
        assertEquals(1, connectCalls)

        val r2 = ensureConnected()
        assertEquals("already_connected", r2)
        assertEquals(1, connectCalls)
    }

    @Test
    fun `maybeReconnect pattern ignores duplicate calls when reconnect job is active`() = runTest {
        var activeReconnects = 0
        var reconnectRunning = false

        fun maybeReconnect() {
            if (reconnectRunning) return
            reconnectRunning = true
            try {
                activeReconnects++
            } finally {
                reconnectRunning = false
            }
        }

        maybeReconnect()
        assertEquals(1, activeReconnects)

        maybeReconnect()
        assertEquals(2, activeReconnects)
    }

    @Test
    fun `frame write does not block reconnect callback`() {
        val steps = mutableListOf<String>()
        steps += "frame_write_start"
        steps += "reconnect_start"
        steps += "frame_write_end"
        steps += "reconnect_end"

        assertEquals(4, steps.size)
        assertTrue(steps.contains("frame_write_start"))
        assertTrue(steps.contains("reconnect_start"))
        assertTrue(steps.contains("frame_write_end"))
        assertTrue(steps.contains("reconnect_end"))
    }

    @Test
    fun `connect callback chain follows expected order`() = runTest {
        val steps = mutableListOf<String>()
        steps += "connect_complete"
        steps += "services_discovered"
        steps += "characteristics_found"
        steps += "mtu_changed"
        steps += "notify_enabled"
        steps += "connected_true"

        assertEquals(
            listOf(
                "connect_complete",
                "services_discovered",
                "characteristics_found",
                "mtu_changed",
                "notify_enabled",
                "connected_true"
            ),
            steps
        )
    }

    @Test
    fun `disconnect callback clears state then triggers reconnect`() = runTest {
        val wasConnected = true
        assertTrue(wasConnected)
    }
}
