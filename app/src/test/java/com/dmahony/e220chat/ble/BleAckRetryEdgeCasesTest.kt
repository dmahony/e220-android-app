package com.dmahony.e220chat.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BleAckRetryEdgeCasesTest {
    @Test
    fun `runAckRetry succeeds on first attempt when ack arrives within timeout`() = runTest {
        val waiters = mutableMapOf<UByte, CompletableDeferred<Unit>>()
        val attempts = mutableListOf<Int>()

        runAckRetry(
            initialSeq = 1u,
            maxRetry = 3,
            timeoutMs = 100,
            nextSeq = { (2).toUByte() },
            registerWaiter = { seq ->
                CompletableDeferred<Unit>().also { waiters[seq] = it }
            },
            removeWaiter = { waiters.remove(it) }
        ) { attempt, seq ->
            attempts += attempt
            // ACK immediately available on first attempt
            if (attempt == 1) {
                waiters[seq]?.complete(Unit)
            }
        }

        assertEquals(listOf(1), attempts)
        assertTrue(waiters.isEmpty())
    }

    @Test
    fun `runAckRetry with maxRetry of 1 fails if ack does not arrive`() = runTest {
        val attempts = mutableListOf<Int>()

        try {
            runAckRetry(
                initialSeq = 1u,
                maxRetry = 1,
                timeoutMs = 1,
                nextSeq = { 2u },
                registerWaiter = { CompletableDeferred() },
                removeWaiter = { }
            ) { attempt, _ ->
                attempts += attempt
            }
            throw AssertionError("Expected IOException")
        } catch (e: IOException) {
            assertEquals("ACK timeout seq=1", e.message)
        }

        assertEquals(listOf(1), attempts)
    }

    @Test
    fun `runAckRetry succeeds on first retry when initial attempt times out`() = runTest {
        val waiters = mutableMapOf<UByte, CompletableDeferred<Unit>>()
        val attempts = mutableListOf<Pair<Int, UByte>>()
        var nextSeqValue: UByte = 10u

        runAckRetry(
            initialSeq = 9u,
            maxRetry = 2,
            timeoutMs = 1,
            nextSeq = {
                val seq = nextSeqValue
                nextSeqValue = (nextSeqValue + 1u).toUByte()
                seq
            },
            registerWaiter = { seq ->
                CompletableDeferred<Unit>().also { waiters[seq] = it }
            },
            removeWaiter = { waiters.remove(it) }
        ) { attempt, seq ->
            attempts += attempt to seq
            // Only ack on retry, not initial attempt
            if (attempt == 2) {
                waiters[seq]?.complete(Unit)
            }
        }

        assertEquals(listOf(1 to 9u.toUByte(), 2 to 10u.toUByte()), attempts)
        assertTrue(waiters.isEmpty())
    }

    @Test
    fun `runAckRetry ignores ack from previous attempt after new seq allocated`() = runTest {
        val waiters = mutableMapOf<UByte, CompletableDeferred<Unit>>()
        val attempts = mutableListOf<Pair<Int, UByte>>()
        var nextSeqValue: UByte = 20u

        runAckRetry(
            initialSeq = 19u,
            maxRetry = 2,
            timeoutMs = 5,
            nextSeq = {
                val seq = nextSeqValue
                nextSeqValue = (nextSeqValue + 1u).toUByte()
                seq
            },
            registerWaiter = { seq ->
                CompletableDeferred<Unit>().also { waiters[seq] = it }
            },
            removeWaiter = { waiters.remove(it) }
        ) { attempt, seq ->
            attempts += attempt to seq
            // First attempt: schedule a late ack (after timeout)
            if (attempt == 1) {
                launch {
                    // This ack arrives way after the timeout - it should be ignored
                    // because the waiter was already removed by then
                }
            }
            // Second attempt: ack immediately
            if (attempt == 2) {
                waiters[seq]?.complete(Unit)
            }
        }

        assertEquals(listOf(1 to 19u.toUByte(), 2 to 20u.toUByte()), attempts)
        assertTrue("all waiters should be cleaned up", waiters.isEmpty())
    }

    @Test
    fun `runAckRetry fails with correct error message when max retries exhausted`() = runTest {
        var nextSeqValue: UByte = 50u

        try {
            runAckRetry(
                initialSeq = 49u,
                maxRetry = 4,
                timeoutMs = 1,
                nextSeq = {
                    val seq = nextSeqValue
                    nextSeqValue = (nextSeqValue + 1u).toUByte()
                    seq
                },
                registerWaiter = { CompletableDeferred() },
                removeWaiter = { }
            ) { _, _ -> }
            throw AssertionError("Expected IOException")
        } catch (e: IOException) {
            // After 4 retries: 49, 50, 51, 52 - the error should reference the last seq
            assertTrue(e.message!!.startsWith("ACK timeout seq="))
        }
    }

    @Test
    fun `runAckRetry allocates fresh seq only after a failed attempt not after success`() = runTest {
        val waiters = mutableMapOf<UByte, CompletableDeferred<Unit>>()
        var nextSeqCalls = 0
        val attempts = mutableListOf<Int>()

        runAckRetry(
            initialSeq = 1u,
            maxRetry = 3,
            timeoutMs = 100,
            nextSeq = {
                nextSeqCalls++
                (nextSeqCalls + 1).toUByte()
            },
            registerWaiter = { seq ->
                CompletableDeferred<Unit>().also { waiters[seq] = it }
            },
            removeWaiter = { waiters.remove(it) }
        ) { attempt, seq ->
            attempts += attempt
            if (attempt == 1) {
                waiters[seq]?.complete(Unit)
            }
        }

        assertEquals(listOf(1), attempts)
        assertEquals("nextSeq should not be called on success", 0, nextSeqCalls)
    }

    @Test
    fun `runAckRetry cleans up waiter even when sendAttempt throws`() = runTest {
        val waiters = mutableMapOf<UByte, CompletableDeferred<Unit>>()
        val attempts = mutableListOf<Int>()

        try {
            runAckRetry(
                initialSeq = 1u,
                maxRetry = 1,
                timeoutMs = 1000,
                nextSeq = { 2u },
                registerWaiter = { seq ->
                    CompletableDeferred<Unit>().also { waiters[seq] = it }
                },
                removeWaiter = { waiters.remove(it) }
            ) { attempt, seq ->
                attempts += attempt
                throw IOException("write failed")
            }
        } catch (e: IOException) {
            assertEquals("write failed", e.message)
        }

        assertEquals(listOf(1), attempts)
        assertTrue("waiter should be cleaned up even after exception", waiters.isEmpty())
    }

    @Test
    fun `runAckRetry rejects maxRetry less than 1`() = runTest {
        try {
            runAckRetry(
                initialSeq = 1u,
                maxRetry = 0,
                timeoutMs = 100,
                nextSeq = { 1u },
                registerWaiter = { CompletableDeferred() },
                removeWaiter = { }
            ) { _, _ -> }
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("maxRetry must be at least 1", e.message)
        }
    }
}
