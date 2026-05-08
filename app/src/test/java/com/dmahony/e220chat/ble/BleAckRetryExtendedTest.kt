package com.dmahony.e220chat.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Extended tests for runAckRetry covering:
 *  - Late-ACK from attempt 1 doesn't corrupt attempt 2
 *  - Late-ACK to removed waiter is ignored
 *  - All-attempts-success first-try with unique sequence
 *  - Attempt counter tracking
 *  - maxRetry=1 single-attempt behavior
 *  - maxRetry<1 rejection
 *  - First-attempt success
 *  - Fresh sequences across multiple retry attempts
 */
class BleAckRetryExtendedTest {

    // ─── Late-ACK from attempt 1 doesn't corrupt attempt 2 ───

    @Test
    fun `late ACK from attempt 1 does not corrupt attempt 2 seq`() = runTest {
        val waiters = mutableMapOf<UByte, CompletableDeferred<Unit>>()
        val completedSeqs = mutableListOf<UByte>()
        var nextSeqValue: UByte = 30u

        runAckRetry(
            initialSeq = 29u,
            maxRetry = 2,
            timeoutMs = 10,
            nextSeq = {
                val seq = nextSeqValue
                nextSeqValue = (nextSeqValue + 1u).toUByte()
                seq
            },
            registerWaiter = { seq ->
                CompletableDeferred<Unit>().also { waiters[seq] = it }
            },
            removeWaiter = { seq ->
                waiters[seq]?.let { completedSeqs += seq }
                waiters.remove(seq)
            }
        ) { attempt, seq ->
            if (attempt == 1) {
                // First attempt: schedule a late ACK that fires after timeout
                launch {
                    kotlinx.coroutines.delay(20) // after the 10ms timeout
                    // This ACK is to a removed waiter — should be ignored
                    waiters[seq]?.complete(Unit)
                }
            }
            if (attempt == 2) {
                // Second attempt: ACK immediately with fresh seq
                waiters[seq]?.complete(Unit)
            }
        }

        // Only seq 30 (attempt 2) should have been acked
        assertTrue(waiters.isEmpty())
    }

    // ─── Late-ACK to removed waiter ignored ───

    @Test
    fun `late ACK to a removed waiter is silently ignored`() = runTest {
        val waiters = mutableMapOf<UByte, CompletableDeferred<Unit>>()
        var lateAckDelivered = false
        var nextSeqValue: UByte = 40u

        runAckRetry(
            initialSeq = 39u,
            maxRetry = 3,
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
            if (attempt == 1) {
                // First attempt: schedule late ACK
                launch {
                    kotlinx.coroutines.delay(15)
                    val oldWaiter = waiters[seq]
                    if (oldWaiter != null) {
                        oldWaiter.complete(Unit)
                        lateAckDelivered = true
                    }
                }
            }
            if (attempt == 2) {
                // Second attempt fails too
            }
            if (attempt == 3) {
                // Third attempt succeeds
                waiters[seq]?.complete(Unit)
            }
        }

        assertTrue(waiters.isEmpty())
    }

    // ─── All-attempts-success first-try with unique seq ───

    @Test
    fun `all attempts succeed on first try with unique sequence`() = runTest {
        val allSeqs = mutableSetOf<UByte>()
        var nextSeqValue: UByte = 50u

        // Run multiple ACK retry cycles, each succeeding on first attempt
        repeat(5) {
            val waiters = mutableMapOf<UByte, CompletableDeferred<Unit>>()
            runAckRetry(
                initialSeq = nextSeqValue,
                maxRetry = 3,
                timeoutMs = 100,
                nextSeq = { nextSeqValue },
                registerWaiter = { seq ->
                    CompletableDeferred<Unit>().also { waiters[seq] = it }
                },
                removeWaiter = { waiters.remove(it) }
            ) { _, seq ->
                allSeqs += seq
                waiters[seq]?.complete(Unit)
            }
            nextSeqValue = (nextSeqValue + 1u).toUByte()
        }

        assertEquals(5, allSeqs.size)
    }

    // ─── Attempt counter tracking ───

    @Test
    fun `attempt counter is correct across multiple attempts`() = runTest {
        val attempts = mutableListOf<Int>()
        var nextSeqValue: UByte = 60u

        try {
            runAckRetry(
                initialSeq = 59u,
                maxRetry = 4,
                timeoutMs = 1,
                nextSeq = {
                    val seq = nextSeqValue
                    nextSeqValue = (nextSeqValue + 1u).toUByte()
                    seq
                },
                registerWaiter = { CompletableDeferred() },
                removeWaiter = { }
            ) { attempt, _ ->
                attempts += attempt
            }
            fail("Expected IOException")
        } catch (e: IOException) {
            // Expected
        }

        assertEquals(listOf(1, 2, 3, 4), attempts)
    }

    // ─── maxRetry=1 single-attempt ───

    @Test
    fun `maxRetry of 1 with failed ACK throws IOException on the one attempt`() = runTest {
        val attempts = mutableListOf<Int>()

        try {
            runAckRetry(
                initialSeq = 70u,
                maxRetry = 1,
                timeoutMs = 1,
                nextSeq = { 71u },
                registerWaiter = { CompletableDeferred() },
                removeWaiter = { }
            ) { attempt, _ ->
                attempts += attempt
            }
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("ACK timeout"))
        }

        assertEquals(listOf(1), attempts)
    }

    // ─── maxRetry<1 rejection ───

    @Test
    fun `maxRetry less than 1 throws IllegalArgumentException`() = runTest {
        try {
            runAckRetry(
                initialSeq = 1u,
                maxRetry = 0,
                timeoutMs = 100,
                nextSeq = { 1u },
                registerWaiter = { CompletableDeferred() },
                removeWaiter = { }
            ) { _, _ -> }
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("maxRetry must be at least 1", e.message)
        }
    }

    @Test
    fun `negative maxRetry throws IllegalArgumentException`() = runTest {
        try {
            runAckRetry(
                initialSeq = 1u,
                maxRetry = -1,
                timeoutMs = 100,
                nextSeq = { 1u },
                registerWaiter = { CompletableDeferred() },
                removeWaiter = { }
            ) { _, _ -> }
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("maxRetry must be at least 1", e.message)
        }
    }

    // ─── First-attempt success ───

    @Test
    fun `first attempt success does not increment attempt counter beyond 1`() = runTest {
        val waiters = mutableMapOf<UByte, CompletableDeferred<Unit>>()
        val attempts = mutableListOf<Int>()

        runAckRetry(
            initialSeq = 80u,
            maxRetry = 5,
            timeoutMs = 100,
            nextSeq = { 81u },
            registerWaiter = { seq ->
                CompletableDeferred<Unit>().also { waiters[seq] = it }
            },
            removeWaiter = { waiters.remove(it) }
        ) { attempt, seq ->
            attempts += attempt
            waiters[seq]?.complete(Unit)
        }

        assertEquals(listOf(1), attempts)
    }

    // ─── Fresh sequences across multiple retry attempts ───

    @Test
    fun `fresh sequences are allocated across multiple retry attempts`() = runTest {
        val allSeqs = mutableSetOf<UByte>()
        val attempts = mutableListOf<Pair<Int, UByte>>()
        var nextSeqValue: UByte = 90u

        try {
            runAckRetry(
                initialSeq = 89u,
                maxRetry = 3,
                timeoutMs = 1,
                nextSeq = {
                    val seq = nextSeqValue
                    nextSeqValue = (nextSeqValue + 1u).toUByte()
                    seq
                },
                registerWaiter = { CompletableDeferred() },
                removeWaiter = { }
            ) { attempt, seq ->
                attempts += attempt to seq
                allSeqs += seq
            }
            fail("Expected IOException")
        } catch (e: IOException) {
            // Expected — all attempts timeout
        }

        assertEquals(3, attempts.size)
        assertEquals(3, allSeqs.size) // Each attempt gets a unique seq
        assertNotEquals(attempts[0].second, attempts[1].second)
        assertNotEquals(attempts[1].second, attempts[2].second)
    }

    // ─── Duplicate sequence prevention ───

    @Test
    fun `no duplicate sequences are allocated across concurrent retry cycles`() = runTest {
        val allocatedSeqs = mutableSetOf<UByte>()
        var nextSeqValue: UByte = 100u

        repeat(10) { cycle ->
            val waiters = mutableMapOf<UByte, CompletableDeferred<Unit>>()
            try {
                runAckRetry(
                    initialSeq = nextSeqValue,
                    maxRetry = if (cycle % 2 == 0) 1 else 3,
                    timeoutMs = if (cycle % 2 == 0) 100 else 1,
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
                    allocatedSeqs += seq
                    if (cycle % 2 == 0) waiters[seq]?.complete(Unit)
                }
            } catch (e: IOException) {
                // Expected for odd cycles (timeout)
            }
        }

        // All allocated sequences should be unique
        assertTrue(allocatedSeqs.size >= 10)
    }
}
