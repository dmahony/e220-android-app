package com.dmahony.e220chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesModelTest {

    @Test
    fun `ThemeMode has four values`() {
        assertEquals(4, ThemeMode.values().size)
    }

    @Test
    fun `ThemeMode labels are non-empty`() {
        ThemeMode.values().forEach {
            assertTrue("ThemeMode.${it.name} label is blank", it.label.isNotBlank())
        }
    }

    @Test
    fun `FontScale has four values`() {
        assertEquals(4, FontScale.values().size)
    }

    @Test
    fun `FontScale labels are non-empty`() {
        FontScale.values().forEach {
            assertTrue("FontScale.${it.name} label is blank", it.label.isNotBlank())
        }
    }

    @Test
    fun `FontScale multipliers are in ascending order`() {
        val multipliers = FontScale.values().map { it.multiplier }
        for (i in 1 until multipliers.size) {
            assertTrue(
                "FontScale multiplier at $i (${multipliers[i]}) not > previous (${multipliers[i - 1]})",
                multipliers[i] > multipliers[i - 1]
            )
        }
    }

    @Test
    fun `FontScale fromMultiplier returns correct value`() {
        assertEquals(FontScale.SMALL, FontScale.fromMultiplier(FontScale.SMALL.multiplier))
        assertEquals(FontScale.NORMAL, FontScale.fromMultiplier(FontScale.NORMAL.multiplier))
        assertEquals(FontScale.LARGE, FontScale.fromMultiplier(FontScale.LARGE.multiplier))
        assertEquals(FontScale.LARGER, FontScale.fromMultiplier(FontScale.LARGER.multiplier))
    }

    @Test
    fun `FontScale fromMultiplier returns closest match for extreme values`() {
        // fromMultiplier uses minByOrNull (closest absolute difference)
        assertEquals(FontScale.SMALL, FontScale.fromMultiplier(0.0f))
        assertEquals(FontScale.LARGER, FontScale.fromMultiplier(99.0f))
    }

    @Test
    fun `DeliveryStatus has six values`() {
        assertEquals(6, DeliveryStatus.values().size)
    }

    @Test
    fun `ChatMessage defaults senderName to empty`() {
        val msg = ChatMessage(text = "test", sent = true, senderName = "")
        assertEquals("", msg.senderName)
    }

    @Test
    fun `ChatMessage deliveryStatus is SENT for sent messages`() {
        val msg = ChatMessage(text = "test", sent = true)
        assertEquals(DeliveryStatus.SENT, msg.deliveryStatus)
    }

    @Test
    fun `ChatMessage deliveryStatus is CONFIRMED for received messages`() {
        val msg = ChatMessage(text = "test", sent = false)
        assertEquals(DeliveryStatus.CONFIRMED, msg.deliveryStatus)
    }
}
