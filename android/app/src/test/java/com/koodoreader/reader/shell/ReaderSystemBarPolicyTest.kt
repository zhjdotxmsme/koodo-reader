package com.koodoreader.reader.shell

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSystemBarPolicyTest {

    @Test
    fun `system bar matrix matches immersive contract`() {
        assertFalse(ReaderSystemBarPolicy.shouldHide(false, true))
        assertTrue(ReaderSystemBarPolicy.shouldHide(false, false))
        assertTrue(ReaderSystemBarPolicy.shouldHide(true, true))
        assertTrue(ReaderSystemBarPolicy.shouldHide(true, false))
    }

    @Test
    fun `content insets ignore chrome and preserve system space`() {
        assertEquals(72 + 24, ReaderContentInsetPolicy.topPx(72, 3f))
        assertEquals(72 + 48, ReaderContentInsetPolicy.bottomPx(72, 3f))
    }

    @Test
    fun `each axis loses only its own insets`() {
        // Regression: the width used to lose topPx (the status-bar height),
        // which made every page ~30px narrow and left the first line under
        // the status bar.
        assertEquals(
            1080 to 2154,
            ReaderContentInsetPolicy.contentSize(
                widthPx = 1080,
                heightPx = 2280,
                topPx = 96,
                bottomPx = 30,
            ),
        )
    }

    @Test
    fun `landscape side insets shrink the width`() {
        assertEquals(
            2210 to 984,
            ReaderContentInsetPolicy.contentSize(
                widthPx = 2280,
                heightPx = 1080,
                topPx = 0,
                bottomPx = 96,
                leftPx = 40,
                rightPx = 30,
            ),
        )
    }

    @Test
    fun `content size never collapses the viewport`() {
        assertEquals(
            1 to 1,
            ReaderContentInsetPolicy.contentSize(
                widthPx = 10,
                heightPx = 10,
                topPx = 90,
                bottomPx = 90,
                leftPx = 90,
                rightPx = 90,
            ),
        )
    }

    @Test
    fun `reader draws into short display cutout edges on supported devices`() {
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            ReaderSystemBarPolicy.displayCutoutMode(28),
        )
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            ReaderSystemBarPolicy.displayCutoutMode(36),
        )
    }

    @Test
    fun `legacy reader keeps the default display cutout mode`() {
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT,
            ReaderSystemBarPolicy.displayCutoutMode(27),
        )
    }
}
