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
}
