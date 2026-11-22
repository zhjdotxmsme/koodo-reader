package com.koodoreader.engine.annotate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SidemarkGeometryTest {

    @Test
    fun `the tick sits in the margin left of the text column`() {
        val x = SidemarkGeometry.tickLeftPxOrNull(
            lineLeftPx = 44f, lineRightPx = 320f, pageWidthPx = 360f,
        )
        assertEquals(44f - SidemarkGeometry.GAP_PX - SidemarkGeometry.WIDTH_PX, x)
    }

    @Test
    fun `an indented first line puts the tick at the same x as a flush line`() {
        // A first line reports an ink x two characters further right; the tick
        // must still line up with the column, or it looks attached to the word.
        val flush = SidemarkGeometry.tickLeftPxOrNull(
            lineLeftPx = 44f, lineRightPx = 320f, pageWidthPx = 360f,
        )
        val indented = SidemarkGeometry.tickLeftPxOrNull(
            lineLeftPx = 78f, lineRightPx = 320f, indentPx = 34f, pageWidthPx = 360f,
        )
        assertEquals(flush, indented)
    }

    @Test
    fun `the tick does not follow a selection into the middle of a line`() {
        // The old bug: the tick was placed relative to the highlighted run, so
        // a word selected mid-line put it at the word, not in the margin.
        val columnLeft = 44f
        val midLineRun = 180f
        val atColumn = SidemarkGeometry.tickLeftPxOrNull(
            lineLeftPx = columnLeft, lineRightPx = 320f, pageWidthPx = 360f,
        )
        val atRun = SidemarkGeometry.tickLeftPxOrNull(
            lineLeftPx = midLineRun, lineRightPx = 240f, pageWidthPx = 360f,
        )
        assertTrue(atRun!! > atColumn!!, "a run-anchored tick would sit inside the column")
        assertEquals(columnLeft - 5f, atColumn, 0.001f)
    }

    @Test
    fun `rtl mirrors the tick to the right margin`() {
        // `rightPx` already includes the justification stretch, so the margin
        // starts there — adding the indent again would push the tick off it.
        val x = SidemarkGeometry.tickLeftPxOrNull(
            lineLeftPx = 44f, lineRightPx = 316f, rtl = true, pageWidthPx = 360f,
        )
        assertEquals(316f + SidemarkGeometry.GAP_PX, x)
        val indented = SidemarkGeometry.tickLeftPxOrNull(
            lineLeftPx = 78f, lineRightPx = 316f, indentPx = 34f, rtl = true,
            pageWidthPx = 360f,
        )
        assertEquals(x, indented)
    }

    @Test
    fun `a margin narrower than the tick draws no mark at all`() {
        // Drawing it anyway would paint it on the glyphs.
        assertNull(
            SidemarkGeometry.tickLeftPxOrNull(
                lineLeftPx = 4f, lineRightPx = 340f, pageWidthPx = 360f,
            ),
        )
        assertNull(
            SidemarkGeometry.tickLeftPxOrNull(
                lineLeftPx = 4f, lineRightPx = 356f, rtl = true, pageWidthPx = 360f,
            ),
        )
    }

    @Test
    fun `degenerate geometry yields no mark, never NaN`() {
        listOf(
            SidemarkGeometry.tickLeftPxOrNull(Float.NaN, 320f, pageWidthPx = 360f),
            SidemarkGeometry.tickLeftPxOrNull(44f, 320f, indentPx = Float.NaN),
            SidemarkGeometry.tickLeftPxOrNull(44f, 320f, pageWidthPx = Float.NaN),
            SidemarkGeometry.tickLeftPxOrNull(44f, 320f, pageWidthPx = 0f),
        ).forEach { x -> assertTrue(x == null || x.isFinite()) }
    }

    @Test
    fun `the tick keeps the highlight's hue and drops its wash alpha`() {
        val band = 0x4DFFFF00L // 30% yellow, as the canvas paints it
        val tick = SidemarkGeometry.tickArgb(band, dark = false)
        assertEquals(band and 0xFFFFFF, tick and 0xFFFFFF, "hue must survive")
        assertEquals(SidemarkGeometry.LIGHT_ALPHA.toLong(), (tick ushr 24) and 0xFF)
    }

    @Test
    fun `a night page gets a softer tick`() {
        val band = 0x4DB8860BL
        val tick = SidemarkGeometry.tickArgb(band, dark = true)
        assertEquals(SidemarkGeometry.DARK_ALPHA.toLong(), (tick ushr 24) and 0xFF)
        assertTrue((tick ushr 24) and 0xFF < SidemarkGeometry.LIGHT_ALPHA.toLong())
    }
}
