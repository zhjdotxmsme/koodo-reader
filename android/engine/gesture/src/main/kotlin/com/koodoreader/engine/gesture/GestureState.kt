package com.koodoreader.engine.gesture

/**
 * Mutable gesture session state.
 *
 * The [GestureEngine] mutates this between [com.koodoreader.engine.gesture.GestureEngine.onTouchDown]
 * and [com.koodoreader.engine.gesture.GestureEngine.onTouchUp] calls.
 * After [com.koodoreader.engine.gesture.GestureEngine.onTouchUp], all fields are reset
 * to their defaults (the session is complete).
 *
 * All values are in device pixels (px).
 */
class GestureState {

    /** The mode the gesture is being evaluated in. */
    var mode: GestureMode = GestureMode.PAGE_TURN

    /** Current page index (0-based) in PAGE_TURN / DOUBLE_PAGE mode. */
    var currentPageIndex: Int = 0

    /** Total pages (mirrored from [ReaderConfig] for convenience). */
    var totalPages: Int = 1

    /** Current scroll offset (px) in SCROLL mode. 0 = top. */
    var scrollOffsetPx: Float = 0f

    /** Maximum scroll offset in SCROLL mode (= contentHeight − viewportHeight). */
    var maxScrollOffsetPx: Float = 0f

    // ── During-drag fields ──────────────────────────────────────────────────

    /** True while a drag is in progress (between down and up). */
    var isDragging: Boolean = false

    /** X offset from touch-down → current, in px. */
    var dragOffsetX: Float = 0f

    /** Y offset from touch-down → current, in px. */
    var dragOffsetY: Float = 0f

    /** X at touch-down (px). */
    var touchDownX: Float = 0f

    /** Y at touch-down (px). */
    var touchDownY: Float = 0f

    /** Timestamp at touch-down (ms). */
    var touchDownTimeMs: Long = 0L

    /** Timestamp of the last move event (ms). */
    var lastMoveTimeMs: Long = 0L

    /** Instantaneous velocity from the last move (px/s). */
    var velocityX: Float = 0f

    /** Instantaneous velocity from the last move (px/s). */
    var velocityY: Float = 0f

    /**
     * True when the drag has overshot a boundary and is currently
     * in the "rubber-band" region.
     */
    var isOverscrolling: Boolean = false

    /** How far past the boundary, in px (positive). */
    var overscrollAmountPx: Float = 0f

    /** Reset all "during-drag" fields back to their initial values. */
    fun resetDrag() {
        isDragging = false
        dragOffsetX = 0f
        dragOffsetY = 0f
        touchDownX = 0f
        touchDownY = 0f
        touchDownTimeMs = 0L
        lastMoveTimeMs = 0L
        velocityX = 0f
        velocityY = 0f
        isOverscrolling = false
        overscrollAmountPx = 0f
    }

    /** Full reset including the persistent fields. */
    fun reset() {
        resetDrag()
        mode = GestureMode.PAGE_TURN
        currentPageIndex = 0
        totalPages = 1
        scrollOffsetPx = 0f
        maxScrollOffsetPx = 0f
    }
}
