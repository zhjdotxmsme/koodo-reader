package com.koodoreader.reader.shell

/**
 * Native reading typography + P6 enhancement switches.
 *
 * Kept as a plain data class so JVM tests can pin the desktop-parity defaults
 * (`fontSize` 17, `lineHeight` 1.5, `margin` 0) without SharedPreferences.
 * [ReaderFeaturePrefs] is the Android store; [NativeEpubScreen] and
 * [ReadingSettingsRoute] share this object as the single fact source.
 */
data class ReaderFeatureState(
    val fontScale: Float = 1.0f,
    val lineHeightRatio: Float = 1.5f,
    val marginHorizontalPx: Float = 0f,
    val bionic: Boolean = false,
    val ruler: Boolean = false,
    val rulerLineHeight: Float = 3f,
    val paragraphMode: Boolean = false,
    val speedReading: Boolean = false,
    val speedWpm: Int = 300,
    val textRulesJson: String = "",
    /** CSS column-count for a page (1 = phone default, 2 = desktop wide). */
    val columnsPerPage: Int = 1,
    /** FontManager / FontCatalog key; blank = system default. */
    val fontKey: String = "",
    /** PDF / comic double-page spread. */
    val doublePage: Boolean = false,
    /** Foliate paginated vs scrolled layout (true = continuous scroll). */
    val scrolled: Boolean = true,
    /** Desktop `customBookCSS` when [customBookCssEnabled]. */
    val customBookCss: String = "",
    /** Desktop `isCustomBookCSS === "yes"`. */
    val customBookCssEnabled: Boolean = false,
    /** [ReaderPerformancePolicy.TIER_NORMAL] or [ReaderPerformancePolicy.TIER_LITE]. */
    val perfTier: String = ReaderPerformancePolicy.TIER_NORMAL,
    /** Uniform inset crop on PDF raster pages (output pixels, 0 = off). */
    val pdfCropMarginPx: Float = 0f,
    /** PDF text reflow (html from getTextContent) instead of bitmap pages. */
    val pdfReflow: Boolean = false,
)

object ReaderFeatureCodec {

    const val KEY_FONT_SCALE = "fontScale"
    const val KEY_LINE_HEIGHT = "lineHeightRatio"
    const val KEY_MARGIN = "marginHorizontalPx"
    const val KEY_BIONIC = "bionic"
    const val KEY_RULER = "ruler"
    const val KEY_RULER_LINES = "rulerLineHeight"
    const val KEY_PARAGRAPH = "paragraphMode"
    const val KEY_SPEED = "speedReading"
    const val KEY_SPEED_WPM = "speedWpm"
    const val KEY_TEXT_RULES = "textRulesJson"
    const val KEY_COLUMNS = "columnsPerPage"
    const val KEY_FONT = "fontKey"
    const val KEY_DOUBLE_PAGE = "doublePage"
    const val KEY_SCROLLED = "scrolled"
    const val KEY_CUSTOM_BOOK_CSS = "customBookCss"
    const val KEY_CUSTOM_BOOK_CSS_ENABLED = "customBookCssEnabled"
    const val KEY_PERF_TIER = "perfTier"
    const val KEY_PDF_CROP = "pdfCropMarginPx"
    const val KEY_PDF_REFLOW = "pdfReflow"

    const val FONT_SIZE_DEFAULT = 17f
    const val PDF_CROP_MIN = 0f
    const val PDF_CROP_MAX = 48f
    const val FONT_SCALE_MIN = 0.7f
    const val FONT_SCALE_MAX = 2.5f
    const val LINE_HEIGHT_MIN = 1f
    const val LINE_HEIGHT_MAX = 2f
    const val MARGIN_MIN = -40f
    const val MARGIN_MAX = 80f
    const val SPEED_WPM_MIN = 100
    const val SPEED_WPM_MAX = 900
    const val SPEED_WPM_DEFAULT = 300

    fun fromMap(map: Map<String, String>): ReaderFeatureState = ReaderFeatureState(
        fontScale = floatOf(map[KEY_FONT_SCALE], 1.0f),
        lineHeightRatio = floatOf(map[KEY_LINE_HEIGHT], 1.5f),
        marginHorizontalPx = floatOf(map[KEY_MARGIN], 0f),
        bionic = boolOf(map[KEY_BIONIC]),
        ruler = boolOf(map[KEY_RULER]),
        rulerLineHeight = floatOf(map[KEY_RULER_LINES], 3f),
        paragraphMode = boolOf(map[KEY_PARAGRAPH]),
        speedReading = boolOf(map[KEY_SPEED]),
        speedWpm = intOf(map[KEY_SPEED_WPM], SPEED_WPM_DEFAULT),
        textRulesJson = map[KEY_TEXT_RULES].orEmpty(),
        columnsPerPage = intOf(map[KEY_COLUMNS], 1),
        fontKey = map[KEY_FONT].orEmpty(),
        doublePage = boolOf(map[KEY_DOUBLE_PAGE]),
        scrolled = if (map[KEY_SCROLLED] == null) true else boolOf(map[KEY_SCROLLED]),
        customBookCss = map[KEY_CUSTOM_BOOK_CSS].orEmpty(),
        customBookCssEnabled = boolOf(map[KEY_CUSTOM_BOOK_CSS_ENABLED]),
        perfTier = map[KEY_PERF_TIER]?.takeIf { it.isNotBlank() }
            ?: ReaderPerformancePolicy.TIER_NORMAL,
        pdfCropMarginPx = floatOf(map[KEY_PDF_CROP], 0f),
        pdfReflow = boolOf(map[KEY_PDF_REFLOW]),
    )

    fun toMap(state: ReaderFeatureState): Map<String, String> = mapOf(
        KEY_FONT_SCALE to state.fontScale.toString(),
        KEY_LINE_HEIGHT to state.lineHeightRatio.toString(),
        KEY_MARGIN to state.marginHorizontalPx.toString(),
        KEY_BIONIC to state.bionic.toString(),
        KEY_RULER to state.ruler.toString(),
        KEY_RULER_LINES to state.rulerLineHeight.toString(),
        KEY_PARAGRAPH to state.paragraphMode.toString(),
        KEY_SPEED to state.speedReading.toString(),
        KEY_SPEED_WPM to state.speedWpm.toString(),
        KEY_TEXT_RULES to state.textRulesJson,
        KEY_COLUMNS to clampedColumns(state).toString(),
        KEY_FONT to state.fontKey,
        KEY_DOUBLE_PAGE to state.doublePage.toString(),
        KEY_SCROLLED to state.scrolled.toString(),
        KEY_CUSTOM_BOOK_CSS to state.customBookCss,
        KEY_CUSTOM_BOOK_CSS_ENABLED to state.customBookCssEnabled.toString(),
        KEY_PERF_TIER to state.perfTier,
        KEY_PDF_CROP to clampedPdfCrop(state).toString(),
        KEY_PDF_REFLOW to state.pdfReflow.toString(),
    )

    fun userStylesheet(state: ReaderFeatureState): String =
        if (state.customBookCssEnabled) state.customBookCss.trim() else ""

    fun fontSizePx(state: ReaderFeatureState): Float =
        FONT_SIZE_DEFAULT * state.fontScale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)

    fun clampedLineHeight(state: ReaderFeatureState): Float =
        state.lineHeightRatio.coerceIn(LINE_HEIGHT_MIN, LINE_HEIGHT_MAX)

    fun clampedMargin(state: ReaderFeatureState): Float =
        state.marginHorizontalPx.coerceIn(MARGIN_MIN, MARGIN_MAX)

    fun clampedWpm(state: ReaderFeatureState): Int =
        state.speedWpm.coerceIn(SPEED_WPM_MIN, SPEED_WPM_MAX)

    fun clampedColumns(state: ReaderFeatureState): Int =
        state.columnsPerPage.coerceIn(1, 8)

    fun clampedPdfCrop(state: ReaderFeatureState): Float =
        state.pdfCropMarginPx.coerceIn(PDF_CROP_MIN, PDF_CROP_MAX)

    private fun floatOf(raw: String?, default: Float): Float =
        raw?.toFloatOrNull() ?: default

    private fun intOf(raw: String?, default: Int): Int =
        raw?.toIntOrNull() ?: default

    private fun boolOf(raw: String?): Boolean =
        raw.equals("true", ignoreCase = true) || raw == "1"
}
