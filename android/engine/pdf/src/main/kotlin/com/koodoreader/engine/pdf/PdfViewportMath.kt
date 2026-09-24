package com.koodoreader.engine.pdf

/**
 * Viewport geometry for the PDF reader (P3).
 *
 * The engine never sees a real `WebView`; what it sees is a `Viewport`:
 * width × height in CSS pixels, plus the current [PdfViewMode] and
 * [PdfZoom]. From those it computes:
 *   - the size of the rendered page canvas;
 *   - which pages are inside the current scroll window;
 *   - the scroll offset that lands a given (page, y) at the top of the
 *     viewport (used by outline-tap / bookmark-jump / search-result
 *     navigation).
 *
 * The desktop reader's kookit `PdfRender` does this on the browser side via
 * CSS columns; the native reader has to do it ahead of time so the
 * Compose layer can virtualise pages that aren't yet rendered.
 *
 * Coordinate system:
 *   - The viewport is a CSS-pixel box at the device density;
 *   - PDF user space (points, 72/in) is mapped to CSS pixels via
 *     [PdfZoom.percent];
 *   - `pageWidthPx = (naturalWidthPt / 72) * dpi * (zoom / 100)` — dpi
 *     collapses into the device `density` injected at construction.
 */
class PdfViewportMath(
    private val viewportWidthPx: Float,
    private val viewportHeightPx: Float,
    private val deviceDensity: Float,
    /** Average PDF page size in points (612 × 792 for Letter; overridden by the document). */
    var pageWidthPt: Float = 612f,
    var pageHeightPt: Float = 792f,
) {
    init {
        require(viewportWidthPx > 0f) { "viewportWidthPx must be > 0" }
        require(viewportHeightPx > 0f) { "viewportHeightPx must be > 0" }
        require(deviceDensity > 0f) { "deviceDensity must be > 0" }
        require(pageWidthPt > 0f && pageHeightPt > 0f) { "page size must be > 0" }
    }

    /** Set the natural page size in points. */
    fun setPageSize(widthPt: Float, heightPt: Float) {
        pageWidthPt = widthPt
        pageHeightPt = heightPt
    }

    /** Width of one rendered page at [zoom]. */
    fun pageWidthPx(zoom: PdfZoom): Float {
        if (zoom.percent == PdfZoom.FIT_WIDTH) {
            // Make the page exactly viewport-width. The companion render
            // height is recomputed by [pageHeightPx] (preserves aspect).
            return viewportWidthPx
        }
        return pointsToPixels(pageWidthPt, zoom)
    }

    /** Height of one rendered page at [zoom]. */
    fun pageHeightPx(zoom: PdfZoom): Float {
        if (zoom.percent == PdfZoom.FIT_WIDTH) {
            val scale = viewportWidthPx / pointsToPixels(pageWidthPt, zoom).coerceAtLeast(0.001f)
            return pointsToPixels(pageHeightPt, zoom) * scale
        }
        return pointsToPixels(pageHeightPt, zoom)
    }

    /** Vertical gap between pages (CSS px). */
    val pageGapPx: Float get() = (16f * deviceDensity).coerceAtLeast(1f)

    /**
     * Total scrollable height for [mode] at [zoom], for a book of
     * [pageCount] pages.
     */
    fun contentHeightPx(mode: PdfViewMode, zoom: PdfZoom, pageCount: Int): Float {
        require(pageCount >= 0) { "pageCount must be >= 0" }
        return when (mode) {
            PdfViewMode.SCROLL -> {
                val per = pageHeightPx(zoom) + pageGapPx
                per * pageCount
            }
            PdfViewMode.PAGE_TURN, PdfViewMode.DOUBLE_PAGE -> {
                // Paged modes don't have a meaningful scroll height, but
                // the Compose layer still wants a positive value so the
                // scroll bars don't divide by zero.
                pageHeightPx(zoom)
            }
        }
    }

    /**
     * Page index (1-based) at the top of the viewport given a scroll
     * [offsetPx]. Returns the page whose top edge is at or above [offsetPx]
     * but whose bottom edge is strictly below.
     */
    fun pageAtOffset(mode: PdfViewMode, zoom: PdfZoom, offsetPx: Float): Int {
        if (mode != PdfViewMode.SCROLL) return 1
        val per = pageHeightPx(zoom) + pageGapPx
        if (per <= 0f) return 1
        val raw = (offsetPx / per).toInt()
        return raw.coerceAtLeast(0) + 1
    }

    /**
     * Scroll offset that puts [pageNumber] (1-based) at the top of the
     * viewport. Used by outline-tap, bookmark-jump, search-result-jump.
     */
    fun offsetForPage(mode: PdfViewMode, zoom: PdfZoom, pageNumber: Int): Float {
        require(pageNumber >= 1) { "pageNumber must be 1-based (was $pageNumber)" }
        if (mode != PdfViewMode.SCROLL) return 0f
        val per = pageHeightPx(zoom) + pageGapPx
        return per * (pageNumber - 1)
    }

    /**
     * Window of pages to render eagerly around [center]. The native reader
     * virtualises off-screen pages — see [engine:image] (P5 comic reader)
     * which uses the same "current + 3 ahead, drop -4 behind" heuristic.
     *
     * @param center the 1-based page currently in the centre of the
     *   viewport.
     * @param pageCount total pages (clamped).
     * @return [from, to] inclusive, 1-based; always non-empty.
     */
    fun renderWindow(center: Int, pageCount: Int): IntRange {
        require(pageCount >= 1) { "pageCount must be >= 1" }
        val c = center.coerceIn(1, pageCount)
        val ahead = 3
        val behind = 4
        val from = (c - behind).coerceAtLeast(1)
        val to = (c + ahead).coerceAtMost(pageCount)
        return from..to
    }

    private fun pointsToPixels(pt: Float, zoom: PdfZoom): Float {
        // 1 pt = 1/72 in. 1 in at the device density = density * 160 dpi
        // units (Compose's `density`). Apply zoom. FIT_WIDTH is resolved
        // by the callers (pageWidthPx / pageHeightPx) — we don't reach
        // here with FIT_WIDTH because that path short-circuits earlier.
        if (zoom.percent == PdfZoom.FIT_WIDTH || zoom.percent == PdfZoom.FIT_PAGE) {
            // Safety: fall back to the natural zoom. Should not be reached.
            return pt * (deviceDensity * 160f) / 72f
        }
        val cssPxPerPt = (deviceDensity * 160f) / 72f
        return pt * cssPxPerPt * (zoom.percent / 100f)
    }
}