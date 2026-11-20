package com.koodoreader.reader.shell

import com.koodoreader.engine.gesture.TapZoneLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the native reader page gestures. Regression: the packaged Kotlin
 * reader treated long-press as a no-op (only double-tap opened the
 * selection menu) and the TOC button was easy to miss, so "open EPUB/PDF"
 * looked broken even when pagination succeeded.
 */
class ReaderPageGesturesTest {

    @Test
    fun `long press opens the selection menu`() {
        assertEquals(ReaderPageGesture.SELECT, ReaderPageGestures.onLongPress())
    }

    @Test
    fun `double tap also opens the selection menu`() {
        assertEquals(ReaderPageGesture.SELECT, ReaderPageGestures.onDoubleTap())
    }

    @Test
    fun `tap zones turn pages or toggle chrome`() {
        val w = 300f
        assertEquals(ReaderPageGesture.PREV_PAGE, ReaderPageGestures.onTap(10f, w))
        assertEquals(ReaderPageGesture.NEXT_PAGE, ReaderPageGestures.onTap(290f, w))
        assertEquals(ReaderPageGesture.TOGGLE_CHROME, ReaderPageGestures.onTap(150f, w))
    }

    @Test
    fun `toolbar keeps navigation and close as primary actions`() {
        assertEquals(
            listOf(ReaderChromeAction.TOC, ReaderChromeAction.SEARCH, ReaderChromeAction.CLOSE),
            ReaderChromeAction.PRIMARY,
        )
        org.junit.Assert.assertTrue(
            ReaderChromeAction.OVERFLOW.contains(ReaderChromeAction.BOOKMARK),
        )
    }

    @Test
    fun `chrome is hidden when a book opens`() {
        org.junit.Assert.assertFalse(ReaderPageGestures.CHROME_VISIBLE_ON_OPEN)
    }

    @Test
    fun `swipe down past threshold reveals chrome`() {
        org.junit.Assert.assertTrue(ReaderPageGestures.revealChromeOnSwipeDown(24f))
        org.junit.Assert.assertFalse(ReaderPageGestures.revealChromeOnSwipeDown(8f))
        org.junit.Assert.assertFalse(ReaderPageGestures.revealChromeOnSwipeDown(-40f))
    }

    @Test
    fun `system bars hide with the toolbar`() {
        org.junit.Assert.assertTrue(ReaderPageGestures.hideSystemBars(chromeVisible = false))
        org.junit.Assert.assertFalse(ReaderPageGestures.hideSystemBars(chromeVisible = true))
    }

    @Test
    fun `center press without drag toggles chrome`() {
        assertEquals(
            ReaderPageGesture.TOGGLE_CHROME,
            ReaderPageGestures.tapIfNotDrag(150f, 200f, 152f, 202f, 300f),
        )
    }

    @Test
    fun `vertical drag is a scroll not a chrome tap`() {
        org.junit.Assert.assertNull(
            ReaderPageGestures.tapIfNotDrag(150f, 200f, 150f, 280f, 300f),
        )
    }

    @Test
    fun `edge press without drag still turns the page`() {
        assertEquals(
            ReaderPageGesture.PREV_PAGE,
            ReaderPageGestures.tapIfNotDrag(10f, 200f, 12f, 202f, 300f),
        )
    }

    @Test
    fun `swipe left turns to the next page`() {
        assertEquals(
            ReaderPageGesture.NEXT_PAGE,
            ReaderPageGestures.onHorizontalSwipe(dx = -80f, dy = 10f),
        )
        assertEquals(
            ReaderPageGesture.PREV_PAGE,
            ReaderPageGestures.onHorizontalSwipe(dx = 80f, dy = -8f),
        )
    }

    @Test
    fun `vertical drag is not a page-turn swipe`() {
        org.junit.Assert.assertNull(
            ReaderPageGestures.onHorizontalSwipe(dx = -20f, dy = -90f),
        )
        org.junit.Assert.assertNull(
            ReaderPageGestures.onHorizontalSwipe(dx = -20f, dy = 10f),
        )
    }

    @Test
    fun `volume down goes forward and volume up goes back`() {
        assertEquals(
            ReaderPageGesture.NEXT_PAGE,
            ReaderPageGestures.onVolumeKey(ReaderPageGestures.VOLUME_DOWN),
        )
        assertEquals(
            ReaderPageGesture.PREV_PAGE,
            ReaderPageGestures.onVolumeKey(ReaderPageGestures.VOLUME_UP),
        )
        org.junit.Assert.assertNull(ReaderPageGestures.onVolumeKey(4))
    }

    @Test
    fun `volume keys can be disabled`() {
        org.junit.Assert.assertNull(
            ReaderPageGestures.onVolumeKey(ReaderPageGestures.VOLUME_DOWN, enabled = false),
        )
    }

    @Test
    fun `inverted volume swaps directions`() {
        assertEquals(
            ReaderPageGesture.PREV_PAGE,
            ReaderPageGestures.onVolumeKey(
                ReaderPageGestures.VOLUME_DOWN,
                inverted = true,
            ),
        )
        assertEquals(
            ReaderPageGesture.NEXT_PAGE,
            ReaderPageGestures.onVolumeKey(
                ReaderPageGestures.VOLUME_UP,
                inverted = true,
            ),
        )
    }

    @Test
    fun `horizontal swipe can be disabled`() {
        org.junit.Assert.assertNull(
            ReaderPageGestures.onHorizontalSwipe(-80f, 10f, enabled = false),
        )
    }

    @Test
    fun `tap thresholds scale with density instead of raw pixels`() {
        // 8dp / 24dp at three common densities.
        assertEquals(16f, ReaderGestureMetrics.tapSlopPx(2f), 0.001f)
        assertEquals(22f, ReaderGestureMetrics.tapSlopPx(2.75f), 0.001f)
        assertEquals(28f, ReaderGestureMetrics.tapSlopPx(3.5f), 0.001f)
        assertEquals(48f, ReaderGestureMetrics.swipeMinPx(2f), 0.001f)
        assertEquals(84f, ReaderGestureMetrics.swipeMinPx(3.5f), 0.001f)
        // Degenerate density must not zero the thresholds.
        assertEquals(ReaderGestureMetrics.TAP_SLOP_DP, ReaderGestureMetrics.tapSlopPx(0f), 0.001f)
        assertEquals(ReaderGestureMetrics.SWIPE_MIN_DP, ReaderGestureMetrics.swipeMinPx(Float.NaN), 0.001f)
    }

    @Test
    fun `two dimensional tap resolves vertical layouts`() {
        val w = 1000f
        val h = 2000f
        assertEquals(
            ReaderPageGesture.PREV_PAGE,
            ReaderPageGestures.onTapAt(500f, 50f, w, h, TapZoneLayout.TOP_BOTTOM),
        )
        assertEquals(
            ReaderPageGesture.NEXT_PAGE,
            ReaderPageGestures.onTapAt(500f, 1950f, w, h, TapZoneLayout.TOP_BOTTOM),
        )
        assertEquals(
            ReaderPageGesture.TOGGLE_CHROME,
            ReaderPageGestures.onTapAt(500f, 1000f, w, h, TapZoneLayout.TOP_BOTTOM),
        )
    }

    @Test
    fun `right to left mirrors only the horizontal axis`() {
        val w = 1000f
        val h = 2000f
        assertEquals(
            ReaderPageGesture.PREV_PAGE,
            ReaderPageGestures.onTapAt(950f, 1000f, w, h, TapZoneLayout.EDGES, mirrored = true),
        )
        assertEquals(
            ReaderPageGesture.NEXT_PAGE,
            ReaderPageGestures.onTapAt(50f, 1000f, w, h, TapZoneLayout.EDGES, mirrored = true),
        )
    }

    @Test
    fun `live config drives the tap layout`() {
        val cfg = ReaderGestureConfig(
            tapEdgeFraction = 0.2f,
            tapZoneLayout = TapZoneLayout.BOTTOM_TOP,
        )
        assertEquals(
            ReaderPageGesture.NEXT_PAGE,
            ReaderPageGestures.onTapAt(500f, 50f, 1000f, 2000f, cfg),
        )
        assertEquals(
            ReaderPageGesture.PREV_PAGE,
            ReaderPageGestures.onTapAt(500f, 1950f, 1000f, 2000f, cfg),
        )
    }

    @Test
    fun `two dimensional tap ignores drags`() {
        org.junit.Assert.assertNull(
            ReaderPageGestures.tapAtIfNotDrag(
                downX = 500f, downY = 1000f,
                upX = 500f, upY = 1100f,
                widthPx = 1000f, heightPx = 2000f,
                slopPx = 20f,
            ),
        )
    }

    @Test
    fun `wider tap edge shrinks the chrome band`() {
        val w = 300f
        assertEquals(
            ReaderPageGesture.TOGGLE_CHROME,
            ReaderPageGestures.onTap(150f, w, edgeFraction = 0.2f),
        )
    }
}
