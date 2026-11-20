package com.koodoreader.engine.annotate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HighlightPaletteTest {

    @Test
    fun `legacy yellow with zero alpha paints as visible yellow`() {
        val paint = HighlightPalette.paintArgb(HighlightPalette.LEGACY_INVISIBLE_YELLOW)
        val alpha = (paint ushr 24) and 0xFF
        assertNotEquals(0L, alpha)
        assertEquals(HighlightColor.YELLOW.hex, HighlightPalette.css(HighlightPalette.LEGACY_INVISIBLE_YELLOW))
    }

    @Test
    fun `color codes paint the matching palette entry`() {
        val yellow = HighlightPalette.paintArgb(HighlightColor.YELLOW.code.toLong())
        assertEquals(HighlightPalette.argb(HighlightColor.YELLOW), yellow)
        assertEquals(HighlightColor.BLUE.hex, HighlightPalette.css(HighlightColor.BLUE.code.toLong()))
    }

    @Test
    fun `packed colors keep their rgb for reader paint`() {
        val green = 0x9988FF88L
        assertEquals(0x4D88FF88L, HighlightPalette.paintArgb(green))
        assertEquals("#88FF88", HighlightPalette.css(green))
    }

    @Test
    fun `reader paint always uses thirty percent alpha`() {
        val paint = HighlightPalette.paintArgb(0x9988FF88L)
        assertEquals(0x4D88FF88L, paint)
        assertEquals("rgba(136,255,136,0.3)", HighlightPalette.readerCss(0x9988FF88L))
    }

    // ─── Dark mode ────────────────────────────────────────────────────────────

    @Test
    fun `dark mode still applies the thirty percent alpha`() {
        val paint = HighlightPalette.paintArgb(HighlightColor.YELLOW.code.toLong(), dark = true)
        val alpha = (paint ushr 24) and 0xFF
        assertEquals(0x4DL, alpha)
    }

    @Test
    fun `dark mode uses a different tint than light mode`() {
        val light = HighlightPalette.paintArgb(HighlightColor.YELLOW.code.toLong(), dark = false)
        val dark = HighlightPalette.paintArgb(HighlightColor.YELLOW.code.toLong(), dark = true)
        assertNotEquals(light and 0xFFFFFF, dark and 0xFFFFFF)
    }

    @Test
    fun `dark mode tints are darker than the desktop day colours`() {
        // A 30%-alpha band over a near-black page is only visible if the tint
        // is itself dark; a bright tint would wash out to nothing.
        for (color in HighlightColor.entries) {
            val day = HighlightPalette.paintArgb(color.code.toLong(), dark = false) and 0xFFFFFF
            val night = HighlightPalette.paintArgb(color.code.toLong(), dark = true) and 0xFFFFFF
            assertTrue(
                luminance(night) < luminance(day),
                "${color.name} dark tint is not darker than its day colour",
            )
        }
    }

    @Test
    fun `dark mode css matches the dark canvas paint`() {
        val css = HighlightPalette.readerCss(HighlightColor.BLUE.code.toLong(), dark = true)
        val paint = HighlightPalette.paintArgb(HighlightColor.BLUE.code.toLong(), dark = true)
        val red = (paint ushr 16) and 0xFF
        val green = (paint ushr 8) and 0xFF
        val blue = paint and 0xFF
        assertEquals("rgba($red,$green,$blue,0.3)", css)
    }

    /** WCAG relative luminance of an opaque 0xRRGGBB value. */
    private fun luminance(rgb: Long): Double {
        fun channel(v: Long): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel((rgb shr 16) and 0xFF)
        val g = channel((rgb shr 8) and 0xFF)
        val b = channel(rgb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
