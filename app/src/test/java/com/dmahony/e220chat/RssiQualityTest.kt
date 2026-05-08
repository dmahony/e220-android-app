package com.dmahony.e220chat

import org.junit.Assert.assertEquals
import org.junit.Test

class RssiQualityTest {

    @Test
    fun `getStaticRssiQuality returns Excellent for strong signals`() {
        assertEquals("Excellent", E220ChatViewModel.getStaticRssiQuality(-40))
        assertEquals("Excellent", E220ChatViewModel.getStaticRssiQuality(-55))
    }

    @Test
    fun `getStaticRssiQuality returns Good for moderate signals`() {
        assertEquals("Good", E220ChatViewModel.getStaticRssiQuality(-56))
        assertEquals("Good", E220ChatViewModel.getStaticRssiQuality(-70))
    }

    @Test
    fun `getStaticRssiQuality returns Fair for weak signals`() {
        assertEquals("Fair", E220ChatViewModel.getStaticRssiQuality(-71))
        assertEquals("Fair", E220ChatViewModel.getStaticRssiQuality(-85))
    }

    @Test
    fun `getStaticRssiQuality returns Weak for very weak signals`() {
        assertEquals("Weak", E220ChatViewModel.getStaticRssiQuality(-86))
        assertEquals("Weak", E220ChatViewModel.getStaticRssiQuality(-100))
    }

    @Test
    fun `getStaticRssiQuality returns None for no signal`() {
        assertEquals("None", E220ChatViewModel.getStaticRssiQuality(-101))
        assertEquals("None", E220ChatViewModel.getStaticRssiQuality(-200))
    }

    @Test
    fun `getStaticRssiQualityColor returns green for strong signals`() {
        assertEquals("green", E220ChatViewModel.getStaticRssiQualityColor(-40))
        assertEquals("green", E220ChatViewModel.getStaticRssiQualityColor(-55))
    }

    @Test
    fun `getStaticRssiQualityColor returns yellow-green for moderate signals`() {
        assertEquals("yellow-green", E220ChatViewModel.getStaticRssiQualityColor(-56))
    }

    @Test
    fun `getStaticRssiQualityColor returns yellow for fair signals`() {
        assertEquals("yellow", E220ChatViewModel.getStaticRssiQualityColor(-71))
    }

    @Test
    fun `getStaticRssiQualityColor returns red for weak signals`() {
        assertEquals("red", E220ChatViewModel.getStaticRssiQualityColor(-86))
    }

    @Test
    fun `getStaticRssiQualityColor returns gray for no signal`() {
        assertEquals("gray", E220ChatViewModel.getStaticRssiQualityColor(-101))
    }
}
