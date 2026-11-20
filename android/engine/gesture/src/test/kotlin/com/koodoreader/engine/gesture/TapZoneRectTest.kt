package com.koodoreader.engine.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the ratio-based tap-zone geometry (KOReader `getTapZones` model).
 *
 * The old Android reader hard-coded a 1/3 split on x only; these tests exist
 * so the fourth variant, the independent band widths, mirroring, and the
 * clamp can never silently regress into that.
 */
class TapZoneRectTest {

    private val w = 1000f
    private val h = 2000f

    @Test
    fun `edges layout keeps left prev right next and centre chrome`() {
        assertEquals(TapAction.PREV_PAGE, TapZoneGeometry.resolve(50f, 1000f, w, h))
        assertEquals(TapAction.NEXT_PAGE, TapZoneGeometry.resolve(950f, 1000f, w, h))
        assertEquals(TapAction.NONE, TapZoneGeometry.resolve(500f, 1000f, w, h))
    }

    @Test
    fun `edges layout honours independent band widths`() {
        // Narrow previous band (10%), wide next band (40%).
        assertEquals(
            TapAction.NONE,
            TapZoneGeometry.resolve(150f, 1000f, w, h, forwardRatio = 0.40f, backwardRatio = 0.10f),
        )
        assertEquals(
            TapAction.PREV_PAGE,
            TapZoneGeometry.resolve(90f, 1000f, w, h, forwardRatio = 0.40f, backwardRatio = 0.10f),
        )
        assertEquals(
            TapAction.NEXT_PAGE,
            TapZoneGeometry.resolve(700f, 1000f, w, h, forwardRatio = 0.40f, backwardRatio = 0.10f),
        )
    }

    @Test
    fun `top bottom layout turns pages on the vertical axis`() {
        val layout = TapZoneLayout.TOP_BOTTOM
        assertEquals(TapAction.PREV_PAGE, TapZoneGeometry.resolve(500f, 50f, w, h, layout))
        assertEquals(TapAction.NEXT_PAGE, TapZoneGeometry.resolve(500f, 1950f, w, h, layout))
        assertEquals(TapAction.NONE, TapZoneGeometry.resolve(500f, 1000f, w, h, layout))
    }

    @Test
    fun `bottom top layout swaps the vertical bands`() {
        val layout = TapZoneLayout.BOTTOM_TOP
        assertEquals(TapAction.NEXT_PAGE, TapZoneGeometry.resolve(500f, 50f, w, h, layout))
        assertEquals(TapAction.PREV_PAGE, TapZoneGeometry.resolve(500f, 1950f, w, h, layout))
    }

    @Test
    fun `desktop layout delegates to the 3x3 desktop rule`() {
        val layout = TapZoneLayout.DESKTOP
        // Zone 2 (top-middle) is a previous-page cell on the desktop.
        assertEquals(TapAction.PREV_PAGE, TapZoneGeometry.resolve(500f, 100f, w, h, layout))
        // Zone 8 (bottom-middle) is forward.
        assertEquals(TapAction.NEXT_PAGE, TapZoneGeometry.resolve(500f, 1900f, w, h, layout))
        // Zone 5 (exact centre) is chrome.
        assertEquals(TapAction.NONE, TapZoneGeometry.resolve(500f, 1000f, w, h, layout))
    }

    @Test
    fun `mirroring swaps the horizontal bands`() {
        val mirrored = TapZoneGeometry.zones(
            TapZoneLayout.EDGES,
            forwardRatio = 0.30f,
            backwardRatio = 0.20f,
            mirrored = true,
        )
        // Backward band moves to the right edge, forward band to the left edge.
        assertTrue(mirrored.previous.contains(0.90f, 0.5f))
        assertTrue(mirrored.next.contains(0.10f, 0.5f))

        assertEquals(TapAction.PREV_PAGE, TapZoneGeometry.resolve(950f, 1000f, w, h, mirrored = true))
        assertEquals(TapAction.NEXT_PAGE, TapZoneGeometry.resolve(50f, 1000f, w, h, mirrored = true))
    }

    @Test
    fun `mirroring is a no-op for full width bands`() {
        val zones = TapZoneGeometry.zones(
            TapZoneLayout.TOP_BOTTOM,
            forwardRatio = 0.25f,
            backwardRatio = 0.25f,
            mirrored = true,
        )
        assertEquals(0f, zones.previous.left)
        assertEquals(1f, zones.previous.right)
        assertEquals(0f, zones.next.left)
        assertEquals(1f, zones.next.right)
    }

    @Test
    fun `ratios are clamped so the bands can never overlap`() {
        val zones = TapZoneGeometry.zones(
            TapZoneLayout.EDGES,
            forwardRatio = 0.9f,
            backwardRatio = 0.9f,
        )
        assertEquals(TapZoneGeometry.MAX_RATIO, zones.previous.width)
        assertEquals(TapZoneGeometry.MAX_RATIO, zones.next.width)
        // 0.45 + 0.45 leaves a 10% chrome band in the middle.
        assertEquals(TapAction.NONE, TapZoneGeometry.resolve(500f, 1000f, w, h))
    }

    @Test
    fun `tiny ratios still leave a usable edge target`() {
        val zones = TapZoneGeometry.zones(TapZoneLayout.EDGES, forwardRatio = 0f, backwardRatio = 0f)
        assertEquals(TapZoneGeometry.MIN_RATIO, zones.next.width)
        assertEquals(TapZoneGeometry.MIN_RATIO, zones.previous.width)
    }

    @Test
    fun `degenerate viewport resolves to none`() {
        assertEquals(TapAction.NONE, TapZoneGeometry.resolve(10f, 10f, 0f, 0f))
        assertEquals(TapAction.NONE, TapZoneGeometry.resolve(10f, 10f, 100f, 0f))
    }

    @Test
    fun `taps outside the viewport are clamped instead of throwing`() {
        assertEquals(TapAction.PREV_PAGE, TapZoneGeometry.resolve(-50f, -50f, w, h))
        assertEquals(TapAction.NEXT_PAGE, TapZoneGeometry.resolve(w + 50f, h + 50f, w, h))
    }

    @Test
    fun `persisted layout names round trip and fall back to edges`() {
        TapZoneLayout.entries.forEach { layout ->
            assertEquals(layout, TapZoneLayout.fromPersisted(layout.name))
        }
        assertEquals(TapZoneLayout.EDGES, TapZoneLayout.fromPersisted(null))
        assertEquals(TapZoneLayout.EDGES, TapZoneLayout.fromPersisted("garbage"))
        assertEquals(TapZoneLayout.TOP_BOTTOM, TapZoneLayout.fromPersisted("top_bottom"))
    }
}
