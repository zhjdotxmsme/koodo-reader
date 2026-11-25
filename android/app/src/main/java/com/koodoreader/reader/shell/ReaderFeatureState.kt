package com.koodoreader.reader.shell

import com.koodoreader.core.designsystem.CjkTypography
import kotlin.math.roundToInt

/**
 * Native reading typography + P6 enhancement switches.
 *
 * Kept as a plain data class so JVM tests can pin the desktop-parity defaults
 * (`fontSize` 17, `lineHeight` 1.6, `margin` 0) without SharedPreferences.
 * [ReaderFeaturePrefs] is the Android store; [NativeEpubScreen] and
 * [ReadingSettingsRoute] share this object as the single fact source.
 */
data class ReaderFeatureState(
    val fontScale: Float = 1.0f,
    val lineHeightRatio: Float = 1.6f,
    /** Extra tracking on top of the CJK default, in **sp** (negative = tighter). */
    val letterSpacingSp: Float = 0f,
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
    /** Extra spacing between paragraphs in px (0 = default). */
    val paragraphSpacingPx: Float = 0f,
    /** Screen brightness 0..1; negative = follow the system setting. */
    val brightness: Float = -1f,
    val keepScreenOn: Boolean = false,
    /** "system" | "portrait" | "landscape". */
    val orientation: String = "system",
    /** Custom page background ARGB; null = theme default. */
    val customBackgroundArgb: Int? = null,
)

object ReaderFeatureCodec {

    const val KEY_FONT_SCALE = "fontScale"
    const val KEY_LINE_HEIGHT = "lineHeightRatio"
    const val KEY_LETTER_SPACING = "letterSpacingSp"
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
    const val KEY_PARAGRAPH_SPACING = "paragraphSpacingPx"
    const val KEY_BRIGHTNESS = "brightness"
    const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
    const val KEY_ORIENTATION = "orientation"
    const val KEY_CUSTOM_BACKGROUND = "customBackgroundArgb"

    const val ORIENTATION_SYSTEM = "system"
    const val ORIENTATION_PORTRAIT = "portrait"
    const val ORIENTATION_LANDSCAPE = "landscape"
    const val PARAGRAPH_SPACING_MIN = 0f
    const val PARAGRAPH_SPACING_MAX = 48f

    const val FONT_SIZE_DEFAULT = 17f
    const val PDF_CROP_MIN = 0f
    const val PDF_CROP_MAX = 48f
    const val FONT_SCALE_MIN = 14f / 17f
    const val FONT_SCALE_MAX = 22f / 17f
    const val LINE_HEIGHT_MIN = 1f
    const val LINE_HEIGHT_MAX = 2f
    /** Slider granularity for the line height — 21 stops between min and max. */
    const val LINE_HEIGHT_STEP = 0.05f
    const val LETTER_SPACING_MIN = CjkTypography.LETTER_SPACING_MIN_SP
    const val LETTER_SPACING_MAX = 0.1f
    const val LETTER_SPACING_STEP = 0.01f
    const val MARGIN_MIN = -40f
    const val MARGIN_MAX = 80f
    const val SPEED_WPM_MIN = 100
    const val SPEED_WPM_MAX = 900
    const val SPEED_WPM_DEFAULT = 300

    fun fromMap(map: Map<String, String>): ReaderFeatureState = ReaderFeatureState(
        fontScale = floatOf(map[KEY_FONT_SCALE], 1.0f),
        lineHeightRatio = floatOf(map[KEY_LINE_HEIGHT], 1.6f),
        letterSpacingSp = floatOf(map[KEY_LETTER_SPACING], 0f),
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
        paragraphSpacingPx = floatOf(map[KEY_PARAGRAPH_SPACING], 0f),
        brightness = floatOf(map[KEY_BRIGHTNESS], -1f),
        keepScreenOn = boolOf(map[KEY_KEEP_SCREEN_ON]),
        orientation = map[KEY_ORIENTATION]?.takeIf {
            it == ORIENTATION_PORTRAIT || it == ORIENTATION_LANDSCAPE
        } ?: ORIENTATION_SYSTEM,
        customBackgroundArgb = map[KEY_CUSTOM_BACKGROUND]?.toIntOrNull(),
    )

    fun toMap(state: ReaderFeatureState): Map<String, String> = mapOf(
        KEY_FONT_SCALE to state.fontScale.toString(),
        KEY_LINE_HEIGHT to snappedLineHeight(state.lineHeightRatio).toString(),
        KEY_LETTER_SPACING to clampedLetterSpacing(state).toString(),
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
        KEY_PARAGRAPH_SPACING to clampedParagraphSpacing(state).toString(),
        KEY_BRIGHTNESS to state.brightness.toString(),
        KEY_KEEP_SCREEN_ON to state.keepScreenOn.toString(),
        KEY_ORIENTATION to state.orientation,
        KEY_CUSTOM_BACKGROUND to state.customBackgroundArgb?.toString().orEmpty(),
    )

    /**
     * The paragraph gap actually applied, in **dp**.
     *
     * This is the value the slider shows and the settings store persists — a
     * display-independent number, so the same setting looks the same on a
     * 2.0-density phone and a 3.5-density tablet. The two rendering tracks then
     * convert it themselves: the native engine multiplies by density (see
     * [effectiveParagraphSpacingPx]), the WebView uses it as CSS px, where one
     * CSS px *is* one dp.
     *
     * When the user has not touched the control, the CJK default applies
     * (10dp) — a fixed, comfortable gap between paragraphs. The older
     * derivation (1.2 × the line height) grew with the font size and pushed
     * paragraphs further apart the larger the text became, which is not what a
     * reader wants.
     */
    fun effectiveParagraphSpacingDp(state: ReaderFeatureState): Float {
        val explicit = clampedParagraphSpacing(state)
        return if (explicit > 0f) explicit else CjkTypography.PARAGRAPH_SPACING_DP
    }

    /**
     * The paragraph gap in **px** for the native layout engine.
     *
     * @param density display density, used to turn the dp setting into px.
     */
    fun effectiveParagraphSpacingPx(state: ReaderFeatureState, density: Float = 1f): Float =
        effectiveParagraphSpacingDp(state) * density

    /**
     * The paragraph gap for the WebView track, in CSS px.
     *
     * CSS px and dp are the same unit inside a WebView, so this is the dp value
     * unchanged — passing density-scaled px here (the old behaviour) made the
     * same setting render three times wider in EPUB than in a TXT file on a
     * 3× screen.
     */
    fun effectiveParagraphSpacingCssPx(state: ReaderFeatureState): Float =
        effectiveParagraphSpacingDp(state)

    private fun cssPx(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else "%.2f".format(java.util.Locale.US, value)

    /**
     * The user stylesheet injected into the Foliate (WebView) track.
     *
     * The WebView track lays the book out in a real browser, so the same
     * typography intent the native engine expresses in pixels has to be
     * expressed here as CSS. The two must agree, otherwise the same book
     * breaks its lines differently depending on which track renders it —
     * which is exactly what [ReaderTypography] exists to prevent.
     *
     * @param state the reader's feature state (font scale, paragraph spacing).
     * @param firstLineIndent whether to indent the first line of a paragraph.
     * @param density display density, needed to convert sp of tracking into px.
     */
    fun userStylesheet(
        state: ReaderFeatureState,
        firstLineIndent: Boolean = false,
        density: Float = 1f,
    ): String {
        val custom = if (state.customBookCssEnabled) state.customBookCss.trim() else ""
        val base = ReaderTypography.userStylesheet(
            density = density,
            fontScale = state.fontScale,
            firstLineIndent = firstLineIndent,
            justify = true,
            explicitParagraphSpacingPx = effectiveParagraphSpacingCssPx(state),
            letterSpacingSp = clampedLetterSpacing(state),
        )
        // A real newline between rule blocks. The previous "\\n" wrote a
        // literal backslash-n into the stylesheet whenever custom CSS was
        // enabled — a stray token between rules that WebKit swallows at best.
        return listOf(base, custom).filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun fontSizePx(state: ReaderFeatureState): Float =
        FONT_SIZE_DEFAULT * state.fontScale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)

    /**
     * Tracking as the WebView track sees it, in **CSS px** (= dp): the CJK
     * default plus the reader's adjustment, scaled with the font size.
     *
     * Also what the foliate column quantisation needs: a cell is the font size
     * plus this value.
     */
    fun letterSpacingCssPx(state: ReaderFeatureState): Float =
        (CjkTypography.LETTER_SPACING_SP + clampedLetterSpacing(state)) *
            state.fontScale.coerceAtLeast(0.5f)

    fun clampedLineHeight(state: ReaderFeatureState): Float =
        snappedLineHeight(state.lineHeightRatio.coerceIn(LINE_HEIGHT_MIN, LINE_HEIGHT_MAX))

    fun clampedLetterSpacing(state: ReaderFeatureState): Float =
        state.letterSpacingSp.coerceIn(LETTER_SPACING_MIN, LETTER_SPACING_MAX)

    fun snappedLetterSpacing(value: Float): Float {
        if (!value.isFinite()) return 0f
        val snapped = (value / LETTER_SPACING_STEP).roundToInt() * LETTER_SPACING_STEP
        return snapped.coerceIn(LETTER_SPACING_MIN, LETTER_SPACING_MAX)
    }

    /**
     * Line height rounded to the slider's [LINE_HEIGHT_STEP] grid, then clamped.
     *
     * A continuous slider leaves values like 1.6327498, which makes the same
     * setting impossible to dial back to and prints eight digits next to the
     * slider. Snapping is also applied on load, so a store written by an older
     * build lands on the grid instead of producing a half-step.
     */
    fun snappedLineHeight(value: Float): Float {
        if (!value.isFinite()) return LINE_HEIGHT_MIN
        val snapped = (value / LINE_HEIGHT_STEP).roundToInt() * LINE_HEIGHT_STEP
        return snapped.coerceIn(LINE_HEIGHT_MIN, LINE_HEIGHT_MAX)
    }

    /**
     * Font scale snapped so the rendered body lands on a whole pixel size.
     *
     * The slider moves in one-pixel steps of the 17px default (14…22px): a
     * fractional size makes the CJK column grid and the glyph advances
     * disagree, which is exactly the uneven tracking the quantisation exists
     * to avoid.
     */
    fun snappedFontScale(value: Float): Float {
        val clamped = if (value.isFinite()) {
            value.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
        } else {
            1f
        }
        val px = (FONT_SIZE_DEFAULT * clamped).roundToInt()
            .coerceIn((FONT_SIZE_DEFAULT * FONT_SCALE_MIN).roundToInt(),
                (FONT_SIZE_DEFAULT * FONT_SCALE_MAX).roundToInt())
        return px / FONT_SIZE_DEFAULT
    }

    fun clampedMargin(state: ReaderFeatureState): Float =
        state.marginHorizontalPx.coerceIn(MARGIN_MIN, MARGIN_MAX)

    fun clampedWpm(state: ReaderFeatureState): Int =
        state.speedWpm.coerceIn(SPEED_WPM_MIN, SPEED_WPM_MAX)

    fun clampedColumns(state: ReaderFeatureState): Int =
        state.columnsPerPage.coerceIn(1, 8)

    fun clampedPdfCrop(state: ReaderFeatureState): Float =
        state.pdfCropMarginPx.coerceIn(PDF_CROP_MIN, PDF_CROP_MAX)

    fun clampedParagraphSpacing(state: ReaderFeatureState): Float =
        state.paragraphSpacingPx.coerceIn(PARAGRAPH_SPACING_MIN, PARAGRAPH_SPACING_MAX)

    private fun floatOf(raw: String?, default: Float): Float =
        raw?.toFloatOrNull() ?: default

    private fun intOf(raw: String?, default: Int): Int =
        raw?.toIntOrNull() ?: default

    private fun boolOf(raw: String?): Boolean =
        raw.equals("true", ignoreCase = true) || raw == "1"
}
