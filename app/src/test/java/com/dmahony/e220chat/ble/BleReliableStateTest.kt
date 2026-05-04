package com.dmahony.e220chat.ble

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleReliableStateTest {
    @Test
    fun `next seq is unique across concurrent callers and never returns zero`() = runBlocking {
        val state = BleReliableState()

        val seqs = (1..128).map {
            async {
                state.nextSeq()
            }
        }.awaitAll()

        assertEquals(128, seqs.toSet().size)
        assertFalse(seqs.contains(0u))
    }

    @Test
    fun `next seq wraps from 255 back to 1`() {
        val state = BleReliableState(startSeq = 254)

        assertEquals(254.toUByte(), state.nextSeq())
        assertEquals(255.toUByte(), state.nextSeq())
        assertEquals(1.toUByte(), state.nextSeq())
        assertEquals(2.toUByte(), state.nextSeq())
    }

    @Test
    fun `ack waiter completes from a different thread and ignores unknown seqs`() = runBlocking {
        val state = BleReliableState()
        val waiter = state.registerWaiter(7u)

        assertFalse(state.completeAck(8u))
        assertTrue(state.completeAck(7u))
        waiter.await()
    }
}
