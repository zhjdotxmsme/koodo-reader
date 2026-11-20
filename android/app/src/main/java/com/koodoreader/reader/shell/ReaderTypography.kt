package com.koodoreader.reader.shell

import com.koodoreader.core.designsystem.CjkStyleSheet
import com.koodoreader.core.designsystem.CjkTypography
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.TextAlign
import com.koodoreader.engine.layout.TextJustify

/**
 * The reader's **Chinese-first typography defaults**, resolved into the pixel
 * values the layout engine consumes.
 *
 * ## Why this lives in the app layer
 *
 * `:core:designsystem` owns the *values* ([CjkTypography]); `:engine:layout`
 * owns the *algorithm* (justification, indentation, tracking). This object is
 * the seam between them: it converts "0.5sp of tracking" into "N px at this
 * density and font scale", and it does so in exactly one place so that the
 * EPUB canvas track, the native text track and the PDF reflow track cannot
 * drift apart.
 *
 * ## Why the defaults differ from the desktop
 *
 * The desktop reader config stores `letterSpacing: 0`, `paraSpacing: 0`,
 * `textAlign: "left"` and no first-line indent. Those are *desktop* defaults
 * tuned for Latin prose. On a phone, reading Chinese, they produce a ragged
 * right edge, no paragraph breaks and glyphs that touch. The native reader
 * therefore applies the CJK defaults on top of whatever the desktop config
 * carries — the persisted config still round-trips to the desktop unchanged,
 * but the native presentation is correct for the language actually on screen.
 *
 * A user who has *explicitly* chosen a value in the settings keeps it: this
 * object supplies the **fallback**, and the settings layer only overrides it
 * when the user moved the control.
 */
object ReaderTypography {

    /**
     * The fallback alignment.
     *
     * `Justify` — a flush right edge is what makes a Chinese column look
     * typeset rather than dumped. The engine excludes the last line of each
     * block from justification, exactly as CSS `text-align: justify` does.
     */
    val fallbackTextAlign: TextAlign = TextAlign.JUSTIFY

    /**
     * The fallback letter spacing, in px, for the given density and font scale.
     */
    fun letterSpacingPx(density: Float, fontScale: Float = 1f): Float =
        CjkTypography.letterSpacingPx(density, fontScale)

    /**
     * The fallback first-line indent, in px, for the given font size.
     *
     * Two character widths, scaled by the *block's own* font size inside the
     * engine, so a heading is not indented by two body-size characters.
     */
    fun firstLineIndentPx(fontSizePx: Float): Float =
        CjkTypography.firstLineIndentPx(fontSizePx)

    /**
     * The fallback paragraph spacing, in px.
     *
     * 10dp — inside the requested 8–12dp band. It only applies when the
     * reader has not set an explicit paragraph spacing; a book's own CSS
     * margins still win inside the engine.
     */
    fun paragraphSpacingPx(density: Float, fontScale: Float = 1f): Float =
        CjkTypography.paragraphSpacingPx(density) * fontScale.coerceAtLeast(0.5f)

    /**
     * Builds the [PaginatorOptions] for the native reader.
     *
     * @param density display density (px per dp)
     * @param fontScale the reader's font-size multiplier
     * @param explicitParagraphSpacingPx the user's explicit paragraph spacing,
     *   or 0 when they have not touched the control — in which case the CJK
     *   default applies.
     * @param firstLineIndent whether the reader's first-line indent is on.
     */
    fun paginatorOptions(
        density: Float,
        fontScale: Float = 1f,
        /** Body font size in **sp** — the engine multiplies by density itself. */
        fontSizePx: Float = 17f,
        lineHeightMultiple: Float = 1.6f,
        /** Horizontal page margin in **dp**. */
        marginHorizontalPx: Float = 0f,
        /** Vertical page margin in **dp**. */
        marginVerticalPx: Float = 0f,
        columnsPerPage: Int = 1,
        /** Explicit paragraph gap in **dp**, or 0 when the user has not set one. */
        explicitParagraphSpacingPx: Float = 0f,
        firstLineIndent: Boolean = true,
    ): PaginatorOptions = PaginatorOptions(
        columnsPerPage = columnsPerPage,
        fontSizePx = fontSizePx,
        lineHeightMultiple = lineHeightMultiple,
        // The engine works in physical pixels: everything that comes from the
        // settings layer is authored in dp and is scaled here, once. Doing it
        // at the call sites is how the two tracks drifted apart (a 10dp gap
        // became 10px in EPUB and 30px in TXT on a 3× screen).
        marginHorizontalPx = marginHorizontalPx * density,
        marginVerticalPx = marginVerticalPx * density,
        paraSpacingPx = if (explicitParagraphSpacingPx > 0f) {
            explicitParagraphSpacingPx * density
        } else {
            // Must match the WebView track exactly: 10dp, NOT scaled by the
            // font multiplier (the paragraph gap is a fixed breathing space,
            // and scaling it made large type push paragraphs apart).
            CjkTypography.PARAGRAPH_SPACING_DP * density
        },
        textAlign = fallbackTextAlign,
        firstLineIndentPx = if (firstLineIndent) {
            // 2em of the *body* font, so it has to be converted to px with the
            // same density the measurer uses.
            firstLineIndentPx(fontSizePx * density)
        } else {
            0f
        },
        letterSpacingPx = letterSpacingPx(density, fontScale),
        // Chinese has no word gaps, so the CSS default (stretch the spaces)
        // would leave a justified Chinese column ragged. Spread the slack over
        // the gaps between ideographs instead.
        textJustify = TextJustify.INTER_IDEOGRAPH,
        // Fit the column to a whole number of character cells (font size +
        // tracking) and round the first-line indent to the same grid. Without
        // it every line ends with a few pixels of slack that the
        // justification engine spreads into the glyph gaps — the "假字距"
        // that makes a Chinese page look typeset by an amateur.
        quantizeColumnToCells = true,
    )

    /**
     * The CSS the Foliate (WebView) track injects for the same typography.
     *
     * The WebView track does not use the native layout engine — it lays the
     * book out in a real browser — so the same intent has to be expressed as
     * CSS. Keeping the values in one place is what stops the two tracks from
     * showing the same book with different line breaks.
     *
     * @param firstLineIndent whether to indent the first line of a paragraph.
     * @param justify whether to justify the column.
     */
    @Suppress("UNUSED_PARAMETER")
    fun userStylesheet(
        density: Float,
        fontScale: Float = 1f,
        firstLineIndent: Boolean = true,
        justify: Boolean = true,
        explicitParagraphSpacingPx: Float = 0f,
    ): String = CjkStyleSheet.build(
        CjkStyleSheet.Rule(
            // CSS px, not physical px: inside a WebView one CSS px is one dp.
            // Scaling by density here is what made an EPUB paragraph gap look
            // three times wider than the same setting in a TXT file.
            paragraphGapPx = if (explicitParagraphSpacingPx > 0f) {
                explicitParagraphSpacingPx
            } else {
                CjkTypography.PARAGRAPH_SPACING_DP * fontScale.coerceAtLeast(0.5f)
            },
            firstLineIndentEm = CjkTypography.FIRST_LINE_INDENT_EM,
            // CSS px again: 0.5sp of tracking is 0.5 CSS px, not 0.5 × density.
            letterSpacingPx = CjkTypography.LETTER_SPACING_SP * fontScale.coerceAtLeast(0.5f),
            justify = justify,
            indentFirstLine = firstLineIndent,
        ),
    )
}
