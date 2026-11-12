package com.koodoreader.reader.shell

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
}
