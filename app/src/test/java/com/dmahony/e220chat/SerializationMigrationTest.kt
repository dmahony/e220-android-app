package com.dmahony.e220chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializationMigrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `build send request returns kotlinx serialized json string`() {
        val request = E220Protocol.buildSendRequest("hello radio")

        val envelope = json.parseToJsonElement(request).jsonObject
        assertEquals("/api/send", envelope["path"]?.jsonPrimitive?.content)
        assertEquals("POST", envelope["method"]?.jsonPrimitive?.content)
        assertEquals("hello radio", envelope["message"]?.jsonPrimitive?.content)
        assertEquals("hello radio", envelope["body"]?.jsonObject?.get("message")?.jsonPrimitive?.content)
    }

    @Test
    fun `parse chat response accepts a raw json string`() {
        val response = """
            {
              "ok": true,
              "data": {
                "sequence": 7,
                "messages": ["[RX] hello", "[TX] hi back"]
              }
            }
        """.trimIndent()

        val chat = E220Protocol.parseChatResponse(response)

        assertEquals(7, chat.sequence)
        assertEquals(2, chat.messages.size)
        assertEquals("hello", chat.messages[0].text)
        assertTrue(!chat.messages[0].sent)
        assertEquals("hi back", chat.messages[1].text)
        assertTrue(chat.messages[1].sent)
    }
}
