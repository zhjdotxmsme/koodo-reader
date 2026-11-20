package com.koodoreader.engine.annotate

/**
 * Maps persisted highlight values to reader paint and legacy CSS colors.
 *
 * Room stores either a [HighlightColor] code (1..8) or a legacy packed color.
 * The old yellow was `0x99FFFF` (alpha 0), which Canvas draws as invisible.
 *
 * ## The 30% rule
 *
 * A highlight sits BEHIND body text, so it must never be opaque: an opaque
 * band would force the text to be re-coloured and the result reads as a
 * sticker rather than as a highlighter. 30% alpha keeps the underlying glyphs
 * dominant — the highlight reads as a translucent wash of colour over the page,
 * exactly like a real highlighter over printed ink.
 *
 * The alpha is applied at paint time and never stored, so the persisted value
 * keeps the opaque desktop hex and a change to the alpha rule re-tints every
 * existing annotation retroactively.
 *
 * ## Dark-mode legibility
 *
 * A 30%-alpha yellow over a near-black page is nearly invisible: 30% of a
 * bright yellow is dark. The palette therefore has a *dark* variant in which
 * each colour is replaced by a darker, more saturated tint that still reads as
 * a coloured band once composited at 30% over a dark background.
 *
 * The dark tints live here (not in `:core:designsystem`) because this module is
 * the one that owns the highlight wire format and the paint decision, and
 * `:core:designsystem` must not depend on `:engine:annotate`.
 */
object HighlightPalette {
    const val LEGACY_INVISIBLE_YELLOW = 0x99FFFFL
    const val READER_ALPHA = 0.30f
    private const val READER_ALPHA_INT = 0x4D

    /**
     * Dark-mode tints, keyed by the desktop highlight code (1..8).
     *
     * Darker and more saturated than the desktop day colours, so that after
     * being composited at 30% over a #121212 page they still read as a
     * coloured band rather than as noise.
     */
    private val DARK_TINTS: Map<Int, Long> = mapOf(
        1 to 0xFFB8860B, // yellow → dark amber
        2 to 0xFF2E7D32, // green  → forest
        3 to 0xFFAD1457, // pink  → deep magenta
        4 to 0xFFB45309, // orange → burnt orange
        5 to 0xFF0E7490, // cyan   → deep teal
        6 to 0xFF6D28D9, // purple → violet
        7 to 0xFFB91C1C, // red    → deep red
        8 to 0xFF1D4ED8, // blue   → strong blue
    )

    fun argb(color: HighlightColor, alpha: Int = READER_ALPHA_INT): Long {
        val rgb = color.hex.removePrefix("#").toLong(16)
        return ((alpha.toLong() and 0xFF) shl 24) or rgb
    }

    /**
     * Resolves a stored value to the ARGB the canvas should paint.
     *
     * @param stored the persisted value (a code 1..8, a packed ARGB, or null)
     * @param dark true when the reader page is in dark mode — the dark tint
     *   table is applied so the 30%-alpha band stays visible.
     */
    fun paintArgb(stored: Long?, dark: Boolean = false): Long {
        val resolved = when {
            stored == null || stored == LEGACY_INVISIBLE_YELLOW -> argb(HighlightColor.YELLOW)
            stored in 1L..8L -> argb(HighlightColor.fromCode(stored))
            (stored ushr 24) and 0xFF != 0L -> stored
            else -> argb(HighlightColor.YELLOW)
        }
        val rgb = resolved and 0xFFFFFF
        val tinted = if (dark) {
            val code = HighlightColor.fromCode(stored).code
            DARK_TINTS[code] ?: rgb
        } else {
            rgb
        }
        return (READER_ALPHA_INT.toLong() shl 24) or (tinted and 0xFFFFFF)
    }

    /**
     * The CSS colour for the Foliate (WebView) track.
     *
     * @param dark true in dark mode — the same dark tint table is applied, so
     *   the browser-rendered highlight matches the canvas-rendered one.
     */
    fun readerCss(stored: Long?, dark: Boolean = false): String {
        val paint = paintArgb(stored, dark)
        val red = (paint ushr 16) and 0xFF
        val green = (paint ushr 8) and 0xFF
        val blue = paint and 0xFF
        return "rgba($red,$green,$blue,$READER_ALPHA)"
    }

    fun css(stored: Long?): String {
        if (stored == null || stored == LEGACY_INVISIBLE_YELLOW) return HighlightColor.YELLOW.hex
        if (stored in 1L..8L) return HighlightColor.fromCode(stored).hex
        val alpha = (stored ushr 24) and 0xFF
        if (alpha == 0L) return HighlightColor.YELLOW.hex
        val rgb = (stored and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
        return "#$rgb"
    }
}
