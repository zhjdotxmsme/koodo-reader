package com.koodoreader.engine.gesture

/**
 * Sealed result types produced by [GestureEngine].
 *
 * The Compose layer and the reader UI interpret these to:
 *  - navigate to a new page ([PageTurn]),
 *  - update the scroll offset ([ScrollTo]),
 *  - play a spring-back animation after overscroll ([Overscroll]),
 *  - open a selection / image / link / footnote UI ([Selection], [ViewImage],
 *    [OpenLink], [Footnote]),
 *  - show an error ([Error]).
 */
sealed class GestureResult {

    /**
     * Turn to a specific page index.
     * @param targetPage 0-based page index to navigate to.
     */
    data class PageTurn(val targetPage: Int) : GestureResult() {
        fun isNext(current: Int) = targetPage == current + 1
        fun isPrev(current: Int) = targetPage == current - 1
    }

    /**
     * Scroll to a specific offset (SCROLL mode).
     * @param offsetPx  Target vertical offset in px (0 = top,
     *                  maxScrollOffset = bottom).
     */
    data class ScrollTo(val offsetPx: Float) : GestureResult()

    /**
     * The gesture overshot a boundary. The Compose layer should animate
     * the content offset back to the boundary.
     * @param boundary  `"top"`, `"bottom"`, `"left"`, or `"right"`.
     */
    data class Overscroll(val boundary: String) : GestureResult()

    /** Selection menu should appear near (x, y). */
    data class Selection(val x: Float, val y: Float) : GestureResult()

    /** Full-screen image view should open. Data-URI / identifier in [source]. */
    data class ViewImage(val source: String) : GestureResult()

    /** External link should be opened in the system browser. */
    data class OpenLink(val href: String) : GestureResult()

    /** Footnote dialog should show [text]. */
    data class Footnote(val text: String) : GestureResult()

    /** An error occurred; [message] is user-readable. */
    data class Error(val message: String) : GestureResult()

    /** No-op — the gesture was recognized but produces no visible action. */
    data object NoOp : GestureResult()
}
