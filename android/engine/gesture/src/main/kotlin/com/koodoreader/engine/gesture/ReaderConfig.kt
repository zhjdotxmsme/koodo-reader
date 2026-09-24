package com.koodoreader.engine.gesture

/**
 * Configuration for a [GestureEngine] instance.
 *
 * @property viewportWidthPx    Logical viewport width (dp).
 * @property viewportHeightPx   Logical viewport height (dp).
 * @property totalPages         Total number of pages in [PAGE_TURN] / [DOUBLE_PAGE] mode.
 * @property mode               The reader interaction mode.
 * @property scrollContentSizePx
 *   In [GestureMode.SCROLL] mode, the total scrollable content height (dp).
 *   For other modes this is unused (set to 0).
 * @property overscrollEnabled
 *   Enable rubber-band edge bounce at the first/last page (or scroll boundaries).
 *   Defaults to `true` to match Android's system overscroll behaviour.
 */
data class ReaderConfig(
    val viewportWidthPx: Float = 400f,
    val viewportHeightPx: Float = 700f,
    val totalPages: Int = 1,
    val mode: GestureMode = GestureMode.PAGE_TURN,
    val scrollContentSizePx: Float = 2000f,
    val overscrollEnabled: Boolean = true,
) {
    init {
        require(viewportWidthPx > 0f) { "viewportWidthPx must be > 0 (was $viewportWidthPx)" }
        require(viewportHeightPx > 0f) { "viewportHeightPx must be > 0 (was $viewportHeightPx)" }
        require(totalPages >= 1) { "totalPages must be >= 1 (was $totalPages)" }
    }

    /**
     * The "page dimension" for horizontal gesture calculations:
     *  - [PAGE_TURN]   → the viewport width
     *  - [DOUBLE_PAGE] → the viewport width (one spread = one viewport)
     * This is the snap-unit used for page-turn gestures.
     */
    val pageUnitPx: Float get() = viewportWidthPx

    /** The max scroll target in [SCROLL] mode: contentHeight − viewport. */
    val maxScrollOffsetPx: Float
        get() = (scrollContentSizePx - viewportHeightPx).coerceAtLeast(0f)

    /**
     * For [PAGE_TURN] / [DOUBLE_PAGE] mode: the number of "snaps"
     * (i.e. the index of the last page).
     */
    val lastIndex: Int get() = totalPages - 1
}
