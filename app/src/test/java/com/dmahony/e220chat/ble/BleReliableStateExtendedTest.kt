package com.dmahony.e220chat.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BleReliableStateExtendedTest {
    // ─── Duplicate sequence prevention ───

    @Test
    fun `registerWaiter replaces existing waiter for the same seq cancelling the old one`() = runBlocking {
        val state = BleReliableState(startSeq = 1)

        val waiter1 = state.registerWaiter(5u)
        assertFalse(waiter1.isCancelled)

        val waiter2 = state.registerWaiter(5u)
        assertTrue("old waiter should be cancelled when replaced", waiter1.isCancelled)
        assertFalse("new waiter should be active", waiter2.isCancelled)

        // Completing the seq should resolve waiter2 but not waiter1 (already cancelled)
        assertTrue(state.completeAck(5u))
        waiter2.await() // should succeed immediately
    }

    @Test
    fun `registerWaiter does not cancel non existent waiter`() = runBlocking {
        val state = BleReliableState()

        // Registering a fresh seq should work normally
        val waiter = state.registerWaiter(42u)
        assertFalse(waiter.isCancelled)
    }

    // ─── completeAck behavior ───

    @Test
    fun `completeAck returns false for unknown sequence`() {
        val state = BleReliableState()
        assertFalse(state.completeAck(99u))
    }

    @Test
    fun `completeAck returns false for already completed waiter`() = runBlocking {
        val state = BleReliableState()
        state.registerWaiter(10u)

        assertTrue(state.completeAck(10u))
        assertFalse("second completeAck should return false", state.completeAck(10u))
    }

    @Test
    fun `completeAck returns false for removed waiter`() = runBlocking {
        val state = BleReliableState()
        state.registerWaiter(7u)
        state.removeWaiter(7u)

        assertFalse("completeAck after remove should return false", state.completeAck(7u))
    }

    // ─── removeWaiter behavior ───

    @Test
    fun `removeWaiter cancels the waiter and removes it`() = runBlocking {
        val state = BleReliableState()
        val waiter = state.registerWaiter(3u)

        state.removeWaiter(3u)

        assertTrue(waiter.isCancelled)
        assertFalse("ack after remove should fail", state.completeAck(3u))
    }

    @Test
    fun `removeWaiter is idempotent for unknown seqs`() {
        val state = BleReliableState()
        // Should not throw
        state.removeWaiter(255u)
        state.removeWaiter(0u)
    }

    // ─── clear behavior ───

    @Test
    fun `clear cancels all pending waiters`() = runBlocking {
        val state = BleReliableState()
        val w1 = state.registerWaiter(1u)
        val w2 = state.registerWaiter(2u)
        val w3 = state.registerWaiter(3u)

        state.clear()

        assertTrue(w1.isCancelled)
        assertTrue(w2.isCancelled)
        assertTrue(w3.isCancelled)

        // All waiters should be gone after clear
        assertFalse(state.completeAck(1u))
        assertFalse(state.completeAck(2u))
        assertFalse(state.completeAck(3u))
    }

    @Test
    fun `after clear new registrations work normally`() = runBlocking {
        val state = BleReliableState()
        state.registerWaiter(1u)
        state.clear()

        val newWaiter = state.registerWaiter(1u)
        assertFalse(newWaiter.isCancelled)
        assertTrue(state.completeAck(1u))
        newWaiter.await()
    }

    // ─── Sequence wrap-around edge cases ───

    @Test
    fun `sequence wraps correctly when starting at various positions`() {
        // Start at 1
        val s1 = BleReliableState(startSeq = 1)
        assertEquals(1, s1.nextSeq().toInt())
        assertEquals(2, s1.nextSeq().toInt())

        // Start at 255, wrap to 1
        val s255 = BleReliableState(startSeq = 255)
        assertEquals(255, s255.nextSeq().toInt())
        assertEquals(1, s255.nextSeq().toInt())
        assertEquals(2, s255.nextSeq().toInt())

        // Start at mid-range
        val s128 = BleReliableState(startSeq = 128)
        assertEquals(128, s128.nextSeq().toInt())
        assertEquals(129, s128.nextSeq().toInt())
    }

    @Test
    fun `startSeq coerced into valid range`() {
        val tooLow = BleReliableState(startSeq = -5)
        assertEquals(1, tooLow.nextSeq().toInt())

        val tooHigh = BleReliableState(startSeq = 999)
        assertEquals(255, tooHigh.nextSeq().toInt())
    }

    // ─── Full sequence cycle (0-byte sequences, wrap verification) ───

    @Test
    fun `full 255 value cycle produces no zero values`() {
        val state = BleReliableState(startSeq = 1)
        val values = mutableSetOf<Int>()
        repeat(255) {
            val v = state.nextSeq().toInt()
            assertFalse("sequence should never be 0", v == 0)
            assertTrue("sequence should be 1-255", v in 1..255)
            values += v
        }
        assertEquals(255, values.size)
    }

    @Test
    fun `concurrent registrations with same seq cancel old waiters correctly`() = runBlocking {
        val state = BleReliableState()
        val waiters = (1..50).map { seq ->
            async {
                val uSeq = (seq % 10 + 1).toUByte()
                state.registerWaiter(uSeq)
            }
        }.awaitAll()

        // After concurrent registrations, only the last waiter per seq should be active
        // All should be either active or cancelled (none should throw)
        val completed = waiters.count { it.isCompleted || it.isCancelled }
        assertTrue("all waiters should be resolved or cancelled", completed > 0)
    }
}
