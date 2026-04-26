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
            val ch = message[end]
            val charBytes = ch.toString().toByteArray(Charsets.UTF_8).size
            if (byteCount + charBytes > maxChunkBytes) break
            byteCount += charBytes
            end++
            if (ch.isWhitespace()) {
                lastWhitespaceEnd = end
            }
        }

        if (end == index) {
            // A single code point exceeded the limit. Emit it alone so progress continues.
            end = (index + 1).coerceAtMost(message.length)
        } else if (end < message.length && lastWhitespaceEnd > index) {
            end = lastWhitespaceEnd
        }

        val chunk = message.substring(index, end).trim()
        if (chunk.isNotEmpty()) {
            chunks += chunk
        }

        index = end
        while (index < message.length && message[index].isWhitespace()) {
            index++
        }
    }

    return if (chunks.isEmpty()) listOf(message.trim()) else chunks
}