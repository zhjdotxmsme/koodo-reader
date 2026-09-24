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
        require(percent in MIN..MAX) { "PdfZoom percent must be in [$MIN..$MAX] (was $percent)" }
    }

    fun asScale(): Float = percent / 100f

    companion object {
        const val MIN = 50
        const val MAX = 400
        const val DEFAULT = 100
        const val FIT_WIDTH = -1
        const val FIT_PAGE = -2

        fun of(percent: Int): PdfZoom = if (percent in MIN..MAX) PdfZoom(percent) else PdfZoom(DEFAULT)

        /** Parse a desktop `scale` config ("" / "1" / "1.5") into a [PdfZoom]. */
        fun fromDesktop(raw: Any?): PdfZoom {
            val f = (raw as? Number)?.toFloat()
                ?: raw?.toString()?.trim()?.toFloatOrNull()
                ?: return PdfZoom(DEFAULT)
            val pct = (f * 100f).toInt().coerceIn(MIN, MAX)
            return PdfZoom(pct)
        }
    }
}