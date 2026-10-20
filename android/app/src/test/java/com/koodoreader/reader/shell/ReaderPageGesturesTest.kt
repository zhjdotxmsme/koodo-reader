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
}
