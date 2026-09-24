package com.koodoreader.engine.layout

/**
 * CSS `text-align` values the desktop reader exposes.
 *
 * [desktopValue] is the literal string stored in the desktop `readerConfig`
 * (`""` = "Default"), so a migrated desktop config round-trips without a
 * mapping table.
 */
enum class TextAlign(val desktopValue: String) {
    /** No override: the source CSS wins. In native v1 this degrades to left. */
    DEFAULT(""),

    LEFT("Left"),

    /** Stretch the gaps between words to fill the column (last line excluded). */
    JUSTIFY("Justify"),

    RIGHT("Right"),
    ;

    companion object {
        fun fromDesktop(raw: String?): TextAlign =
            entries.firstOrNull { it.desktopValue.equals(raw, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * CSS `word-break` handling. The desktop app does not expose this to the user —
 * it comes from the book's CSS / the default stylesheet — so it is a token the
 * EPUB parse layer decides, not a user setting.
 */
enum class WordBreak {
    /** Break at spaces, and between adjacent CJK ideographs (CSS `line-break: auto`). */
    NORMAL,

    /** Break only at spaces (CSS `word-break: keep-all`, the CJK "禁则" mode). */
    KEEP_ALL,

    /** Break between any two characters (CSS `word-break: break-all`, the
     * default the desktop engine applies to CJK text). */
    BREAK_ALL,
}

/** CSS `overflow-wrap` handling for unbreakable runs longer than a line. */
enum class OverflowWrap {
    /** Let the run overflow the column (browser default; text may be clipped in
     * a paged renderer). */
    NORMAL,

    /** Force-break the run to fit (Readium's mobile fallback; recommended). */
    BREAK_WORD,
}

/**
 * The layout inputs, aligned with the desktop reader settings.
 *
 * Every value is a **pixel** value at the device density: the settings layer
 * converts the desktop config values (font size 13..80, margin -40..80, ...)
 * through [DesktopReaderConfig] before constructing this object, so the engine
 * never sees units it has to interpret.
 *
 * `column-count` emulation: the desktop engine lays EPUB pages out as CSS
 * multi-column boxes (`column-width` / `column-count`) for a double-page
 * spread. This engine reproduces that by splitting the content box into
 * [PaginatorOptions.columnsPerPage] columns, each with a width of
 * [columnWidthPx] and a gap of [columnGapPx] between them.
 */
data class LayoutTokens(
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val marginHorizontalPx: Float = 0f,
    val marginVerticalPx: Float = 0f,
    val fontSizePx: Float = DesktopReaderConfig.FONT_SIZE_DEFAULT,
    val lineHeightMultiple: Float = DesktopReaderConfig.LINE_HEIGHT_DEFAULT,
    val letterSpacingPx: Float = 0f,
    val paraSpacingPx: Float = 0f,
    val firstLineIndentPx: Float = 0f,
    val columnGapPx: Float = 0f,
    val textAlign: TextAlign = TextAlign.DEFAULT,
    val wordBreak: WordBreak = WordBreak.NORMAL,
    val overflowWrap: OverflowWrap = OverflowWrap.BREAK_WORD,
) {
    init {
        require(viewportWidthPx > 0f) { "viewportWidthPx must be > 0 (was $viewportWidthPx)" }
        require(viewportHeightPx > 0f) { "viewportHeightPx must be > 0 (was $viewportHeightPx)" }
        require(fontSizePx > 0f) { "fontSizePx must be > 0 (was $fontSizePx)" }
        require(lineHeightMultiple > 0f) { "lineHeightMultiple must be > 0 (was $lineHeightMultiple)" }
        // Margins are signed: a negative margin *expands* the content box, which
        // is what the desktop margin slider's negative range means (CSS margin
        // semantics). The content box itself is clamped to a positive width so
        // the engine can never divide by zero or produce a negative column.
        require(marginHorizontalPx >= -viewportWidthPx / 2f) { "marginHorizontalPx out of range (was $marginHorizontalPx)" }
        require(marginVerticalPx >= -viewportHeightPx / 2f) { "marginVerticalPx out of range (was $marginVerticalPx)" }
        require(letterSpacingPx >= 0f) { "letterSpacingPx must be >= 0 (was $letterSpacingPx)" }
        require(paraSpacingPx >= 0f) { "paraSpacingPx must be >= 0 (was $paraSpacingPx)" }
        require(firstLineIndentPx >= 0f) { "firstLineIndentPx must be >= 0 (was $firstLineIndentPx)" }
        require(columnGapPx >= 0f) { "columnGapPx must be >= 0 (was $columnGapPx)" }
    }

    /** Text area inside the margins: the outer bound of a column. */
    val contentWidthPx: Float
        get() = (viewportWidthPx - 2f * marginHorizontalPx).coerceAtLeast(1f)

    /** Text area height inside the margins: the height of a page/column. */
    val contentHeightPx: Float
        get() = (viewportHeightPx - 2f * marginVerticalPx).coerceAtLeast(1f)

    /** Width of one column for a [columns]-wide page (CSS `column-count`). */
    fun columnWidthPx(columns: Int): Float {
        val n = columns.coerceAtLeast(1)
        val gaps = if (n > 1) (n - 1) * columnGapPx else 0f
        return ((contentWidthPx - gaps) / n).coerceAtLeast(1f)
    }

    /** Left edge of column [index] (0-based) on a [columns]-wide page. */
    fun columnLeftPx(index: Int, columns: Int): Float =
        marginHorizontalPx + index * (columnWidthPx(columns) + columnGapPx)

    companion object {
        fun defaults(viewportWidthPx: Float, viewportHeightPx: Float): LayoutTokens =
            LayoutTokens(viewportWidthPx, viewportHeightPx)
    }
}

/**
 * Desktop reader-config keys + default values, so the native settings screen
 * reads/writes the *same* keys as the desktop app (`ConfigService.getReaderConfig`).
 *
 * Verified against `src/constants/dropdownList.tsx` (sliderConfigs /
 * textSettingList) and `src/containers/settings/textSetting/component.tsx`;
 * `scripts/check-layout-config-keys.js` keeps this list in lock-step in CI.
 */
object DesktopReaderConfig {

    // ── key names (must match the desktop readerConfig keys exactly) ────────
    const val KEY_FONT_SIZE = "fontSize"
    const val KEY_MARGIN = "margin"
    const val KEY_LETTER_SPACING = "letterSpacing"
    const val KEY_PARA_SPACING = "paraSpacing"
    const val KEY_LINE_HEIGHT = "lineHeight"
    const val KEY_TEXT_ALIGN = "textAlign"
    const val KEY_TEXT_ORIENTATION = "textOrientation"
    const val KEY_FONT_FAMILY = "fontFamily"
    const val KEY_SUB_FONT_FAMILY = "subFontFamily"

    // ── desktop defaults / slider bounds ─────────────────────────────────────
    /** Desktop default font size (px at the device density). */
    const val FONT_SIZE_DEFAULT = 17f

    const val FONT_SIZE_MIN = 13f
    const val FONT_SIZE_MAX = 80f

    /**
     * Desktop `lineHeight` is stored as a *multiple*: the dropdown offers
     * "Default"(empty), "1", "1.25", "1.5", "1.75", "2". An empty/missing value
     * means "Default", which this engine treats as 1.5 (the CSS default the
     * browser applies when the book CSS is silent).
     */
    const val LINE_HEIGHT_DEFAULT = 1.5f

    const val LINE_HEIGHT_MIN = 1f
    const val LINE_HEIGHT_MAX = 2f

    /** Desktop "Default" is the empty string for every dropdown. */
    const val VALUE_DEFAULT = ""

    const val LETTER_SPACING_DEFAULT = 0f
    const val LETTER_SPACING_MAX = 20f

    const val PARA_SPACING_DEFAULT = 0f
    const val PARA_SPACING_MAX = 120f

    const val MARGIN_DEFAULT = 0f
    const val MARGIN_MIN = -40f
    const val MARGIN_MAX = 80f

    // ── parsing ─────────────────────────────────────────────────────────────
    /**
     * Parse a desktop config value into a float. The desktop stores numbers as
     * strings ("17"), and `""` means "default" — so an empty string falls back
     * to [default] rather than producing 0.
     */
    fun floatOf(value: Any?, default: Float): Float = when {
        value == null -> default
        value is Number -> value.toFloat()
        else -> {
            val raw = value.toString().trim()
            if (raw.isEmpty()) default else raw.toFloatOrNull() ?: default
        }
    }

    fun fontSizeOf(value: Any?): Float = floatOf(value, FONT_SIZE_DEFAULT).coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)

    fun lineHeightOf(value: Any?): Float =
        floatOf(value, LINE_HEIGHT_DEFAULT).coerceIn(LINE_HEIGHT_MIN, LINE_HEIGHT_MAX)

    fun letterSpacingOf(value: Any?): Float = floatOf(value, LETTER_SPACING_DEFAULT).coerceIn(0f, LETTER_SPACING_MAX)

    fun paraSpacingOf(value: Any?): Float = floatOf(value, PARA_SPACING_DEFAULT).coerceIn(0f, PARA_SPACING_MAX)

    /** The desktop margin slider is centered on 0 and may be negative. */
    fun marginOf(value: Any?): Float = floatOf(value, MARGIN_DEFAULT).coerceIn(MARGIN_MIN, MARGIN_MAX)

    /**
     * Build [LayoutTokens] from a desktop readerConfig map (the JSON object the
     * desktop app writes into `readerConfig.json` / Room). Only the keys the
     * layout engine consumes are read; everything else is ignored on purpose, so
     * an unknown future desktop key cannot break native pagination.
     *
     * Negative desktop margins are legal (the slider allows -40): a negative
     * margin *expands* the content box, so the margin is applied as a signed
     * offset from the viewport edge.
     */
    fun tokensOf(config: Map<*, *>, viewportWidthPx: Float, viewportHeightPx: Float): LayoutTokens =
        LayoutTokens(
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            marginHorizontalPx = marginOf(config[KEY_MARGIN]),
            marginVerticalPx = marginOf(config[KEY_MARGIN]),
            fontSizePx = fontSizeOf(config[KEY_FONT_SIZE]),
            lineHeightMultiple = lineHeightOf(config[KEY_LINE_HEIGHT]),
            letterSpacingPx = letterSpacingOf(config[KEY_LETTER_SPACING]),
            paraSpacingPx = paraSpacingOf(config[KEY_PARA_SPACING]),
            textAlign = TextAlign.fromDesktop(config[KEY_TEXT_ALIGN]?.toString()),
        )

    /**
     * @param config a desktop readerConfig map (key -> JSON value).
     * @return the token object, or [null] when the config is not usable at all
     *   (e.g. a font size of 0) — callers fall back to [LayoutTokens.defaults].
     */
    fun tokensOrNull(config: Map<*, *>?, viewportWidthPx: Float, viewportHeightPx: Float): LayoutTokens? {
        if (config == null) return LayoutTokens.defaults(viewportWidthPx, viewportHeightPx)
        return runCatching { tokensOf(config, viewportWidthPx, viewportHeightPx) }.getOrNull()
    }
}
