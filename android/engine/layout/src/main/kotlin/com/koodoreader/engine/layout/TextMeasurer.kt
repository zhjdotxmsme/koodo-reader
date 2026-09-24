package com.koodoreader.engine.layout

/**
 * Character-width / line-box measurement, the only piece of "native" text
 * rendering this module needs in order to paginate.
 *
 * WHY AN ABSTRACTION: the desktop engine measures text through the browser's
 * layout engine (glyph advances from the CSS font cascade). Kotlin has no DOM,
 * so the device build injects a `TextPaint.measureText`-backed implementation,
 * while the JVM unit tests inject a deterministic one. The pagination
 * algorithm is identical either way, which is what makes it testable on a
 * plain JVM (the P2 acceptance gate).
 *
 * CONTRACT the algorithm relies on (violating it breaks the page maths):
 *
 *  - `width()` and `prefixWidth()` must agree: `width(text) == prefixWidth(text, len)`.
 *  - `prefixWidth()` must be **monotonic non-decreasing** in [endExclusive] —
 *    the shaper binary-searches it, and every character must have a width
 *    `>= 0`.
 *  - Letter spacing is applied **between** characters only, so a run of `n`
 *    characters costs `n * advance + (n - 1) * letterSpacing`. `prefixWidth()`
 *    must follow the same rule so that slicing `prefixWidth(end) -
 *    prefixWidth(start)` yields the true width of a slice (including a slice
 *    that starts at 0).
 *  - All widths are in **pixels** at the given [fontSizePx]; the CSS `em`
 *    scaling is the caller's job (see [LayoutTokens.fontSizePx]).
 *  - `lineHeightPx()` is the *natural* single-line box height (ascent +
 *    descent + internal leading) for the font at [fontSizePx]; the CSS
 *    `line-height` multiplier is applied by the engine, not here.
 *  - `ascentPx()` is the distance from the line box top to the baseline, so
 *    `ascentPx(fontSize) <= lineHeightPx(fontSize)` must hold.
 */
interface TextMeasurer {

    /** Total advance width of [text]. */
    fun width(text: String, fontSizePx: Float, letterSpacingPx: Float): Float

    /**
     * Advance width of `text[0..endExclusive)`.
     *
     * `endExclusive` outside `[0, text.length]` is coerced into range so callers
     * do not need to clamp before binary-searching.
     */
    fun prefixWidth(text: String, endExclusive: Int, fontSizePx: Float, letterSpacingPx: Float): Float

    /** Natural (unmultiplied) line box height. */
    fun lineHeightPx(fontSizePx: Float): Float

    /** Baseline offset from the top of the line box. */
    fun ascentPx(fontSizePx: Float): Float
}

/**
 * Fixed per-character advance: the simplest measurer, ideal for tests that
 * reason about exact pagination. Widths are multiples of `charWidthEm *
 * fontSizePx`.
 */
class MonospacedTextMeasurer(
    private val charWidthEm: Float = 1f,
    private val lineHeightEm: Float = 1.2f,
    private val ascentRatio: Float = 0.8f,
) : TextMeasurer {

    override fun width(text: String, fontSizePx: Float, letterSpacingPx: Float): Float = runWidth(text, fontSizePx, letterSpacingPx)

    override fun prefixWidth(text: String, endExclusive: Int, fontSizePx: Float, letterSpacingPx: Float): Float =
        runWidth(text.substring(0, endExclusive.coerceIn(0, text.length)), fontSizePx, letterSpacingPx)

    override fun lineHeightPx(fontSizePx: Float): Float = lineHeightEm * fontSizePx

    override fun ascentPx(fontSizePx: Float): Float = lineHeightEm * fontSizePx * ascentRatio

    private fun runWidth(text: String, fontSizePx: Float, letterSpacingPx: Float): Float {
        if (text.isEmpty()) return 0f
        return charWidthEm * fontSizePx * text.length + letterSpacingPx * (text.length - 1)
    }
}

/**
 * Per-character advance table, indexed by the UTF-16 code unit. This lets the
 * tests model proportional fonts — Latin ~0.5em, CJK ~1em, space ~0.25em —
 * which is exactly the case that decides CJK vs Latin line breaking.
 */
class TableTextMeasurer(
    private val widthByCodePoint: Map<Int, Float>,
    private val defaultWidthEm: Float = 0.5f,
    private val lineHeightEm: Float = 1.2f,
    private val ascentRatio: Float = 0.8f,
) : TextMeasurer {

    override fun width(text: String, fontSizePx: Float, letterSpacingPx: Float): Float = runWidth(text, fontSizePx, letterSpacingPx)

    override fun prefixWidth(text: String, endExclusive: Int, fontSizePx: Float, letterSpacingPx: Float): Float =
        runWidth(text.substring(0, endExclusive.coerceIn(0, text.length)), fontSizePx, letterSpacingPx)

    override fun lineHeightPx(fontSizePx: Float): Float = lineHeightEm * fontSizePx

    override fun ascentPx(fontSizePx: Float): Float = lineHeightEm * fontSizePx * ascentRatio

    private fun runWidth(text: String, fontSizePx: Float, letterSpacingPx: Float): Float {
        if (text.isEmpty()) return 0f
        var em = 0f
        for (ch in text) em += widthByCodePoint[ch.code] ?: defaultWidthEm
        return em * fontSizePx + letterSpacingPx * (text.length - 1)
    }
}

/** Deterministic measurers for tests and the self-check runner. */
object TextMeasurers {

    /** `charWidthEm` = 1 means "one character is one em wide" (the easy case). */
    fun mono(charWidthEm: Float = 1f, lineHeightEm: Float = 1.2f, ascentRatio: Float = 0.8f): TextMeasurer =
        MonospacedTextMeasurer(charWidthEm, lineHeightEm, ascentRatio)

    fun widthTable(
        vararg widths: Pair<Int, Float>,
        defaultWidthEm: Float = 0.5f,
        lineHeightEm: Float = 1.2f,
        ascentRatio: Float = 0.8f,
    ): TextMeasurer = TableTextMeasurer(
        widths.associate { (code, em) -> code to em },
        defaultWidthEm,
        lineHeightEm,
        ascentRatio,
    )

    /**
     * A proportional stand-in for a real mixed-script font:
     * space 0.25em, ASCII 0.5em, CJK 1em. This is the profile used by most
     * of the pagination tests, because it is the profile where breaking rules
     * actually change the output.
     */
    val MIXED: TextMeasurer = widthTable(
        ' '.code to 0.25f,
        '0'.code to 0.5f,
        '一'.code to 1f,
    )
}
