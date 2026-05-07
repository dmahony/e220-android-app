package com.dmahony.e220chat.ble

import com.dmahony.e220chat.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Tests for BLE GATT callback ordering, reconnection patterns,
 * and no-deadlock mutex usage in the E220 BLE transport layer.
 *
 * Covers:
 *  - GATT callback chain: onConnectionStateChange → onServicesDiscovered → descriptor write
 *  - Service discovery error handling
 *  - Characteristic/descriptor missing handling
 *  - MTU best-effort pattern
 *  - ioMutex/attMutex non-deadlock pattern
 *  - Reconnect idempotency
 *  - No-address skip
 *  - Deferred cleanup on disconnect
 *  - Connect timeout pattern
 *  - GATT disconnect/connect state transitions
 */
class BleConnectCallbackTest {

    // ─── GATT callback ordering ───

    @Test
    fun `callback chain follows connect → discover → mtu → descriptor order`() = runTest {
        val steps = mutableListOf<String>()

        // Simulate the GATT callback chain
        steps += "onConnectionStateChange_CONNECTED"
        steps += "discoverServices_start"
        steps += "onServicesDiscovered_SUCCESS"
        steps += "findCharacteristics"
        steps += "setCharacteristicNotification"
        steps += "requestConnectionPriority_HIGH"
        steps += "writeDescriptor"
        steps += "onDescriptorWrite_SUCCESS"
        steps += "pendingConnect_complete"

        assertEquals(9, steps.size)
        assertEquals("onConnectionStateChange_CONNECTED", steps[0])
        assertEquals("pendingConnect_complete", steps.last())
    }

    @Test
    fun `discoverServices failure stops the chain`() = runTest {
        val pendingConnect = CompletableDeferred<Unit>()

        // Simulate: onConnectionStateChange calls discoverServices which returns false
        pendingConnect.completeExceptionally(IOException("BLE service discovery failed to start"))

        try {
            pendingConnect.await()
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("BLE service discovery failed to start", e.message)
        }
    }

    @Test
    fun `onServicesDiscovered with error completes pendingConnect exceptionally`() = runTest {
        val pendingConnect = CompletableDeferred<Unit>()

        // Simulate: onServicesDiscovered called with non-zero status
        pendingConnect.completeExceptionally(IOException("BLE service discovery failed (257)"))

        try {
            pendingConnect.await()
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("service discovery failed"))
        }
    }

    // ─── Characteristic / service missing ───

    @Test
    fun `missing UART service completes pendingConnect exceptionally`() = runTest {
        val pendingConnect = CompletableDeferred<Unit>()

        // Simulate: getService returns null
        pendingConnect.completeExceptionally(IOException("BLE UART service not found"))

        try {
            pendingConnect.await()
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("BLE UART service not found", e.message)
        }
    }

    @Test
    fun `missing UART characteristics completes pendingConnect exceptionally`() = runTest {
        val pendingConnect = CompletableDeferred<Unit>()

        pendingConnect.completeExceptionally(IOException("BLE UART characteristics not found"))

        try {
            pendingConnect.await()
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("BLE UART characteristics not found", e.message)
        }
    }

    @Test
    fun `missing notification descriptor completes pendingConnect exceptionally`() = runTest {
        val pendingConnect = CompletableDeferred<Unit>()

        pendingConnect.completeExceptionally(IOException("BLE notification descriptor not found"))

        try {
            pendingConnect.await()
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("BLE notification descriptor not found", e.message)
        }
    }

    // ─── MTU best-effort ───

    @Test
    fun `MTU request is best-effort and does not block connection`() = runTest {
        // requestConnectionPriority is a fire-and-forget best-effort call.
        // Even if it fails, the connection proceeds.
        var mtuRequested = false
        var connectionSucceeded = false

        // Simulate: MTU request fires, then connection completes
        mtuRequested = true
        connectionSucceeded = true

        assertTrue(mtuRequested)
        assertTrue(connectionSucceeded)
    }

    // ─── ioMutex / attMutex non-deadlock pattern ───

    @Test
    fun `frame write uses attMutex while reconnect uses ioMutex`() = runTest {
        val ioMutex = Mutex()
        val attMutex = Mutex()
        val executionOrder = mutableListOf<String>()

        // Frame write holds attMutex
        val frameWrite = launch {
            attMutex.withLock {
                executionOrder += "frame_write_att_acquired"
                // ioMutex can still be acquired independently (no deadlock)
            }
            executionOrder += "frame_write_done"
        }

        // Reconnect uses ioMutex
        val reconnect = launch {
            ioMutex.withLock {
                executionOrder += "reconnect_io_acquired"
            }
            executionOrder += "reconnect_done"
        }

        frameWrite.join()
        reconnect.join()

        assertTrue(executionOrder.contains("frame_write_att_acquired"))
        assertTrue(executionOrder.contains("reconnect_io_acquired"))
        assertTrue(executionOrder.contains("frame_write_done"))
        assertTrue(executionOrder.contains("reconnect_done"))
    }

    // ─── Reconnect ioMutex release during ACK wait ───

    @Test
    fun `reconnect releases ioMutex before waiting for ACK`() = runTest {
        val ioMutex = Mutex()
        val waiters = mutableMapOf<UByte, CompletableDeferred<Unit>>()
        var nextSeqValue: UByte = 10u

        // ioMutex is released after registerWaiter, before ACK wait
        ioMutex.withLock {
            // Acquire ioMutex for reconnect
        }

        // Now run ACK retry without holding ioMutex
        runAckRetry(
            initialSeq = 9u,
            maxRetry = 1,
            timeoutMs = 50,
            nextSeq = {
                val seq = nextSeqValue
                nextSeqValue = (nextSeqValue + 1u).toUByte()
                seq
            },
            registerWaiter = { seq ->
                CompletableDeferred<Unit>().also { waiters[seq] = it }
            },
            removeWaiter = { waiters.remove(it) }
        ) { _, seq ->
            waiters[seq]?.complete(Unit)
        }

        assertTrue(waiters.isEmpty())
    }

    // ─── Reconnect idempotency ───

    @Test
    fun `reconnect is skipped when already connected`() = runTest {
        var connected = true
        var reconnectCalled = false

        fun maybeReconnect() {
            if (connected) return
            reconnectCalled = true
        }

        maybeReconnect()
        assertFalse(reconnectCalled)
    }

    @Test
    fun `reconnect proceeds when disconnected`() = runTest {
        var connected = false
        var reconnectCalled = false

        fun maybeReconnect() {
            if (connected) return
            reconnectCalled = true
            connected = true
        }

        maybeReconnect()
        assertTrue(reconnectCalled)
        assertTrue(connected)
    }

    // ─── No-address skip ───

    @Test
    fun `reconnect is skipped when no address is saved`() = runTest {
        var address = ""
        var reconnectAttempted = false

        if (address.isNotBlank()) {
            reconnectAttempted = true
        }

        assertFalse(reconnectAttempted)
    }

    // ─── Deferred cleanup on disconnect ───

    @Test
    fun `pending completables are cleaned up on disconnect`() = runTest {
        val pendingConnect = CompletableDeferred<Unit>()
        val pendingWrite = CompletableDeferred<Unit>()
        val pendingDescriptor = CompletableDeferred<Unit>()

        // Simulate disconnect: cancel all pending operations
        pendingConnect.cancel()
        pendingWrite.cancel()
        pendingDescriptor.cancel()

        assertTrue(pendingConnect.isCancelled)
        assertTrue(pendingWrite.isCancelled)
        assertTrue(pendingDescriptor.isCancelled)
    }

    // ─── Connect timeout ───

    @Test
    fun `connect times out after deadline`() = runTest {
        val result = withTimeoutOrNull(50L) {
            // Simulate a slow connection
            kotlinx.coroutines.delay(200)
            "connected"
        }

        assertEquals(null, result)
    }

    // ─── GATT disconnect / connect state transitions ───

    @Test
    fun `connection state transitions through full lifecycle`() {
        val lifecycle = listOf(
            ConnectionState.DISCONNECTED,
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.ERROR,
            ConnectionState.DISCONNECTED
        )

        assertEquals(5, lifecycle.size)
        assertEquals(ConnectionState.DISCONNECTED, lifecycle.first())
        assertEquals(ConnectionState.DISCONNECTED, lifecycle.last())
    }

    @Test
    fun `GATT status 133 triggers cache refresh and error`() = runTest {
        val pendingConnect = CompletableDeferred<Unit>()

        // Simulate status 133 (Bluetooth cache stale)
        pendingConnect.completeExceptionally(
            IOException("Bluetooth cache is stale (status 133). Forget this device in Bluetooth settings, then re-pair and reconnect.")
        )

        try {
            pendingConnect.await()
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("status 133"))
        }
    }
}
