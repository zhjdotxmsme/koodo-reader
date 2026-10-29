package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderGestureConfigTest {

    @Test
    fun `defaults match legacy one third tap zones`() {
        val c = ReaderGestureCodec.fromMap(emptyMap())
        assertEquals(1f / 3f, ReaderGestureCodec.clampedTapEdge(c), 0.001f)
        assertTrue(c.swipePageTurn)
        assertTrue(c.volumePageTurn)
        assertFalse(c.volumeInverted)
    }

    @Test
    fun `tap edge clamps to librera range`() {
        val wide = ReaderGestureCodec.clampedTapEdge(ReaderGestureConfig(tapEdgeFraction = 0.9f))
        assertEquals(ReaderGestureCodec.TAP_EDGE_MAX, wide, 0.001f)
        val narrow = ReaderGestureCodec.clampedTapEdge(ReaderGestureConfig(tapEdgeFraction = 0.05f))
        assertEquals(ReaderGestureCodec.TAP_EDGE_MIN, narrow, 0.001f)
    }

    @Test
    fun `round trip preserves flags`() {
        val original = ReaderGestureConfig(
            tapEdgeFraction = 0.2f,
            swipePageTurn = false,
            volumePageTurn = false,
            volumeInverted = true,
        )
        val restored = ReaderGestureCodec.fromMap(ReaderGestureCodec.toMap(original))
        assertEquals(0.2f, ReaderGestureCodec.clampedTapEdge(restored), 0.001f)
        assertFalse(restored.swipePageTurn)
        assertFalse(restored.volumePageTurn)
        assertTrue(restored.volumeInverted)
    }
}
