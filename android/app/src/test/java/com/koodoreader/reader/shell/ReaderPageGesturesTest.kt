package com.koodoreader.reader.shell

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
    fun `toolbar keeps toc as a primary action so it cannot overflow off-screen`() {
        assertEquals(
            listOf(ReaderChromeAction.TOC, ReaderChromeAction.SEARCH),
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
    fun `wider tap edge shrinks the chrome band`() {
        val w = 300f
        assertEquals(
            ReaderPageGesture.TOGGLE_CHROME,
            ReaderPageGestures.onTap(150f, w, edgeFraction = 0.2f),
        )
    }
}
