package com.koodoreader.engine.pdf

/**
 * PDF viewer mode, parallel to the desktop kookit `readerMode` and
 * `:engine:gesture` [com.koodoreader.engine.gesture.GestureMode].
 *
 * | kookit enum  | [PdfViewMode] | description                                |
 * |--------------|---------------|--------------------------------------------|
 * | `"single"`   | [PAGE_TURN]   | One page per screen, horizontal navigation |
 * | `"double"`   | [DOUBLE_PAGE] | Two-page spread (desktop wide mode)        |
 * | `"scroll"`   | [SCROLL]      | Continuous vertical scrolling               |
 *
 * This is the public surface used by both the Compose host (mapping it onto
 * [GestureMode]) and the PdfSearchEngine (which only needs to know whether
 * the viewport is single-column or two-column to clamp result rectangles).
 */
enum class PdfViewMode(val desktopValue: String) {
    /** Single-column page turning (the phone default). */
    PAGE_TURN("single"),

    /** Two-page spread (desktop wide mode). */
    DOUBLE_PAGE("double"),

    /** Continuous vertical scroll (mobile reading style). */
    SCROLL("scroll"),
    ;

    companion object {
        /** Desktop-round-trip parsing; "" or unknown -> [PAGE_TURN]. */
        fun fromDesktop(raw: String?): PdfViewMode =
            entries.firstOrNull { it.desktopValue.equals(raw, ignoreCase = true) } ?: PAGE_TURN
    }
}

/**
 * PDF zoom step (percent of the natural page size). The desktop reader uses
 * `"scale"` as a float with the same semantics, so [JsonViewZoomScale] /
 * [fromDesktop] keep the desktop config round-trip-able.
 */
data class PdfZoom(val percent: Int) {
    init {
        // FIT_WIDTH / FIT_PAGE are SENTINELS, not percentages: PdfViewportMath branches
        // on them before it ever calls asScale(). The old validation rejected them, so
        // `PdfZoom(PdfZoom.FIT_WIDTH)` threw and fit-width could not be constructed.
        require(percent in MIN..MAX || percent == FIT_WIDTH || percent == FIT_PAGE) {
            "PdfZoom percent must be in [$MIN..$MAX] or FIT_WIDTH/FIT_PAGE (was $percent)"
        }
    }

    fun asScale(): Float = percent / 100f

    companion object {
        const val MIN = 50
        const val MAX = 400
        const val DEFAULT = 100
        const val FIT_WIDTH = -1
        const val FIT_PAGE = -2

        /**
         * Clamp a percentage into [MIN]..[MAX], falling back to [DEFAULT] outside it.
         * The FIT_WIDTH / FIT_PAGE sentinels are not percentages — construct those
         * with `PdfZoom(PdfZoom.FIT_WIDTH)` directly.
         */
        fun of(percent: Int): PdfZoom = if (percent in MIN..MAX) PdfZoom(percent) else PdfZoom(DEFAULT)

        /**
         * Parse a desktop `scale` config ("" / "1" / "1.5") into a [PdfZoom].
         *
         * A scale outside the supported range falls back to [DEFAULT] rather than being
         * clamped to the nearest bound (the desktop slider cannot produce it either, so
         * there is nothing to preserve).
         */
        fun fromDesktop(raw: Any?): PdfZoom {
            val f = (raw as? Number)?.toFloat()
                ?: raw?.toString()?.trim()?.toFloatOrNull()
                ?: return PdfZoom(DEFAULT)
            val pct = (f * 100f).toInt()
            return if (pct in MIN..MAX) PdfZoom(pct) else PdfZoom(DEFAULT)
        }
    }
}