package com.koodoreader.engine.annotate

/**
 * Where the page-margin tick ("sidemark") of a highlight goes (P1-10).
 *
 * Both rendering tracks draw the same shape, so the geometry lives here and is
 * unit-tested: a 3px vertical tick just outside the highlighted line's ink, in
 * the page margin. The native track paints it on its Canvas; the foliate track
 * appends an SVG `<rect>` to the highlight's overlay group.
 *
 * The point of the mark is that a note must be *visible while reading* — a
 * translucent band over the text is easy to skim past, a coloured tick in the
 * margin is not.
 */
object SidemarkGeometry {

    /** Tick thickness in px. Thin enough to read as a mark, not as a border. */
    const val WIDTH_PX = 3f

    /** Distance between the tick and the line's ink, in px. */
    const val GAP_PX = 6f

    /** Alpha of the tick on a night page: the tint is already dark, so soften. */
    const val DARK_ALPHA = 0x8C

    /** Alpha of the tick on a day page — the mark is the point, so keep it solid. */
    const val LIGHT_ALPHA = 0xFF

    /**
     * The tick's left edge, in page px.
     *
     * @param lineLeftPx the line's own left ink edge.
     * @param lineRightPx the line's right edge including justification.
     * @param rtl mirror to the right margin, where an RTL page's outer edge is.
     * @param pageWidthPx the page width, used to keep the tick on the page.
     */
    fun tickLeftPx(
        lineLeftPx: Float,
        lineRightPx: Float,
        rtl: Boolean,
        pageWidthPx: Float,
    ): Float {
        val anchor = if (rtl) lineRightPx else lineLeftPx
        if (!anchor.isFinite()) return 0f
        val raw = if (rtl) anchor + GAP_PX else anchor - GAP_PX
        // Never off the page: a margin narrower than the gap (large type on a
        // small screen) would otherwise paint the tick at a negative x, which
        // the canvas clips — the note would vanish instead of moving inward.
        val maxLeft = if (pageWidthPx.isFinite()) pageWidthPx - WIDTH_PX else raw
        return if (maxLeft <= 0f) 0f else raw.coerceIn(0f, maxLeft)
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
