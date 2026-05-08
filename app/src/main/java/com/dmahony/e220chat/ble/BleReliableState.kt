package com.dmahony.e220chat.ble

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

internal class BleReliableState(startSeq: Int = 1) {
    private val lock = Any()
    private var nextSeqValue: Int = startSeq.coerceIn(1, 255)
    private val ackWaiters = ConcurrentHashMap<UByte, CompletableDeferred<Unit>>()

    fun nextSeq(): UByte = synchronized(lock) {
        val seq = nextSeqValue
        nextSeqValue = if (nextSeqValue >= 255) 1 else nextSeqValue + 1
        seq.toUByte()
    }

    fun registerWaiter(seq: UByte): CompletableDeferred<Unit> {
        val waiter = CompletableDeferred<Unit>()
        ackWaiters.put(seq, waiter)?.cancel()
        return waiter
    }

    fun completeAck(seq: UByte): Boolean {
        return ackWaiters[seq]?.complete(Unit) == true
    }

    fun removeWaiter(seq: UByte) {
        ackWaiters.remove(seq)?.cancel()
    }

    fun clear() {
        ackWaiters.values.forEach { it.cancel() }
        ackWaiters.clear()
    }
}
