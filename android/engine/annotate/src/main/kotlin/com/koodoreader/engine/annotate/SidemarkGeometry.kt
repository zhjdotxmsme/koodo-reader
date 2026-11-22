package com.koodoreader.engine.annotate

/**
 * Where the page-margin tick ("sidemark") of a highlight goes (P1-10).
 *
 * Both rendering tracks draw the same shape, so the geometry lives here and is
 * unit-tested: a 3px vertical tick inside the page's side margin, level with the
 * highlighted line. The native track paints it on its Canvas; the foliate track
 * appends an SVG `<rect>` to the highlight's overlay group.
 *
 * The mark is anchored to the **text column**, not to the highlighted run: it
 * means "there is a note on this line", so it must land in the same place
 * whether the selection starts a line or sits in the middle of one. The first
 * device run drew it beside the selected word — mid-column — which read as a
 * stray artefact rather than a margin mark.
 *
 * The point of the mark is that a note must be *visible while reading* — a
 * translucent band over the text is easy to skim past, a coloured tick in the
 * margin is not.
 */
object SidemarkGeometry {

    /** Tick thickness in px. Thin enough to read as a mark, not as a border. */
    const val WIDTH_PX = 3f

    /** Clearance between the tick and the column edge, in px. */
    const val GAP_PX = 2f

    /** Alpha of the tick on a night page: the tint is already dark, so soften. */
    const val DARK_ALPHA = 0x8C

    /** Alpha of the tick on a day page — the mark is the point, so keep it solid. */
    const val LIGHT_ALPHA = 0xFF

    /**
     * The tick's left edge, in page px, or null when it should not be drawn.
     *
     * @param lineLeftPx the line's ink x — [LayoutLine.x], which is *after* the
     *   first-line indent.
     * @param lineRightPx the line's right edge including justification.
     * @param indentPx the line's own first-line indent ([LayoutLine.indentPx]);
     *   subtracted to get back to the column's left edge, so an indented first
     *   line and a flush one put the tick in the same place.
     * @param rtl mirror to the right margin, where an RTL page's outer edge is.
     * @param pageWidthPx the page width, used to keep the tick on the page.
     * @return the tick's left edge, or null when the margin is too narrow to
     *   hold it — the caller then draws no mark rather than one over the
     *   glyphs.
     */
    fun tickLeftPxOrNull(
        lineLeftPx: Float,
        lineRightPx: Float,
        indentPx: Float = 0f,
        rtl: Boolean = false,
        pageWidthPx: Float = Float.NaN,
    ): Float? {
        val indent = if (indentPx.isFinite()) indentPx else 0f
        // Room the tick needs outside the text.
        val needed = GAP_PX + WIDTH_PX
        if (rtl) {
            // `rightPx` already includes the justification stretch and the
            // indent does not shift the right edge, so it needs no correction.
            if (!lineRightPx.isFinite()) return null
            val margin = if (pageWidthPx.isFinite()) pageWidthPx - lineRightPx else Float.NaN
            if (!margin.isFinite() || margin < needed) return null
            val left = lineRightPx + GAP_PX
            val maxLeft = pageWidthPx - WIDTH_PX
            return if (maxLeft <= 0f) null else left.coerceIn(0f, maxLeft)
        }
        if (!lineLeftPx.isFinite()) return null
        val columnLeft = lineLeftPx - indent
        if (columnLeft < needed) return null
        return columnLeft - GAP_PX - WIDTH_PX
    }

    /**
     * The page-margin tick colour for a highlight band colour (P1-10).
     *
     * @param highlightArgb the 30%-alpha band colour the line is painted with.
     */
    fun tickArgb(highlightArgb: Long, dark: Boolean = false): Long {
        val alpha = if (dark) DARK_ALPHA else LIGHT_ALPHA
        return (alpha.toLong() shl 24) or (highlightArgb and 0xFFFFFF)
    }

    /** [tickArgb] applied to every entry of a block → band-colour map. */
    fun tickColors(bands: Map<Int, Long>, dark: Boolean = false): Map<Int, Long> =
        if (bands.isEmpty()) emptyMap() else bands.mapValues { (_, argb) -> tickArgb(argb, dark) }
}
