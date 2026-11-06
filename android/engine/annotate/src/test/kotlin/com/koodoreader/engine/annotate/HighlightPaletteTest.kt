package com.koodoreader.engine.annotate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
    fun `packed colors with alpha are kept`() {
        val green = 0x9988FF88L
        assertEquals(green, HighlightPalette.paintArgb(green))
        assertEquals("#88FF88", HighlightPalette.css(green))
    }
}
