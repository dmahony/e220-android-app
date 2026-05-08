package com.dmahony.e220chat

private const val DEFAULT_RADIO_CHUNK_BYTES = 64

fun splitMessageForRadio(message: String, maxChunkBytes: Int = DEFAULT_RADIO_CHUNK_BYTES): List<String> {
    require(maxChunkBytes > 0) { "maxChunkBytes must be positive" }

    val chunks = mutableListOf<String>()
    var index = 0

    while (index < message.length) {
        var end = index
        var byteCount = 0
        var lastWhitespaceEnd = -1

        while (end < message.length) {
            val codePoint = Character.codePointAt(message, end)
            val charCount = Character.charCount(codePoint)
            val codePointBytes = utf8ByteCount(codePoint)
            if (byteCount + codePointBytes > maxChunkBytes) break
            byteCount += codePointBytes
            end += charCount
            if (Character.isWhitespace(codePoint)) {
                lastWhitespaceEnd = end
            }
        }

        if (end == index) {
            // A single code point exceeded the limit. Emit it alone so progress continues.
            end = (index + Character.charCount(Character.codePointAt(message, index))).coerceAtMost(message.length)
        } else if (end < message.length && lastWhitespaceEnd > index) {
            end = lastWhitespaceEnd
        }

        val chunk = message.substring(index, end).trim()
        if (chunk.isNotEmpty()) {
            chunks += chunk
        }

        index = end
        while (index < message.length) {
            val codePoint = Character.codePointAt(message, index)
            if (!Character.isWhitespace(codePoint)) break
            index += Character.charCount(codePoint)
        }
    }

    return if (chunks.isEmpty()) listOf(message.trim()) else chunks
}

private fun utf8ByteCount(codePoint: Int): Int = when {
    codePoint <= 0x7F -> 1
    codePoint <= 0x7FF -> 2
    codePoint <= 0xFFFF -> 3
    else -> 4
}
