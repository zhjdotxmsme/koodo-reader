package com.koodoreader.core.designsystem

/**
 * Reader-facing typography defaults tuned for **CJK-first** typesetting.
 *
 * The desktop reader config (`sliderList.tsx` / `dropdownList.tsx`) stores
 * *desktop* values: `letterSpacing` 0, `paraSpacing` 0, `textAlign` "left",
 * and no first-line indent at all. Those defaults read fine for Latin prose but
 * produce three visible defects in Chinese text:
 *
 *  1. **Ragged right edge.** CJK glyphs are square and monospaced-ish, so a
 *     left-aligned column reads as a ragged wall rather than a page. Paper books
 *     justify; KOReader and Koodo Reader both justify Chinese by default.
 *  2. **No first-line indent.** Chinese convention marks a new paragraph with a
 *     two-character indent, not a blank line. Without it, Chinese paragraphs
 *     are indistinguishable from line wraps.
 *  3. **Touching ink.** CJK glyphs occupy the full em box and their ink
 *     frequently touches the neighbour's, which reads as smear at small
 *     sizes. Tracking can separate it — but a fixed hair of tracking also
 *     opens a visible gap inside Latin words, so the default tracking stays
 *     at the desktop's 0 and separating the ink is the reader's
 *     letter-spacing slider's call ([LETTER_SPACING_SP]).
 *
 * These are the *reader defaults* (what a Chinese reader sees before touching
 * any setting). They are deliberately NOT applied to the shell UI, and they do
 * NOT change the desktop wire format — the persisted `readerConfig` keys keep
 * their desktop values; these constants are the *initial* presentation the
 * native reader applies on top of them.
 *
 * ### Why sp, not px
 *
 * The desktop stores px because it is a desktop app. On Android the same
 * intent is expressed in sp so that the user's system font-scale preference is
 * honoured. The conversion happens once, in the settings layer, exactly like
 * the desktop's slider does.
 */
object CjkTypography {

    /**
     * Default line height, as a unitless multiple of the font size.
     *
     * 1.6 matches the desktop reader's "Default" row and is a comfortable
     * measure for Chinese body text: dense enough to keep a page full,
     * open enough that a line of ideographs does not touch the next.
     */
    const val LINE_HEIGHT_RATIO = 1.6f

    /**
     * Default text alignment for the native reader.
     *
     * `Justify` makes the right edge of the column flush, which is the single
     * biggest perceived-quality win for Chinese body text. The engine already
     * implements justification (`LayoutEngine` stretches inter-word gaps and
     * excludes the last line of a block, matching CSS `text-align: justify`).
     */
    const val DEFAULT_TEXT_ALIGN = "justify"

    /**
     * First-line indent, in **em** (character widths).
     *
     * 2em is the Chinese typographic convention (「首行缩进两字符」). The
     * engine multiplies this by the block's own font size, so it scales with
     * the user's font-size setting instead of being pinned to a pixel value.
     */
    const val FIRST_LINE_INDENT_EM = 2f

    /**
     * Default letter spacing, in **sp**.
     *
     * `0` — the desktop default. An earlier default added a hair of tracking
     * (0.5 sp) to separate CJK ink, which fills its whole em box and
     * frequently touches the neighbour's; the same hair, however, opened a
     * visible gap inside Latin words and moved every line break, so tracking
     * is **opt-in** now: the reader's letter-spacing slider adds up to
     * ±0.1 sp on top of this base, and the native engine accepts the signed
     * result as long as the cell advance stays positive.
     *
     * ## Why a non-zero tracking cannot cause the "字距忽大忽小" problem
     *
     * Tracking is only harmful when it is *added on top of* a stretched
     * justification. That is avoided by [CjkColumn] column quantisation: the
     * column is fitted to a whole number of character cells — a cell being the
     * font size **plus** the tracking — and the first-line indent is rounded
     * to the same grid. Every line therefore ends exactly on the grid, and
     * the justification engine has no slack to distribute, so whatever
     * tracking is chosen stays identical on every single line.
     *
     * The two features are a pair: quantisation makes any fixed tracking
     * value hold on every line, and the slider decides whether there is
     * tracking to hold.
     */
    const val LETTER_SPACING_SP = 0f

    /**
     * Shared floor for user-adjusted tracking, in sp. The layout engine's desktop
     * config parser and the Android settings codec must use this one value so the
     * accepted range cannot drift between modules.
     */
    const val LETTER_SPACING_MIN_SP = -0.1f

    /**
     * Extra spacing between paragraphs, in **dp**.
     *
     * The requested range is 8–12dp; 10dp sits in the middle and reads as
     * "clearly separate" without adding so much air that a short page holds
     * only a dozen lines.
     */
    const val PARAGRAPH_SPACING_DP = 10f

    /**
     * Horizontal page margin, in **dp**.
     *
     * 20dp keeps the text off the screen edge on every phone size and matches
     * the default already used by the native reader's chrome settings.
     */
    const val HORIZONTAL_MARGIN_DP = 20f

    /**
     * Converts [LETTER_SPACING_SP] to the **px** value the layout engine
     * expects, honouring both the font scale and the display density.
     *
     * @param density display density (px per dp)
     * @param fontScale the reader's font-size multiplier
     */
    fun letterSpacingPx(density: Float, fontScale: Float = 1f): Float =
        LETTER_SPACING_SP * density * fontScale.coerceAtLeast(0.5f)

    /**
     * Converts [FIRST_LINE_INDENT_EM] to the **px** value the layout engine
     * expects for a given font size.
     */
    fun firstLineIndentPx(fontSizePx: Float): Float = FIRST_LINE_INDENT_EM * fontSizePx

    /**
     * Converts [PARAGRAPH_SPACING_DP] to the **px** value the layout engine
     * expects.
     */
    fun paragraphSpacingPx(density: Float): Float = PARAGRAPH_SPACING_DP * density
}
