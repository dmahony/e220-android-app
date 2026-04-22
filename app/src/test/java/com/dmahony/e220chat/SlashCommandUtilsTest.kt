package com.dmahony.e220chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandUtilsTest {
    @Test
    fun `build maps link uses google maps url with coordinates`() {
        val link = buildMapsLink(37.4219983, -122.084)

        assertEquals("https://maps.google.com/?q=37.4219983,-122.084", link)
    }

    @Test
    fun `build gps message prefixes the location link`() {
        val message = buildGpsMessage(37.4219983, -122.084)

        assertEquals(
            "My location: https://maps.google.com/?q=37.4219983,-122.084",
            message
        )
    }

    @Test
    fun `extract clickable message links finds maps urls in text`() {
        val links = extractClickableMessageLinks("My location: https://maps.google.com/?q=37.4219983,-122.084")

        assertEquals(1, links.size)
        assertEquals("https://maps.google.com/?q=37.4219983,-122.084", links.single().url)
        assertTrue(links.single().start < links.single().end)
    }
}
