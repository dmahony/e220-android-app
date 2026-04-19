package com.dmahony.e220chat

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportRetryTest {
    @Test
    fun `retryTransportFailure retries once after io exception`() = runBlocking {
        var attempts = 0
        var retried = 0

        val result = retryTransportFailure(
            block = {
                attempts += 1
                if (attempts == 1) throw IOException("socket closed")
                "ok"
            },
            onRetry = { retried += 1 }
        )

        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(1, retried)
    }

    @Test
    fun `retryTransportFailure does not retry non transport exceptions`() = runBlocking {
        var retried = false

        try {
            retryTransportFailure(
                block = { throw IllegalStateException("bad state") },
                onRetry = { retried = true }
            )
        } catch (e: IllegalStateException) {
            assertEquals("bad state", e.message)
        }

        assertTrue(!retried)
    }

    @Test
    fun `readLineWithTimeout reads newline terminated response`() {
        val input = ByteArrayInputStream("{\"ok\":true}\n".toByteArray())
        val line = readLineWithTimeout(input, 100)
        assertEquals("{\"ok\":true}", line)
    }
}
