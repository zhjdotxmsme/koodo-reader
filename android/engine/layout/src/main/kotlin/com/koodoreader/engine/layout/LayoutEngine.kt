package com.koodoreader.engine.layout

/**
 * Pagination options.
 *
 * @property columnsPerPage CSS `column-count` for a page: 1 = single column
 *   (the phone default), 2 = a double-page spread (the desktop reader's wide
 *   mode, which sets `column-count: 2`).
 * @property widowsOrphans CSS `orphans`/`widows`: the minimum number of lines
 *   of a block that must stay on the same page. 0 disables the rule.
 * @property chapterStartsNewPage whether a spine item starts on a fresh page
 *   (the desktop EPUB reader's default).
 */
data class PaginatorOptions(
    val columnsPerPage: Int = 1,
    val widowsOrphans: Int = 2,
    val chapterStartsNewPage: Boolean = true,
) {
    init {
        require(columnsPerPage in 1..8) { "columnsPerPage must be 1..8 (was $columnsPerPage)" }
        require(widowsOrphans in 0..4) { "widowsOrphans must be 0..4 (was $widowsOrphans)" }
    }
}

/** One shaped (not yet placed) line. */
private data class ShapedLine(
    val text: String,
    val start: Int,
    val end: Int,
    val fontSizePx: Float,
    val indentPx: Float,
    val width: Float,
    val heightPx: Float,
    val baselinePx: Float,
    val wordGaps: Int,
    val forced: Boolean,
)

/**
 * The native pagination engine — P2's "自绘分页".
 *
 * ## What it replaces
 *
 * The desktop engine paginates by letting the browser lay an XHTML document
 * into CSS multi-column boxes (`column-width` / `column-count` in kookit
 * `layoutUtil.ts` + `GeneralRender`) and then reading the resulting geometry
 * off the DOM. None of that exists in Kotlin, so this class re-implements the
 * same algorithm against the [BlockModel]:
 *
 *  1. **shape** — break each block into lines that fit the column width, with
 *     CSS break opportunities ([breakEnds]) and the same `line-height` /
 *     `letter-spacing` / `text-indent` maths the browser applies;
 *  2. **paginate** — fill each page's columns top-down, applying the CSS
 *     `orphans`/`widows` rule and between-block margin collapsing.
 *
 * The output [LayoutResult] lines are directly drawable on a Canvas.
 *
 * ## Deliberate deviations from a browser
 *
 *  - A paged renderer cannot let a line overflow its column (the browser would
 *    draw it outside the box). Under [OverflowWrap.BREAK_WORD] an unbreakable
 *    run is force-broken; [LayoutLine.forced] records it so the renderer can
 *    hint it if it wants.
 *  - `orphans` / `widows` are enforced between two consecutive lines of the
 *    same block, which is the observable CSS behaviour for paginated text.
 *  - A line taller than the whole content box is placed anyway in an empty
 *    column, so the engine always advances and never loops forever.
 *  - `text-align: right` ignores `text-indent` (browsers apply it, the visual
 *    difference is not worth the complexity in a mobile reader).
 *
 * ## CFI
 *
 * This engine never invents addresses: every line carries a [LayoutPosition]
 * that [CfiAddressing] turns into a CFI via `:engine:cfi` (ADR-002).
 */
class LayoutEngine(
    private val measurer: TextMeasurer,
    private val options: PaginatorOptions = PaginatorOptions(),
) {

    /** Paginate [document] into a [LayoutResult]. */
    fun layout(document: EpubDocument, tokens: LayoutTokens): LayoutResult {
        val flat = document.flatBlocks()
        val chapters = buildChapters(document)
        val blocks = flat.map { f ->
            LayoutBlock(f.spine.index, f.block.elementIndex, f.blockIndex, f.block.text.length, f.globalStart)
        }

        val columnWidth = tokens.columnWidthPx(options.columnsPerPage)
        val columnHeight = tokens.contentHeightPx
        val topMargin = tokens.marginVerticalPx
        val bottom = topMargin + columnHeight
        val EPS = 0.0001f

        val pages = ArrayList<LayoutPage>()
        val flatLines = ArrayList<LayoutLine>()
        var pageIdx = 0
        var colIdx = 0
        var colLines = ArrayList<LayoutLine>()
        var pageColumns = ArrayList<LayoutColumn>()
        var y = topMargin
        var gapBeforeNext = 0f
        var gapApplied = true
        var prevStyle: ParagraphStyle? = null
        var currentSpine = Int.MIN_VALUE
        var placedInColumn = 0

        fun closeColumn() {
            if (colLines.isNotEmpty()) {
                pageColumns.add(
                    LayoutColumn(colIdx, tokens.columnLeftPx(colIdx, options.columnsPerPage), columnWidth, columnHeight, colLines.toList()),
                )
                colLines = ArrayList()
            }
            colIdx++
            if (colIdx >= options.columnsPerPage) {
                pages.add(LayoutPage(pageIdx, pageColumns.toList()))
                pageColumns = ArrayList()
                pageIdx++
                colIdx = 0
            }
            y = topMargin
            gapApplied = true   // a margin at a column boundary is dropped
            placedInColumn = 0
        }

        fun place(flatBlock: FlatBlock, spineIndex: Int, shaped: List<ShapedLine>, from: Int, to: Int, align: TextAlign) {
            val columnLeft = tokens.columnLeftPx(colIdx, options.columnsPerPage)
            val placed = ArrayList<LayoutLine>(to - from)
            var yy = y
            for (k in from until to) {
                val sl = shaped[k]
                // CSS does not stretch the last line of a block.
                val lastOfBlock = (k == shaped.lastIndex) && (to == shaped.size)
                val lineAlign = if (align == TextAlign.JUSTIFY && lastOfBlock) TextAlign.LEFT else align
                // CSS stretches the gaps between words; a single word has no gap
                // to stretch, so it stays at its natural width.
                val stretch = if (align == TextAlign.JUSTIFY && !lastOfBlock && sl.wordGaps > 0) {
                    (columnWidth - sl.indentPx - sl.width).coerceAtLeast(0f)
                } else 0f
                placed.add(
                    LayoutLine(
                        page = pageIdx,
                        column = colIdx,
                        position = LayoutPosition(spineIndex, flatBlock.block.elementIndex, sl.start),
                        blockIndex = flatBlock.blockIndex,
                        text = sl.text,
                        start = sl.start,
                        end = sl.end,
                        fontSizePx = sl.fontSizePx,
                        indentPx = sl.indentPx,
                        x = inkX(lineAlign, columnLeft, columnWidth, sl.indentPx, sl.width),
                        y = yy,
                        width = sl.width,
                        height = sl.heightPx,
                        baselineY = yy + sl.baselinePx,
                        align = lineAlign,
                        justifyStretchPx = stretch,
                        wordGaps = sl.wordGaps,
                        forced = sl.forced,
                    ),
                )
                yy += sl.heightPx
            }
            flatLines.addAll(placed)
            colLines.addAll(placed)
            y = yy
        }

        for (flatBlock in flat) {
            if (flatBlock.spine.index != currentSpine) {
                if (currentSpine != Int.MIN_VALUE && options.chapterStartsNewPage) closeColumn()
                currentSpine = flatBlock.spine.index
            }

            val block = flatBlock.block
            if (block.isEmpty) {
                prevStyle = block.style
                continue
            }

            val shaped = shape(block, tokens, columnWidth)
            if (shaped.isEmpty()) {
                prevStyle = block.style
                continue
            }

            val align = block.style.textAlign ?: tokens.textAlign
            var i = 0
            while (i < shaped.size) {
                val line = shaped[i]
                if (!gapApplied) {
                    y += gapBeforeNext
                    gapApplied = true
                }

                if (y + line.heightPx > bottom + EPS) {
                    if (colLines.isEmpty()) {
                        // Taller than the content box: place it anyway so the
                        // engine always advances.
                        place(flatBlock, flatBlock.spine.index, shaped, i, i + 1, align)
                        placedInColumn++
                        i++
                        continue
                    }
                    if (placedInColumn < options.widowsOrphans && shaped.size > 1) {
                        // Orphans: fewer than `widowsOrphans` lines of the block
                        // would be left behind in this column, so the whole
                        // block moves on.
                        closeColumn()
                        i = 0
                        continue
                    }
                    closeColumn()
                    continue
                }

                var take = 0
                var probe = y
                while (i + take < shaped.size && probe + shaped[i + take].heightPx <= bottom + EPS) {
                    probe += shaped[i + take].heightPx
                    take++
                }

                val remaining = shaped.size - i
                if (take < remaining) {
                    // The block splits at the bottom of this column.
                    if (take < options.widowsOrphans) {
                        closeColumn()
                        i = 0
                        continue
                    }
                    // Widows: pull one line back so the next column does not
                    // start with a single stranded line.
                    if (take > 1 && remaining - take < options.widowsOrphans) take--
                }

                place(flatBlock, flatBlock.spine.index, shaped, i, i + take, align)
                placedInColumn += take
                i += take

                if (i == shaped.size) {
                    val spaceBefore = block.style.spaceBeforePx
                    val spaceAfter = prevStyle?.spaceAfterPx ?: 0f
                    gapBeforeNext = tokens.paraSpacingPx + maxOf(block.style.spaceAfterPx, spaceBefore, spaceAfter)
                    gapApplied = false
                    prevStyle = block.style
                } else {
                    closeColumn()
                }
            }
        }

        closeColumn()
        // Flush any remaining columns that did not fill the last page.
        if (pageColumns.isNotEmpty()) {
            pages.add(LayoutPage(pageIdx, pageColumns.toList()))
            pageColumns = ArrayList()
            pageIdx++
            colIdx = 0
        }
        if (pages.isEmpty()) pages.add(LayoutPage(0, emptyList()))

        return LayoutResult(tokens, pages, flatLines, chapters, blocks, measurer)
    }

    /** Ink x for a line: right-aligned lines hug the right column edge. */
    private fun inkX(align: TextAlign, columnLeft: Float, columnWidth: Float, indent: Float, width: Float): Float =
        when (align) {
            TextAlign.RIGHT -> columnLeft + (columnWidth - width).coerceAtLeast(0f)
            else -> columnLeft + indent
        }

    /** Where to end a line, and whether the break had to be forced. */
    private data class BreakDecision(val end: Int, val forced: Boolean)

    /**
     * Decide where a line ends, mirroring the browser's break order:
     *
     *  0. if the whole remainder fits, it is one line — no break needed;
     *  1. otherwise take the latest **legal** break end ([breakEnds]) that fits;
     *  2. if no legal break fits, [OverflowWrap.BREAK_WORD] force-breaks the run
     *     (binary search over the monotonic prefix width) and
     *     [OverflowWrap.NORMAL] keeps the run on the line and lets it overflow.
     */
    private fun decideBreak(
        text: String,
        start: Int,
        available: Float,
        sliceWidth: (Int) -> Float,
        breakSet: IntArray,
        overflowWrap: OverflowWrap,
    ): BreakDecision {
        // 0) nothing left to break.
        if (sliceWidth(text.length) <= available) return BreakDecision(text.length, forced = false)

        // 1) the latest legal break end that fits.
        //
        // A break right after a whitespace is judged by the *trimmed* width:
        // the browser renders the break instead of the space, so the space
        // must not count against the available width.  Without this, a line
        // like "aa bb cc dd" in an 80 px column would stop at "aa bb" (raw
        // width of "aa bb cc " = 90 px > 80) even though the rendered text
        // "aa bb cc" is exactly 80 px and fits perfectly.
        var legal = 0
        for (e in breakSet) {
            if (e <= start) continue
            val renderedEnd = trimTrailingWhitespace(text, start, e)
            val w = if (renderedEnd == e) sliceWidth(e) else sliceWidth(renderedEnd)
            if (w > available) break
            legal = e
        }
        if (legal > 0) return BreakDecision(legal, forced = false)

        // 2) no legal break fits.
        return if (overflowWrap == OverflowWrap.BREAK_WORD) {
            var lo = start + 1
            var hi = text.length
            var fit = start + 1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (sliceWidth(mid) <= available) {
                    fit = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            BreakDecision(fit, forced = true)
        } else {
            // 3) overflow-wrap: normal — keep the run on this line and let it
            //    overflow the column.
            BreakDecision(text.length, forced = true)
        }
    }

    /**
     * Shape one block into lines that fit [columnWidthPx] (browser greedy fill).
     *
     * A break *after* a whitespace character consumes that whitespace: the
     * browser renders the break instead of the space, so the next line starts
     * after it and never with a leading space.
     */
    private fun shape(block: TextBlock, tokens: LayoutTokens, columnWidthPx: Float): List<ShapedLine> {
        val text = block.text
        val style = block.style
        val fontSize = tokens.fontSizePx * style.fontSizeScale
        val lineHeightMul = style.lineHeightMultiple ?: tokens.lineHeightMultiple
        val lineH = lineHeightMul * measurer.lineHeightPx(fontSize)
        val baseline = lineHeightMul * measurer.ascentPx(fontSize)
        val firstLineIndent = tokens.firstLineIndentPx + style.textIndentEm * fontSize
        val breakSet = breakEnds(text, tokens.wordBreak)
        val ls = tokens.letterSpacingPx

        val out = ArrayList<ShapedLine>()
        var start = 0
        var lineNo = 0
        while (start < text.length) {
            val indent = if (lineNo == 0) firstLineIndent else 0f
            val available = (columnWidthPx - indent).coerceAtLeast(1f)
            val base = measurer.prefixWidth(text, start, fontSize, ls)
            val sliceWidth: (Int) -> Float = { e -> measurer.prefixWidth(text, e, fontSize, ls) - base }

            val decision = decideBreak(text, start, available, sliceWidth, breakSet, tokens.overflowWrap)
            val end = decision.end
            val forced = decision.forced

            // A line never ends with whitespace: the trailing space becomes the
            // line break itself.
            val trimmedEnd = trimTrailingWhitespace(text, start, end)
            if (trimmedEnd == start) {
                // The whole range was whitespace (a run of spaces at a break).
                start = end
            } else {
                val width = sliceWidth(trimmedEnd)
                out.add(
                    ShapedLine(
                        text = text.substring(start, trimmedEnd),
                        start = start,
                        end = trimmedEnd,
                        fontSizePx = fontSize,
                        indentPx = indent,
                        width = width,
                        heightPx = lineH,
                        baselinePx = baseline,
                        wordGaps = text.substring(start, trimmedEnd).count { isCollapsibleWhitespace(it) },
                        forced = forced,
                    ),
                )
                start = trimmedEnd
                lineNo++
            }

            // Whitespace at a line start is dropped, too.
            while (start < text.length && isCollapsibleWhitespace(text[start])) start++
        }
        return out
    }

    /** The chapters of the result, in reading order. */
    private fun buildChapters(document: EpubDocument): List<LayoutChapter> {
        val out = ArrayList<LayoutChapter>(document.spine.size)
        var blockStart = 0
        for (spine in document.spine) {
            out.add(LayoutChapter(spine.index, spine.charCount, blockStart, blockStart + spine.blocks.size))
            blockStart += spine.blocks.size
        }
        return out
    }
}
