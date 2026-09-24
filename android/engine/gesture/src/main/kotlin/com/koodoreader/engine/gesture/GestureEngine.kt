package com.koodoreader.engine.gesture

import kotlin.math.abs

class GestureEngine(
    var config: ReaderConfig = ReaderConfig(),
) {

    val state = GestureState()
    val fling = FlingPhysics()
    val overscroll = OverscrollModel()
    val tapRule = TapControlRule()
    private val velocityThresholdPxPerS: Float = 50f

    // ── public API ─────────────────────────────────────────────────────────

    fun reconfigure(newConfig: ReaderConfig) {
        config = newConfig
        state.mode = newConfig.mode
        state.totalPages = newConfig.totalPages
        state.maxScrollOffsetPx = newConfig.maxScrollOffsetPx
        if (state.currentPageIndex >= newConfig.totalPages) {
            state.currentPageIndex = 0
        }
        if (state.scrollOffsetPx > newConfig.maxScrollOffsetPx) {
            state.scrollOffsetPx = newConfig.maxScrollOffsetPx
        }
    }

    fun setCurrentPage(pageIndex: Int) {
        state.currentPageIndex = pageIndex.coerceIn(0, config.totalPages - 1)
    }

    fun setScrollOffsetPx(offset: Float) {
        state.scrollOffsetPx = offset.coerceIn(0f, state.maxScrollOffsetPx)
    }

    fun setOverscrollEnabled(enabled: Boolean) {
        overscroll.enabled = enabled
    }

    fun onTouchDown(x: Float, y: Float, nowMs: Long = System.currentTimeMillis()) {
        state.isDragging = true
        state.touchDownX = x
        state.touchDownY = y
        state.touchDownTimeMs = nowMs
        state.lastMoveTimeMs = nowMs
        state.dragOffsetX = 0f
        state.dragOffsetY = 0f
        state.velocityX = 0f
        state.velocityY = 0f
    }

    fun onTouchMove(
        x: Float, y: Float,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!state.isDragging) return
        state.dragOffsetX = x - state.touchDownX
        state.dragOffsetY = y - state.touchDownY
        val dtMs = (nowMs - state.lastMoveTimeMs).toFloat()
        if (dtMs > 0f) {
            state.velocityX = (x - state.touchDownX) / dtMs * 1000f
            state.velocityY = (y - state.touchDownY) / dtMs * 1000f
        }
        state.lastMoveTimeMs = nowMs
    }

    fun onTouchUp(
        x: Float, y: Float,
        velocityX: Float, velocityY: Float,
        nowMs: Long = System.currentTimeMillis(),
    ): GestureResult {
        if (!state.isDragging) {
            state.resetDrag()
            return GestureResult.NoOp
        }
        // Use the velocity passed by the caller (which is typically from Compose's
        // pointer input and is more accurate than our computed one).
        val v0x = velocityX
        val v0y = velocityY
        state.dragOffsetX = x - state.touchDownX
        state.dragOffsetY = y - state.touchDownY

        val result: GestureResult = when (state.mode) {
            GestureMode.PAGE_TURN, GestureMode.DOUBLE_PAGE ->
                resolvePageTurn(v0x, v0y, x, y, nowMs)
            GestureMode.SCROLL ->
                resolveScroll(v0x, v0y, x, y, nowMs)
        }

        state.resetDrag()
        return result
    }

    /**
     * Handle a clean tap (no movement, short duration) — resolves tap zone.
     */
    fun onTap(x: Float, y: Float): TapAction {
        return resolveTapAction(x, y, config.viewportWidthPx, config.viewportHeightPx, tapRule)
    }

    /**
     * Check whether a tap at (x, y) falls on an interactive region.
     * The Compose layer uses this to decide whether to consume the tap
     * for page-turn vs. pass it to the content layer (links, images, selection).
     *
     * @return true if the tap should be interpreted as a page-turn (consume),
     *         false if it should be passed to the content layer.
     */
    fun isPageTurnTap(x: Float, y: Float): Boolean =
        resolveTapAction(x, y, config.viewportWidthPx, config.viewportHeightPx, tapRule) != TapAction.NONE

    // ── internal resolution ─────────────────────────────────────────────────

    private fun pageUnit(): Float = config.pageUnitPx

    private fun resolvePageTurn(
        v0x: Float, v0y: Float,
        finalX: Float, finalY: Float,
        nowMs: Long,
    ): GestureResult {
        // For page-turn, only horizontal drag matters.
        if (abs(v0y) > abs(v0x) * 2f) {
            // Primarily vertical gesture — not a page turn.
            return GestureResult.NoOp
        }

        val unit = pageUnit()
        val dragX = state.dragOffsetX
        val v0 = v0x
        val curPos = state.currentPageIndex.toFloat() * unit
        val minPos = 0f
        val maxPos = state.totalPages * unit - unit

        // Check if we overshot a boundary.
        if (dragX > 0f && state.currentPageIndex == 0) {
            // Overshot left boundary (dragging right past first page)
            val overshoot = dragX
            if (overscroll.enabled && overshoot > 8f) {
                state.isOverscrolling = true
                state.overscrollAmountPx = overshoot
            }
            return if (overscroll.enabled)
                GestureResult.Overscroll("left")
            else
                GestureResult.NoOp
        }
        if (dragX < 0f && state.currentPageIndex >= state.totalPages - 1) {
            val overshoot = -dragX
            if (overscroll.enabled && overshoot > 8f) {
                state.isOverscrolling = true
                state.overscrollAmountPx = overshoot
            }
            return if (overscroll.enabled)
                GestureResult.Overscroll("right")
            else
                GestureResult.NoOp
        }

        // Predict landing position using fling physics.
        val landing = fling.predictLanding(curPos, v0, minPos, maxPos)
        val target = (landing / unit).toInt().coerceIn(0, state.totalPages - 1)

        if (target == state.currentPageIndex) return GestureResult.NoOp
        state.currentPageIndex = target
        return GestureResult.PageTurn(target)
    }

    private fun resolveScroll(
        v0x: Float, v0y: Float,
        finalX: Float, finalY: Float,
        nowMs: Long,
    ): GestureResult {
        // For SCROLL mode, only vertical drag matters.
        if (abs(v0x) > abs(v0y) * 2f) {
            return GestureResult.NoOp
        }

        val dragY = state.dragOffsetY
        val v0 = v0y
        val maxOffset = state.maxScrollOffsetPx
        val curOffset = state.scrollOffsetPx

        // Check overscroll at top.
        if (dragY > 0f && curOffset == 0f) {
            val overshoot = dragY
            if (overscroll.enabled && overshoot > 8f) {
                state.isOverscrolling = true
                state.overscrollAmountPx = overshoot
            }
            return if (overscroll.enabled) GestureResult.Overscroll("top") else GestureResult.NoOp
        }

        // Check overscroll at bottom.
        if (maxOffset > 0f && dragY < 0f && (curOffset + dragY) >= maxOffset) {
            val overshoot = (curOffset + dragY) - maxOffset
            if (overscroll.enabled && overshoot > 8f) {
                state.isOverscrolling = true
                state.overscrollAmountPx = overshoot
            }
            return if (overscroll.enabled) GestureResult.Overscroll("bottom") else GestureResult.NoOp
        }

        // Predict landing position.
        val landing = fling.predictLanding(curOffset, v0, 0f, if (maxOffset > 0f) maxOffset else 0f)
        val target = landing.coerceIn(0f, if (maxOffset > 0f) maxOffset else 0f)
        state.scrollOffsetPx = target
        return GestureResult.ScrollTo(target)
    }
}