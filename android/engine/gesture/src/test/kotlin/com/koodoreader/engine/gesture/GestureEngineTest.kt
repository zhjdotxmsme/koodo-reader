package com.koodoreader.engine.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GestureEngineTest {

    private fun makeEngine(config: ReaderConfig = ReaderConfig()) = GestureEngine(config)

    @Test
    fun `page turn forward with positive velocity`() {
        val engine = makeEngine(config = ReaderConfig(
            viewportWidthPx = 400f, totalPages = 10,
            mode = GestureMode.PAGE_TURN,
        ))
        engine.setCurrentPage(4)
        engine.onTouchDown(300f, 350f, 0L)
        val result = engine.onTouchUp(300f - 200f, 350f, velocityX = -3000f, velocityY = 0f, 100L)
        val pageTurn = result as? GestureResult.PageTurn
        assertTrue(pageTurn != null, "expected PageTurn, got $result")
        assertTrue(pageTurn!!.targetPage > 4, "should move forward (target=${pageTurn.targetPage})")
    }

    @Test
    fun `page turn backward with negative velocity left page`() {
        val engine = makeEngine(config = ReaderConfig(
            viewportWidthPx = 400f, totalPages = 10,
            mode = GestureMode.PAGE_TURN,
        ))
        engine.setCurrentPage(4)
        engine.onTouchDown(100f, 350f, 0L)
        val result = engine.onTouchUp(100f + 200f, 350f, velocityX = 3000f, velocityY = 0f, 100L)
        val pageTurn = result as? GestureResult.PageTurn
        assertTrue(pageTurn != null, "expected PageTurn, got $result")
        assertTrue(pageTurn!!.targetPage < 4, "should move backward (target=${pageTurn.targetPage})")
    }

    @Test
    fun `no motion gives no op`() {
        val engine = makeEngine(config = ReaderConfig(
            viewportWidthPx = 400f, totalPages = 10,
            mode = GestureMode.PAGE_TURN,
        ))
        engine.setCurrentPage(5)
        engine.onTouchDown(200f, 350f, 0L)
        val result = engine.onTouchUp(200f, 350f, velocityX = 0f, velocityY = 0f, 50L)
        assertTrue(result is GestureResult.NoOp, "expected NoOp, got $result")
    }

    @Test
    fun `overscroll at page 0`() {
        val engine = makeEngine(config = ReaderConfig(
            viewportWidthPx = 400f, totalPages = 10,
            mode = GestureMode.PAGE_TURN,
        ))
        engine.setCurrentPage(0)
        engine.onTouchDown(100f, 350f, 0L)
        val result = engine.onTouchUp(100f + 200f, 350f, velocityX = 500f, velocityY = 0f, 100L)
        assertTrue(result is GestureResult.Overscroll, "expected Overscroll at page 0, got $result")
    }

    @Test
    fun `overscroll disabled returns NoOp at page 0`() {
        val engine = makeEngine(config = ReaderConfig(
            viewportWidthPx = 400f, totalPages = 10,
            mode = GestureMode.PAGE_TURN, overscrollEnabled = false,
        ))
        // The config flag alone must disable overscroll (the constructor applies it).
        engine.setCurrentPage(0)
        engine.onTouchDown(100f, 350f, 0L)
        val result = engine.onTouchUp(100f + 200f, 350f, velocityX = 500f, velocityY = 0f, 100L)
        assertTrue(result is GestureResult.NoOp, "expected NoOp with overscroll disabled, got $result")
    }

    @Test
    fun `tap detects left zone`() {
        val engine = makeEngine(config = ReaderConfig(
            viewportWidthPx = 300f, viewportHeightPx = 600f, totalPages = 1,
            mode = GestureMode.PAGE_TURN,
        ))
        assertEquals(TapAction.PREV_PAGE, engine.onTap(10f, 300f))
    }

    @Test
    fun `tap detects right zone`() {
        val engine = makeEngine(config = ReaderConfig(
            viewportWidthPx = 300f, viewportHeightPx = 600f, totalPages = 1,
            mode = GestureMode.PAGE_TURN,
        ))
        assertEquals(TapAction.NEXT_PAGE, engine.onTap(290f, 300f))
    }

    @Test
    fun `tap center is none`() {
        val engine = makeEngine(config = ReaderConfig(
            viewportWidthPx = 300f, viewportHeightPx = 600f, totalPages = 1,
            mode = GestureMode.PAGE_TURN,
        ))
        assertEquals(TapAction.NONE, engine.onTap(150f, 300f))
    }

    @Test
    fun `scroll mode with vertical velocity`() {
        val engine = makeEngine(config = ReaderConfig(
            viewportWidthPx = 400f, viewportHeightPx = 700f, totalPages = 1,
            mode = GestureMode.SCROLL, scrollContentSizePx = 2000f,
        ))
        engine.setScrollOffsetPx(0f)
        engine.onTouchDown(200f, 500f, 0L)
        val result = engine.onTouchUp(200f, 200f, velocityX = 0f, velocityY = -3000f, 100L)
        assertTrue(result is GestureResult.ScrollTo, "expected ScrollTo, got $result")
        val scrollTo = result as GestureResult.ScrollTo
        assertTrue(scrollTo.offsetPx >= 0f, "scroll should be forward")
    }

    @Test
    fun `vertical drag in page-turn mode is NoOp`() {
        val engine = makeEngine(config = ReaderConfig(
            viewportWidthPx = 400f, totalPages = 5,
            mode = GestureMode.PAGE_TURN,
        ))
        engine.setCurrentPage(2)
        engine.onTouchDown(200f, 500f, 0L)
        val result = engine.onTouchUp(200f, 200f, velocityX = 10f, velocityY = -3000f, 100L)
        assertTrue(result is GestureResult.NoOp, "vertical drag should be NoOp in PAGE_TURN, got $result")
    }

    @Test
    fun `reconfigure resets state`() {
        val engine = makeEngine(config = ReaderConfig(
            viewportWidthPx = 400f, totalPages = 10,
            mode = GestureMode.PAGE_TURN,
        ))
        engine.setCurrentPage(8)
        engine.reconfigure(ReaderConfig(
            viewportWidthPx = 400f, totalPages = 3,
            mode = GestureMode.PAGE_TURN,
        ))
        assertTrue(engine.state.currentPageIndex < 3, "page index should be clamped after reconfigure")
    }

    @Test
    fun `state resets after touch up`() {
        val engine = makeEngine()
        engine.onTouchDown(100f, 200f, 0L)
        engine.onTouchUp(150f, 200f, velocityX = 100f, velocityY = 0f, 100L)
        assertEquals(0f, engine.state.dragOffsetX, 0f)
        assertFalse(engine.state.isDragging)
    }
}