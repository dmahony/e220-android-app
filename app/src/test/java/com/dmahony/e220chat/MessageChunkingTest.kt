package com.dmahony.e220chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageChunkingTest {
    @Test
    fun `split message rejects non positive chunk sizes`() {
        try {
            splitMessageForRadio("hello", maxChunkBytes = 0)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("maxChunkBytes must be positive", e.message)
        }
    }

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
            assertWellFormedUtf16(chunk)
        }
    }

    @Test
    fun `split message prefers whitespace boundaries when they fit`() {
        val message = "hello world there"

        val chunks = splitMessageForRadio(message, maxChunkBytes = 12)

        assertEquals(listOf("hello world", "there"), chunks)
    }

    @Test
    fun `split message keeps emoji surrogate pairs intact at exact byte boundaries`() {
        val message = "😀😀"

        val chunks = splitMessageForRadio(message, maxChunkBytes = 4)

        assertEquals(listOf("😀", "😀"), chunks)
        chunks.forEach { chunk ->
            assertEquals(4, chunk.toByteArray(Charsets.UTF_8).size)
            assertWellFormedUtf16(chunk)
        }
    }

    @Test
    fun `split message does not split emoji when mixed with ascii`() {
        val message = "ab😀cd"

        val chunks = splitMessageForRadio(message, maxChunkBytes = 5)

        assertEquals(message, chunks.joinToString(separator = ""))
        chunks.forEach { chunk ->
            assertWellFormedUtf16(chunk)
            assertTrue(chunk.toByteArray(Charsets.UTF_8).size <= 5 || chunk == "😀")
        }
    }

    private fun assertWellFormedUtf16(value: String) {
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                Character.isHighSurrogate(ch) -> {
                    assertTrue("High surrogate at end of string", i + 1 < value.length)
                    assertTrue("High surrogate not followed by low surrogate", Character.isLowSurrogate(value[i + 1]))
                    i += 2
                }
                Character.isLowSurrogate(ch) -> {
                    throw AssertionError("Unexpected isolated low surrogate at index $i")
                }
                else -> i++
            }
        }
    }
}
