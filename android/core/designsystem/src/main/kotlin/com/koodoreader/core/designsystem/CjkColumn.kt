package com.koodoreader.core.designsystem

import kotlin.math.floor

/**
 * Column-width quantisation for CJK typesetting.
 *
 * ## The problem this solves
 *
 * Chinese and Japanese body text is a grid: every glyph occupies a full em
 * box, so a line holds an integer number of characters. If the text column is
 * *not* an integer multiple of the character advance, every single line has a
 * few pixels of slack left over, and the justification engine must spread that
 * slack into the gaps between glyphs. The reader sees letter spacing that
 * changes from line to line — "假字距" — which is the single most common reason
 * a Chinese page looks typeset by an amateur.
 *
 * KOReader solves it with a hint (`-cr-hint: cjk-tailored`) that tailors the
 * paragraph width and text-indent to an integer multiple of the font size
 * precisely so that "lines of Chinese and Japanese characters don't need space
 * added between glyphs for text justification".
 *
 * ## The trade
 *
 * Quantising means the column can be up to one glyph narrower than the space
 * available. The leftover is split evenly between the two margins, so the text
 * stays centred and the difference is neither visible nor measurable by eye
 * (at most ~half a character on each side).
 *
 * ## What counts as one cell
 *
 * A cell is the font size plus tracking. With `letter-spacing: 0` — the CJK
 * default — a cell is exactly the em box, and [cells] is the number of
 * characters that fit on a line.
 *
 * ## What one cell is
 *
 * A cell is the font size plus the tracking: the advance one character
 * occupies, apart from the tracking that separates it from the next one.
 *
 * Tracking is added BETWEEN characters, never after the last one, so a run
 * of N characters is
 *
 *     N × fontSize + (N - 1) × tracking
 *
 * wide. That is the width the column has to be a whole number of. Quantising
 * to anything else leaves every line a fraction of a cell short of the
 * column, and the justification engine would then have to distribute that
 * remainder into the glyph gaps — the very thing the quantisation exists to
 * prevent.
 */
object CjkColumn {

    /** A quantised column: [widthPx] is an exact multiple of the cell advance. */
    data class Fit(
        /** Usable text width, an exact multiple of the cell advance. */
        val widthPx: Float,
        /** Extra padding to add on *each* side so the column stays centred. */
        val sidePadPx: Float,
        /** Number of character cells per line. */
        val cells: Int,
        /** The advance of one cell (font size + tracking), in px. */
        val cellPx: Float,
    )

    /**
     * Fits a text column of [availablePx] to a whole number of cells.
     *
     * @param availablePx width between the page's horizontal margins.
     * @param fontSizePx body font size in px.
     * @param letterSpacingPx tracking in px (0 for the CJK default).
     * @param minCells give up quantisation rather than produce a column
     *   narrower than this; protects against absurd inputs (e.g. a 200px
     *   font on a 320px screen).
     */
    fun fit(
        availablePx: Float,
        fontSizePx: Float,
        letterSpacingPx: Float = 0f,
        minCells: Int = 4,
    ): Fit {
        val cell = fontSizePx + letterSpacingPx
        if (!cell.isFinite() || cell <= 0f || !availablePx.isFinite() || availablePx <= 0f) {
            // A degenerate input must not leak NaN into the layout: the
            // column width is multiplied into the page geometry, and a single
            // NaN there silently blanks the whole page.
            //
            // `coerceAtLeast` is NOT enough here — it compares with `<`, and
            // every comparison against NaN is false, so NaN passes through
            // untouched. Hence the explicit `isFinite` checks.
            return Fit(
                widthPx = if (availablePx.isFinite()) availablePx.coerceAtLeast(0f) else 0f,
                sidePadPx = 0f,
                cells = 0,
                cellPx = if (cell.isFinite()) cell.coerceAtLeast(0f) else 0f,
            )
        }
        // A run of N characters is N × fontSize + (N-1) × tracking wide, so
        // the largest N that fits satisfies
        //
        //     N × (fontSize + tracking) ≤ available + tracking
        //
        // and the column is then that run's real width.
        val cells = floor((availablePx + letterSpacingPx) / cell).toInt()
        if (cells < minCells) {
            // Too narrow to quantise meaningfully — leave the column alone
            // rather than shrinking the text area to a few characters.
            return Fit(availablePx, 0f, cells.coerceAtLeast(0), cell)
        }
        val width = cells * fontSizePx + (cells - 1).coerceAtLeast(0) * letterSpacingPx
        return Fit(
            widthPx = width,
            sidePadPx = (availablePx - width) / 2f,
            cells = cells,
            cellPx = cell,
        )
    }
}
