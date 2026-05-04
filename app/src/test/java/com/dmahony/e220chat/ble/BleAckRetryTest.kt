package com.dmahony.e220chat.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BleAckRetryTest {
    @Test
    fun `runAckRetry retries with a fresh seq and ignores a late ack from the first attempt`() = runTest {
        val waiters = mutableMapOf<UByte, CompletableDeferred<Unit>>()
        val attempts = mutableListOf<Pair<Int, UByte>>()
        var nextSeqValue: UByte = 8u

        runAckRetry(
            initialSeq = 7u,
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
            removeWaiter = { waiters.remove(it) }
        ) { attempt, seq ->
            attempts += attempt to seq
            when (attempt) {
                1 -> launch {
                    delay(15)
                    waiters[seq]?.complete(Unit)
                }
                2 -> launch {
                    delay(5)
                    waiters[seq]?.complete(Unit)
                }
            }
        }

        assertEquals(listOf(1 to 7u.toUByte(), 2 to 8u.toUByte()), attempts)
        assertTrue(waiters.isEmpty())
    }

    @Test
    fun `runAckRetry fails after the configured number of attempts`() = runTest {
        val attempts = mutableListOf<Int>()
        var nextSeqValue: UByte = 10u

        try {
            runAckRetry(
                initialSeq = 9u,
                maxRetry = 3,
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
            throw AssertionError("Expected IOException")
        } catch (e: IOException) {
            assertEquals("ACK timeout seq=11", e.message)
        }

        assertEquals(listOf(1, 2, 3), attempts)
    }
}
