package com.dmahony.e220chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageChunkingTest {
    @Test
    fun `split message returns original text when it fits`() {
        val message = "short radio message"

        val chunks = splitMessageForRadio(message, maxChunkBytes = 160)

        assertEquals(listOf(message), chunks)
    }

    @Test
    fun `split message breaks long text into byte-safe chunks`() {
        val message = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        val chunks = splitMessageForRadio(message, maxChunkBytes = 24)

        assertTrue(chunks.size > 1)
        assertEquals(message, chunks.joinToString(separator = ""))
        chunks.forEach { chunk ->
            assertTrue(chunk.toByteArray(Charsets.UTF_8).size <= 24)
        }
    }

    @Test
    fun `split message prefers whitespace boundaries when they fit`() {
        val message = "hello world there"

        val chunks = splitMessageForRadio(message, maxChunkBytes = 12)

        assertEquals(listOf("hello world", "there"), chunks)
    }
}