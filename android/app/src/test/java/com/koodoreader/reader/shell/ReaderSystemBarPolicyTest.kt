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
