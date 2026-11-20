package com.koodoreader.reader.shell

import com.koodoreader.engine.gesture.TapZoneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderGestureConfigTest {

    @Test
    fun `defaults match legacy one third tap zones`() {
        val c = ReaderGestureCodec.fromMap(emptyMap())
        assertEquals(1f / 3f, ReaderGestureCodec.clampedTapEdge(c), 0.001f)
        assertEquals(TapZoneLayout.EDGES, c.tapZoneLayout)
        // 0 is the "same as the next band" sentinel.
        assertEquals(0f, ReaderGestureCodec.clampedTapBack(c), 0.001f)
        assertEquals(1f / 3f, c.resolvedBackFraction, 0.001f)
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
    fun `layout and separate back band round trip`() {
        val original = ReaderGestureConfig(
            tapEdgeFraction = 0.25f,
            tapBackFraction = 0.4f,
            tapZoneLayout = TapZoneLayout.TOP_BOTTOM,
        )
        val restored = ReaderGestureCodec.fromMap(ReaderGestureCodec.toMap(original))
        assertEquals(TapZoneLayout.TOP_BOTTOM, restored.tapZoneLayout)
        assertEquals(0.25f, ReaderGestureCodec.clampedTapEdge(restored), 0.001f)
        assertEquals(0.4f, ReaderGestureCodec.clampedTapBack(restored), 0.001f)
    }

    @Test
    fun `legacy config without new keys still decodes`() {
        // Written by a build before the layout/back-band fields existed.
        val legacy = mapOf(
            ReaderGestureCodec.KEY_TAP_EDGE to "0.2",
            ReaderGestureCodec.KEY_SWIPE to "false",
        )
        val c = ReaderGestureCodec.fromMap(legacy)
        assertEquals(0.2f, ReaderGestureCodec.clampedTapEdge(c), 0.001f)
        assertEquals(TapZoneLayout.EDGES, c.tapZoneLayout)
        assertEquals(0f, ReaderGestureCodec.clampedTapBack(c), 0.001f)
        assertFalse(c.swipePageTurn)
    }

    @Test
    fun `unknown layout name falls back to edges`() {
        val c = ReaderGestureCodec.fromMap(mapOf(ReaderGestureCodec.KEY_TAP_LAYOUT to "diagonal"))
        assertEquals(TapZoneLayout.EDGES, c.tapZoneLayout)
    }

    @Test
    fun `back band clamps like the forward band`() {
        assertEquals(
            ReaderGestureCodec.TAP_EDGE_MAX,
            ReaderGestureCodec.clampedTapBack(ReaderGestureConfig(tapBackFraction = 0.9f)),
            0.001f,
        )
        assertEquals(
            ReaderGestureCodec.TAP_EDGE_MIN,
            ReaderGestureCodec.clampedTapBack(ReaderGestureConfig(tapBackFraction = 0.01f)),
            0.001f,
        )
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
