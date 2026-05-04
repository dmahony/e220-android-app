package com.dmahony.e220chat.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.IOException

internal suspend fun runAckRetry(
    initialSeq: UByte,
    maxRetry: Int,
    timeoutMs: Long,
    nextSeq: () -> UByte,
    registerWaiter: (UByte) -> CompletableDeferred<Unit>,
    removeWaiter: (UByte) -> Unit,
    sendAttempt: suspend (attempt: Int, seq: UByte) -> Unit
) {
    require(maxRetry >= 1) { "maxRetry must be at least 1" }

    var attempt = 0
    var seq = initialSeq
    while (true) {
        attempt += 1
        val attemptSeq = seq
        val waiter = registerWaiter(attemptSeq)
        try {
            sendAttempt(attempt, attemptSeq)
            val acked = runCatching { withTimeout(timeoutMs) { waiter.await() } }.isSuccess
            if (acked) return
            if (attempt >= maxRetry) {
                throw IOException("ACK timeout seq=$attemptSeq")
            }
            seq = nextSeq()
        } finally {
            removeWaiter(attemptSeq)
        }
    }
}
