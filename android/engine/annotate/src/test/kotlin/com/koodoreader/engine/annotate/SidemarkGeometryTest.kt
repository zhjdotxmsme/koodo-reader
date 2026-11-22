package com.koodoreader.engine.annotate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SidemarkGeometryTest {

    @Test
    fun `the tick sits just outside the line's leading edge`() {
        val x = SidemarkGeometry.tickLeftPx(
            lineLeftPx = 40f, lineRightPx = 300f, rtl = false, pageWidthPx = 360f,
        )
        assertEquals(40f - SidemarkGeometry.GAP_PX, x, 0.001f)
    }

    @Test
    fun `rtl mirrors the tick to the trailing margin`() {
        val x = SidemarkGeometry.tickLeftPx(
            lineLeftPx = 40f, lineRightPx = 300f, rtl = true, pageWidthPx = 360f,
        )
        assertEquals(300f + SidemarkGeometry.GAP_PX, x, 0.001f)
    }

    @Test
    fun `a margin narrower than the gap keeps the tick on the page`() {
        // 20px margin, 6px gap: the naive left edge would be 14px, which is
        // still on the page — but a 4px line start would clip. Clamp inward.
        assertEquals(
            0f,
            SidemarkGeometry.tickLeftPx(
                lineLeftPx = 2f, lineRightPx = 350f, rtl = false, pageWidthPx = 360f,
            ),
            0.001f,
        )
        assertEquals(
            360f - SidemarkGeometry.WIDTH_PX,
            SidemarkGeometry.tickLeftPx(
                lineLeftPx = 2f, lineRightPx = 359f, rtl = true, pageWidthPx = 360f,
            ),
            0.001f,
        )
    }

    @Test
    fun `degenerate geometry never yields NaN`() {
        listOf(
            SidemarkGeometry.tickLeftPx(Float.NaN, 300f, rtl = false, pageWidthPx = 360f),
            SidemarkGeometry.tickLeftPx(40f, 300f, rtl = false, pageWidthPx = Float.NaN),
            SidemarkGeometry.tickLeftPx(40f, 300f, rtl = false, pageWidthPx = 0f),
        ).forEach { assertTrue(it.isFinite() && it >= 0f, "x was $it") }
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
        assertTrue((tick ushr 24) and 0xFF < (SidemarkGeometry.LIGHT_ALPHA.toLong()))
    }
}
