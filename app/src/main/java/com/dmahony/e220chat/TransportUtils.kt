package com.dmahony.e220chat

import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay

suspend fun <T> retryTransportFailure(
    block: suspend () -> T,
    onRetry: suspend () -> Unit
): T {
    var lastError: Exception? = null
    repeat(3) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            lastError = e
            val activeContext = runCatching { currentCoroutineContext()[Job]?.isActive == true }.getOrDefault(false)
            val shouldRetry =
                (e is java.util.concurrent.CancellationException && activeContext) ||
                    e is IOException ||
                    e is TimeoutCancellationException ||
                    e.message?.contains("Timed out waiting", ignoreCase = true) == true ||
                    e.message?.contains("ble", ignoreCase = true) == true ||
                    e.message?.contains("socket", ignoreCase = true) == true
            if (!shouldRetry || attempt == 2) {
                throw e
            }
            delay(500L * (attempt + 1))
            onRetry()
        }
    }
    throw lastError ?: IOException("Transport retry failed")
}

fun readLineWithTimeout(input: java.io.InputStream, timeoutMs: Long): String {
    val deadline = System.currentTimeMillis() + timeoutMs
    val output = java.io.ByteArrayOutputStream()
    while (System.currentTimeMillis() < deadline) {
        val available = try {
            input.available()
        } catch (_: Exception) {
            0
        }
        if (available <= 0) {
            if (output.size() > 0) break
            Thread.sleep(1)
            continue
        }
        val byte = input.read()
        if (byte == -1) break
        if (byte == '\n'.code) break
        if (byte != '\r'.code) output.write(byte)
    }
    return output.toString(Charsets.UTF_8.name())
}
