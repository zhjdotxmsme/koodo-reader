package com.koodoreader.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LayoutEngine] and [LayoutResult].
 *
 * The engine is the heart of P2: it shapes blocks into lines and fills pages.
 * These tests use deterministic measurers ([TextMeasurers.mono] and
 * [TextMeasurers.MIXED]) so every assertion is exact.
 *
 * ## Measurement conventions
 *
 * [TextMeasurers.mono] uses `lineHeightEm = 1.2`, so the natural line box is
 * `1.2 × fontSize`.  The engine then applies the `lineHeightMultiple` (1.5 in
 * these tests), giving an actual line height of `1.5 × 1.2 × 10 = 18 px`.
 * The baseline is `1.5 × 1.2 × 0.8 × 10 = 14.4 px` from the line top.
 */
class LayoutEngineTest {

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * 100×100 viewport, 10 px margins, 10 px font, 1.5 line-height multiple.
     *
     * Derived metrics (with [TextMeasurers.mono]):
     *  - contentWidth = 80 px → 8 mono chars per line
     *  - contentHeight = 80 px
     *  - line height = 1.5 × 1.2 × 10 = 18 px → 4 lines per column
     *  - baseline offset = 1.5 × 1.2 × 0.8 × 10 = 14.4 px
     */
    private fun tokens(
        fontSizePx: Float = 10f,
        lineHeightMultiple: Float = 1.5f,
        letterSpacingPx: Float = 0f,
        paraSpacingPx: Float = 0f,
        firstLineIndentPx: Float = 0f,
        columnGapPx: Float = 0f,
        textAlign: TextAlign = TextAlign.DEFAULT,
        wordBreak: WordBreak = WordBreak.NORMAL,
        overflowWrap: OverflowWrap = OverflowWrap.BREAK_WORD,
    ) = LayoutTokens(
        viewportWidthPx = 100f,
        viewportHeightPx = 100f,
        marginHorizontalPx = 10f,
        marginVerticalPx = 10f,
        fontSizePx = fontSizePx,
        lineHeightMultiple = lineHeightMultiple,
        letterSpacingPx = letterSpacingPx,
        paraSpacingPx = paraSpacingPx,
        firstLineIndentPx = firstLineIndentPx,
        columnGapPx = columnGapPx,
        textAlign = textAlign,
        wordBreak = wordBreak,
        overflowWrap = overflowWrap,
    )

    private fun doc(vararg blocks: TextBlock, spineIndex: Int = 0) = EpubDocument(
        listOf(SpineItem(index = spineIndex, href = "ch$spineIndex.xhtml", blocks = blocks.toList())),
    )

    private fun doc(spine: List<SpineItem>) = EpubDocument(spine)

    private fun line(result: LayoutResult, i: Int) = result.lines[i]

    // ── basic layout ────────────────────────────────────────────────────────

    @Test
    fun `single short block produces one line`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("hello", elementIndex = 0)),
            tokens(),
        )
        assertEquals(1, result.pageCount)
        assertEquals(1, result.lineCount)
        val l = line(result, 0)
        assertEquals("hello", l.text)
        assertEquals(0, l.start)
        assertEquals(5, l.end)
        assertEquals(10f, l.x, EPS)       // left margin
        assertEquals(10f, l.y, EPS)       // top margin
        assertEquals(50f, l.width, EPS)   // 5 chars × 10 px
        assertEquals(18f, l.height, EPS)  // 1.5 × 1.2 × 10
        assertEquals(24.4f, l.baselineY, EPS) // 10 + 14.4
        assertEquals(10f, l.fontSizePx, EPS)
        assertEquals(0, l.page)
        assertEquals(0, l.column)
    }

    @Test
    fun `empty block produces no lines`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("", elementIndex = 0)),
            tokens(),
        )
        assertEquals(0, result.lineCount)
        assertEquals(1, result.pageCount) // engine always returns at least one page
    }

    @Test
    fun `whitespace-only block produces no lines`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("   ", elementIndex = 0)),
            tokens(),
        )
        assertEquals(0, result.lineCount)
    }

    // ── line breaking ───────────────────────────────────────────────────────

    @Test
    fun `line breaks at word boundary when text exceeds column`() {
        // contentWidth = 80 → 8 chars per line.
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("aa bb cc dd", elementIndex = 0)),
            tokens(),
        )
        assertEquals(2, result.lineCount)
        assertEquals("aa bb cc", line(result, 0).text)
        assertEquals(0, line(result, 0).start)
        assertEquals(8, line(result, 0).end)
        assertEquals(80f, line(result, 0).width, EPS)
        assertEquals("dd", line(result, 1).text)
        assertEquals(9, line(result, 1).start)
        assertEquals(11, line(result, 1).end)
        assertEquals(20f, line(result, 1).width, EPS)
    }

    @Test
    fun `line break at exact column width does not overflow`() {
        // 8 chars = exactly 80 px = contentWidth → single line.
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("aaaaaaaa", elementIndex = 0)),
            tokens(),
        )
        assertEquals(1, result.lineCount)
        assertEquals("aaaaaaaa", line(result, 0).text)
        assertEquals(80f, line(result, 0).width, EPS)
    }

    @Test
    fun `overflow-wrap BREAK_WORD force-breaks unbreakable run`() {
        // 10 chars, 8 per line → 8 + 2. First line forced, second fits naturally.
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("abcdefghij", elementIndex = 0)),
            tokens(overflowWrap = OverflowWrap.BREAK_WORD),
        )
        assertEquals(2, result.lineCount)
        assertEquals("abcdefgh", line(result, 0).text)
        assertTrue(line(result, 0).forced)
        assertEquals("ij", line(result, 1).text)
        assertFalse(line(result, 1).forced) // whole remainder fits
    }

    @Test
    fun `overflow-wrap NORMAL keeps unbreakable run on one line`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("abcdefghij", elementIndex = 0)),
            tokens(overflowWrap = OverflowWrap.NORMAL),
        )
        assertEquals(1, result.lineCount)
        assertEquals("abcdefghij", line(result, 0).text)
        assertEquals(100f, line(result, 0).width, EPS) // overflows the 80px column
    }

    @Test
    fun `letter spacing reduces characters per line`() {
        // 8 chars × 10 + 7 × 2 = 94 > 80 → 7 chars: 70 + 12 = 82 > 80 → 6 chars: 60+10=70 ≤ 80.
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("abcdefghij", elementIndex = 0)),
            tokens(letterSpacingPx = 2f),
        )
        assertEquals(2, result.lineCount)
        assertEquals("abcdef", line(result, 0).text)
        assertEquals(70f, line(result, 0).width, EPS)
        assertEquals("ghij", line(result, 1).text)
        assertEquals(48f, line(result, 1).width, EPS) // 4×10 + 3×2
    }

    // ── pagination ──────────────────────────────────────────────────────────

    @Test
    fun `lines fill column then overflow to next page`() {
        // contentHeight = 80, lineH = 18 → 4 lines per column (72 ≤ 80, 90 > 80).
        // 80 chars = 10 lines of 8 → 4 + 4 + 2 = 3 pages.
        val text = "01234567".repeat(10) // 80 chars, no spaces → 10 lines of 8
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of(text, elementIndex = 0)),
            tokens(),
        )
        assertEquals(3, result.pageCount)   // 4 + 4 + 2
        assertEquals(10, result.lineCount)
        assertEquals(4, result.pages[0].lines.size)
        assertEquals(4, result.pages[1].lines.size)
        assertEquals(2, result.pages[2].lines.size)
        assertEquals(10f, result.pages[0].lines[0].y, EPS)
        assertEquals(64f, result.pages[0].lines[3].y, EPS) // 10 + 3×18
        assertEquals(10f, result.pages[1].lines[0].y, EPS)
    }

    @Test
    fun `widow control pulls line back to avoid single-line last column`() {
        // contentHeight = 80, lineH = 18 → 4 lines per column.
        // 5 lines: 4 fit, 1 left over → widows rule (default 2) pulls one back → 3 + 2.
        val fiveLines = "aaaaaaaa bbbbbbbb cccccccc dddddddd eeeeeeee"
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of(fiveLines, elementIndex = 0)),
            tokens(),
        )
        assertEquals(2, result.pageCount)
        assertEquals(3, result.pages[0].lines.size)
        assertEquals(2, result.pages[1].lines.size)
    }

    @Test
    fun `orphan control moves block to next column when fewer than minimum lines fit`() {
        // contentHeight = 80, lineH = 18 → 4 lines per column.
        // Block 1: 4 lines (one per word, since "aaaa bbbb" = 9 chars > 80 px).
        // Block 2: 3 lines. 4×18=72, adding block2 line 1 → 90 > 80 → 0 lines fit.
        // So block 2 goes entirely to the next column.
        val block1 = TextBlock.of("aaaa bbbb cccc dddd", elementIndex = 0)
        val block2 = TextBlock.of("eeee ffff gggg", elementIndex = 1)
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(block1, block2),
            tokens(),
        )
        assertEquals(2, result.pageCount)
        assertEquals(4, result.pages[0].lines.size)
        assertEquals(3, result.pages[1].lines.size)
    }

    @Test
    fun `two-column layout splits lines across columns`() {
        // contentWidth = 80, gap = 10 → columnWidth = 35 → 3 chars per line.
        // contentHeight = 80, lineH = 18 → 4 lines per column.
        // "abcdefghij" = 10 chars → 4 lines (3+3+3+1). 4 lines fit in 1 column.
        val text = "abcdefghij"
        val result = LayoutEngine(TextMeasurers.mono(), PaginatorOptions(columnsPerPage = 2)).layout(
            doc(TextBlock.of(text, elementIndex = 0)),
            tokens(columnGapPx = 10f),
        )
        assertEquals(1, result.pageCount)
        assertEquals(1, result.pages[0].columns.size) // only 1 column has lines
        assertEquals(4, result.pages[0].columns[0].lines.size)
        assertEquals(10f, result.pages[0].columns[0].x, EPS)
    }

    @Test
    fun `two-column layout with enough lines fills both columns`() {
        // contentWidth = 80, gap = 10 → columnWidth = 35 → 3 chars per line.
        // contentHeight = 80, lineH = 18 → 4 lines per column.
        // 15 chars = 5 lines (3+3+3+3+3). 4 fit in column 0, but widow control
        // (default 2) pulls one back since remaining=1 < 2 → 3 + 2.
        val text = "aaaaaaaaaaaaaaa" // 15 chars
        val result = LayoutEngine(TextMeasurers.mono(), PaginatorOptions(columnsPerPage = 2)).layout(
            doc(TextBlock.of(text, elementIndex = 0)),
            tokens(columnGapPx = 10f),
        )
        assertEquals(1, result.pageCount)
        assertEquals(2, result.pages[0].columns.size)
        assertEquals(3, result.pages[0].columns[0].lines.size)
        assertEquals(2, result.pages[0].columns[1].lines.size)
        assertEquals(10f, result.pages[0].columns[0].x, EPS)
        assertEquals(55f, result.pages[0].columns[1].x, EPS)
    }

    @Test
    fun `chapter starts on new page by default`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(
                listOf(
                    SpineItem(0, "ch1.xhtml", blocks = listOf(TextBlock.of("hello", elementIndex = 0))),
                    SpineItem(1, "ch2.xhtml", blocks = listOf(TextBlock.of("world", elementIndex = 0))),
                ),
            ),
            tokens(),
        )
        assertEquals(2, result.pageCount)
        assertEquals(1, result.pages[0].lines.size)
        assertEquals(1, result.pages[1].lines.size)
        assertEquals("hello", result.pages[0].lines[0].text)
        assertEquals("world", result.pages[1].lines[0].text)
    }

    @Test
    fun `chapter does not start new page when disabled`() {
        val result = LayoutEngine(
            TextMeasurers.mono(),
            PaginatorOptions(chapterStartsNewPage = false),
        ).layout(
            doc(
                listOf(
                    SpineItem(0, "ch1.xhtml", blocks = listOf(TextBlock.of("hello", elementIndex = 0))),
                    SpineItem(1, "ch2.xhtml", blocks = listOf(TextBlock.of("world", elementIndex = 0))),
                ),
            ),
            tokens(),
        )
        assertEquals(1, result.pageCount)
        assertEquals(2, result.lineCount)
    }

    // ── text alignment ──────────────────────────────────────────────────────

    @Test
    fun `right-aligned text hugs right column edge`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("abc", elementIndex = 0)),
            tokens(textAlign = TextAlign.RIGHT),
        )
        // contentWidth = 80, text width = 30 → x = 10 + (80 - 30) = 60.
        assertEquals(60f, line(result, 0).x, EPS)
        assertEquals(30f, line(result, 0).width, EPS)
    }

    @Test
    fun `justify does not stretch single-line block`() {
        // Single-line block = last line → no stretch (CSS behavior).
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("a b c", elementIndex = 0)),
            tokens(textAlign = TextAlign.JUSTIFY),
        )
        assertEquals(1, result.lineCount)
        val l = line(result, 0)
        assertEquals(50f, l.width, EPS)
        assertEquals(0f, l.justifyStretchPx, EPS)
        assertEquals(TextAlign.LEFT, l.align)
    }

    @Test
    fun `justify stretches non-last lines of multi-line block`() {
        // Need a multi-line block where non-last lines have word gaps.
        // "aa bb cc dd" → 2 lines: "aa bb cc" (80px, 2 gaps → no stretch needed)
        // Use text that produces a short non-last line:
        // "a b c d e f g h" → line 1: "a b c d" (70px, 3 gaps), line 2: "e f g h" (last → no stretch).
        // Wait: "a b c d" = 7 chars = 70px. contentWidth=80. 1 gap? No: a,space,b,space,c,space,d = 3 spaces.
        // Actually "a b c d" has 3 word gaps. stretch = 80 - 70 = 10.
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("a b c d e f g h", elementIndex = 0)),
            tokens(textAlign = TextAlign.JUSTIFY),
        )
        assertEquals(2, result.lineCount)
        // Line 1: "a b c d" = 70px, 3 gaps, stretch = 80-70 = 10.
        assertEquals(10f, line(result, 0).justifyStretchPx, EPS)
        assertEquals(TextAlign.JUSTIFY, line(result, 0).align)
        // Line 2 is last → no stretch.
        assertEquals(0f, line(result, 1).justifyStretchPx, EPS)
        assertEquals(TextAlign.LEFT, line(result, 1).align)
    }

    // ── indentation ─────────────────────────────────────────────────────────

    @Test
    fun `first-line indent reduces available width on first line only`() {
        // contentWidth = 80, indent = 20 → available = 60 → 6 chars first line.
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("aa bb cc dd", elementIndex = 0)),
            tokens(firstLineIndentPx = 20f),
        )
        assertEquals(2, result.lineCount)
        val l0 = line(result, 0)
        val l1 = line(result, 1)
        assertEquals("aa bb", l0.text)
        assertEquals(20f, l0.indentPx, EPS)
        assertEquals(30f, l0.x, EPS) // 10 margin + 20 indent
        assertEquals(50f, l0.width, EPS)
        assertEquals("cc dd", l1.text)
        assertEquals(0f, l1.indentPx, EPS)
        assertEquals(10f, l1.x, EPS)
        assertEquals(50f, l1.width, EPS)
    }

    // ── paragraph spacing ───────────────────────────────────────────────────

    @Test
    fun `paragraph spacing adds gap between blocks`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(
                TextBlock.of("aaaa", elementIndex = 0),
                TextBlock.of("bbbb", elementIndex = 1),
            ),
            tokens(paraSpacingPx = 10f),
        )
        assertEquals(2, result.lineCount)
        val l0 = line(result, 0)
        val l1 = line(result, 1)
        assertEquals(10f, l0.y, EPS)
        // l0 bottom = 10 + 18 = 28. gap = paraSpacing(10) + max(0,0,0) = 10. l1 y = 38.
        assertEquals(38f, l1.y, EPS)
    }

    @Test
    fun `margin collapsing takes max of adjacent space values`() {
        val style1 = ParagraphStyle(spaceAfterPx = 20f)
        val style2 = ParagraphStyle(spaceBeforePx = 30f)
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(
                TextBlock.of("aaaa", elementIndex = 0, style = style1),
                TextBlock.of("bbbb", elementIndex = 1, style = style2),
            ),
            tokens(paraSpacingPx = 10f),
        )
        val l0 = line(result, 0)
        val l1 = line(result, 1)
        // After block1: gap = paraSpacing(10) + max(block1.spaceAfter=20, block1.spaceBefore=0) = 30.
        // l1 y = 10 + 18 + 30 = 58.
        assertEquals(10f, l0.y, EPS)
        assertEquals(58f, l1.y, EPS)
    }

    // ── font size scaling ───────────────────────────────────────────────────

    @Test
    fun `block fontSizeScale affects line metrics`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("hello", elementIndex = 0, style = ParagraphStyle(fontSizeScale = 2f))),
            tokens(),
        )
        val l = line(result, 0)
        assertEquals(20f, l.fontSizePx, EPS)
        assertEquals(36f, l.height, EPS)   // 1.5 × 1.2 × 20
        assertEquals(28.8f, l.baselineY - l.y, EPS) // 1.5 × 1.2 × 0.8 × 20
        // "hello" at 20px = 100 px > 80 px column → BREAK_WORD → 4 chars (80px) + 1 char.
        assertEquals(2, result.lineCount)
        assertEquals("hell", line(result, 0).text)
        assertEquals(80f, line(result, 0).width, EPS)
        assertEquals("o", line(result, 1).text)
        assertEquals(20f, line(result, 1).width, EPS)
    }

    // ── CJK text ────────────────────────────────────────────────────────────

    @Test
    fun `CJK text breaks between ideographs with custom width table`() {
        // Use a width table where ALL CJK chars are 1em.
        val cjkMeasurer = TextMeasurers.widthTable(
            '一'.code to 1f,
            '二'.code to 1f,
            '三'.code to 1f,
            '四'.code to 1f,
            '五'.code to 1f,
            '六'.code to 1f,
            '七'.code to 1f,
            '八'.code to 1f,
            '九'.code to 1f,
            '十'.code to 1f,
        )
        // contentWidth = 80. CJK char = 10 px. 8 chars per line.
        val cjkText = "一二三四五六七八九十" // 10 chars → 8 + 2.
        val result = LayoutEngine(cjkMeasurer).layout(
            doc(TextBlock.of(cjkText, elementIndex = 0)),
            tokens(),
        )
        assertEquals(2, result.lineCount)
        assertEquals("一二三四五六七八", line(result, 0).text)
        assertEquals("九十", line(result, 1).text)
    }

    @Test
    fun `CJK KEEP_ALL does not break between ideographs`() {
        val cjkMeasurer = TextMeasurers.widthTable(
            '一'.code to 1f,
            '二'.code to 1f,
            '三'.code to 1f,
            '四'.code to 1f,
            '五'.code to 1f,
            '六'.code to 1f,
            '七'.code to 1f,
            '八'.code to 1f,
            '九'.code to 1f,
            '十'.code to 1f,
        )
        val cjkText = "一二三四五六七八九十"
        val result = LayoutEngine(cjkMeasurer).layout(
            doc(TextBlock.of(cjkText, elementIndex = 0)),
            tokens(wordBreak = WordBreak.KEEP_ALL, overflowWrap = OverflowWrap.BREAK_WORD),
        )
        // No legal breaks → force-break at 8 chars.
        assertEquals(2, result.lineCount)
        assertEquals("一二三四五六七八", line(result, 0).text)
        assertTrue(line(result, 0).forced)
    }

    // ── hit-testing ─────────────────────────────────────────────────────────

    @Test
    fun `positionAt finds character at tap point`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("hello", elementIndex = 0)),
            tokens(),
        )
        // Line at x=10, y=10, width=50, height=18.
        // Tap at x=15 (5 px into text) → char offset 0 (prefixWidth(1)=10 > 5).
        val pos0 = result.positionAt(15f, 12f)
        assertNotNull(pos0)
        assertEquals(0, pos0!!.charOffset)

        // Tap at x=35 (25 px into text) → prefixWidth(2)=20 ≤ 25 < prefixWidth(3)=30 → offset 2.
        val pos2 = result.positionAt(35f, 12f)
        assertNotNull(pos2)
        assertEquals(2, pos2!!.charOffset)
    }

    @Test
    fun `positionAt returns null outside line bounds`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("hello", elementIndex = 0)),
            tokens(),
        )
        assertNull(result.positionAt(5f, 12f))   // left of line
        assertNull(result.positionAt(70f, 12f))  // right of line
        assertNull(result.positionAt(15f, 50f))  // below line
    }

    // ── lineAt / pageOf ─────────────────────────────────────────────────────

    @Test
    fun `lineAt finds line containing position`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("aa bb cc dd", elementIndex = 0)),
            tokens(),
        )
        // Lines: "aa bb cc" [0,8), "dd" [9,11).
        val l0 = result.lineAt(LayoutPosition(0, 0, 0))
        assertNotNull(l0)
        assertEquals("aa bb cc", l0!!.text)

        val l1 = result.lineAt(LayoutPosition(0, 0, 9))
        assertNotNull(l1)
        assertEquals("dd", l1!!.text)
    }

    @Test
    fun `lineAt boundary offset belongs to earlier line`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("aa bb cc dd", elementIndex = 0)),
            tokens(),
        )
        // charOffset 8 is the boundary: belongs to the *earlier* line per the
        // documented semantics (position.charOffset <= it.end).
        val l = result.lineAt(LayoutPosition(0, 0, 8))
        assertNotNull(l)
        assertEquals("aa bb cc", l!!.text)
    }

    @Test
    fun `lineAt returns null for unknown position`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("hello", elementIndex = 0)),
            tokens(),
        )
        assertNull(result.lineAt(LayoutPosition(99, 0, 0)))
        assertNull(result.lineAt(LayoutPosition(0, 99, 0)))
        assertNull(result.lineAt(LayoutPosition(0, 0, 99)))
    }

    @Test
    fun `pageOf returns page index for position`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("aa bb cc dd", elementIndex = 0)),
            tokens(),
        )
        assertEquals(0, result.pageOf(LayoutPosition(0, 0, 0)))
        assertEquals(0, result.pageOf(LayoutPosition(0, 0, 9)))
    }

    // ── progress ────────────────────────────────────────────────────────────

    @Test
    fun `progress at chapter start is zero`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(
                listOf(
                    SpineItem(0, "ch1.xhtml", blocks = listOf(TextBlock.of("hello", elementIndex = 0))),
                    SpineItem(1, "ch2.xhtml", blocks = listOf(TextBlock.of("world", elementIndex = 0))),
                ),
            ),
            tokens(),
        )
        val p = result.progress(LayoutPosition(0, 0, 0))
        assertEquals(0f, p.chapterPercent, EPS)
        assertEquals(0f, p.documentPercent, EPS)
    }

    @Test
    fun `progress at second chapter start is fifty percent`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(
                listOf(
                    SpineItem(0, "ch1.xhtml", blocks = listOf(TextBlock.of("hello", elementIndex = 0))),
                    SpineItem(1, "ch2.xhtml", blocks = listOf(TextBlock.of("world", elementIndex = 0))),
                ),
            ),
            tokens(),
        )
        val p = result.progress(LayoutPosition(1, 0, 0))
        assertEquals(0f, p.chapterPercent, EPS)
        assertEquals(0.5f, p.documentPercent, EPS)
    }

    @Test
    fun `progress mid-chapter`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("hello", elementIndex = 0)),
            tokens(),
        )
        val p = result.progress(LayoutPosition(0, 0, 2))
        assertEquals(0.4f, p.chapterPercent, EPS)
        assertEquals(0.4f, p.documentPercent, EPS)
    }

    // ── cfiSequence ─────────────────────────────────────────────────────────

    @Test
    fun `cfiSequence is deterministic and non-empty`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("hello", elementIndex = 0)),
            tokens(),
        )
        val seq = result.cfiSequence()
        assertTrue(seq.isNotEmpty())
        assertTrue(seq.startsWith("p1:"))
        assertTrue(seq.contains("epubcfi("))
    }

    @Test
    fun `cfiSequence contains one CFI per line`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(TextBlock.of("aa bb cc dd", elementIndex = 0)),
            tokens(),
        )
        val seq = result.cfiSequence()
        val parts = seq.substringAfter("p1:").split("|")
        assertEquals(2, parts.size)
    }

    // ── LayoutResult metadata ───────────────────────────────────────────────

    @Test
    fun `totalChars sums chapter char counts`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(
                listOf(
                    SpineItem(0, "ch1.xhtml", blocks = listOf(TextBlock.of("hello", elementIndex = 0))),
                    SpineItem(1, "ch2.xhtml", blocks = listOf(TextBlock.of("world", elementIndex = 0))),
                ),
            ),
            tokens(),
        )
        assertEquals(10, result.totalChars)
    }

    @Test
    fun `blockIndexOf resolves position to flat block index`() {
        val result = LayoutEngine(TextMeasurers.mono()).layout(
            doc(
                TextBlock.of("aaaa", elementIndex = 0),
                TextBlock.of("bbbb", elementIndex = 1),
            ),
            tokens(),
        )
        assertEquals(0, result.blockIndexOf(LayoutPosition(0, 0, 0)))
        assertEquals(1, result.blockIndexOf(LayoutPosition(0, 1, 0)))
    }

    companion object {
        private const val EPS = 0.01f
    }
}
