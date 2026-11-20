package com.koodoreader.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the **CJK column quantisation** end to end.
 *
 * The problem it solves: Chinese glyphs sit on a fixed em grid, so a column
 * whose width is not a whole multiple of the character run leaves a few
 * pixels of slack on every line. The justification engine then spreads that
 * slack into the gaps between the glyphs, and because the first line carries
 * a two-character indent while the others do not, the resulting letter
 * spacing differs from line to line — the "假字距" that makes a Chinese page
 * look typeset by an amateur.
 *
 * The fix has two halves, and both are asserted here:
 *  1. the column is fitted to a whole number of character runs;
 *  2. the first-line indent is fitted to the same grid.
 */
class CjkColumnQuantisationTest {

    // 100px viewport, 10px margins → an 80px column. A 10px font with 1px of
    // tracking gives an 11px cell, and a run of N characters is
    // N×10 + (N-1)×1 wide — so seven characters are 76px and the column is
    // quantised to exactly that.
    private fun tokens(
        quantize: Boolean = true,
        firstLineIndentPx: Float = 0f,
        letterSpacingPx: Float = 1f,
        fontSizePx: Float = 10f,
    ) = LayoutTokens(
        viewportWidthPx = 100f,
        viewportHeightPx = 100f,
        marginHorizontalPx = 10f,
        marginVerticalPx = 10f,
        fontSizePx = fontSizePx,
        lineHeightMultiple = 1.5f,
        letterSpacingPx = letterSpacingPx,
        firstLineIndentPx = firstLineIndentPx,
        textAlign = TextAlign.JUSTIFY,
        textJustify = TextJustify.INTER_IDEOGRAPH,
        quantizeColumnToCells = quantize,
    )

    private fun doc(vararg paragraphs: String): EpubDocument =
        EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch0.xhtml",
                    blocks = paragraphs.mapIndexed { i, t -> TextBlock.of(t, i) },
                ),
            ),
        )

    @Test
    fun `quantised column is a whole number of character runs`() {
        val t = tokens()
        val column = t.columnWidthPx(1)
        // Seven characters: 7×10 + 6×1 = 76.
        assertEquals(76f, column, 0.001f)
        assertEquals(7f * t.fontSizePx + 6f * t.letterSpacingPx, column, 0.001f)
    }

    @Test
    fun `unquantised column keeps the raw width`() {
        val t = tokens(quantize = false)
        assertEquals(80f, t.columnWidthPx(1), 0.001f)
    }

    @Test
    fun `the leftover is split evenly between the two margins`() {
        val t = tokens()
        // 80px available, 76px column → 2px on each side.
        assertEquals(2f, t.columnInsetPx(1), 0.001f)
        assertEquals(12f, t.columnLeftPx(0, 1), 0.001f)
    }

    @Test
    fun `a quantised line needs no justification stretch`() {
        // The point of the whole exercise: a line that fills the column
        // exactly has no slack, so the justification engine does not spread
        // anything into the glyph gaps and the letter spacing stays the same
        // on every line.
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc("一二三四五六七八九十甲乙丙丁戊己庚辛壬癸子丑寅卯", "第二段"),
            tokens(),
        )
        assertTrue(layout.lines.size > 2, "expected the paragraph to wrap")
        assertTrue(
            layout.lines.all { it.justifyStretchPx == 0f },
            "expected no stretch on any line, got ${layout.lines.map { it.justifyStretchPx }}",
        )
    }

    @Test
    fun `every line of a quantised paragraph has the same width`() {
        // The user-visible consequence: with the column quantised, the lines
        // are all the same length, so the right edge of the column is straight
        // and the tracking does not change from line to line.
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc("一二三四五六七八九十甲乙丙丁戊己庚辛壬癸子丑寅卯", "第二段"),
            tokens(),
        )
        // The LAST line of a block is naturally short — CSS never stretches
        // it — so only the lines that fill the column are compared. A line
        // that fills the column is exactly as wide as the column.
        val column = tokens().columnWidthPx(1)
        val fullLines = layout.lines.filter { it.blockIndex == 0 && it.width > column - 0.01f }
        assertTrue(fullLines.size >= 2, "expected at least two full lines")
        assertTrue(
            fullLines.all { kotlin.math.abs(it.width - column) < 0.01f },
            "expected every full line to be exactly the column width, " +
                "got ${fullLines.map { it.width }} (column $column)",
        )
    }

    @Test
    fun `the first line indent is fitted to the character grid`() {
        // A raw indent of 22px is two character slots; the engine fits it to
        // the grid (2 slots = 2×10 + 1×1 = 21px) so that the first line holds
        // exactly two fewer characters than the others.
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc("一二三四五六七八九十", "第二段"),
            tokens(firstLineIndentPx = 22f),
        )
        assertEquals(21f, layout.lines.first().indentPx, 0.001f)
    }

    @Test
    fun `without quantisation the indent is left exactly as asked`() {
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc("一二三四五六七八九十", "第二段"),
            tokens(quantize = false, firstLineIndentPx = 21f),
        )
        assertEquals(21f, layout.lines.first().indentPx, 0.001f)
    }

    @Test
    fun `the first line holds fewer characters than the rest`() {
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc("一二三四五六七八九十甲乙丙丁", "第二段"),
            tokens(firstLineIndentPx = 22f),
        )
        val first = layout.lines.first()
        val second = layout.lines[1]
        assertTrue(
            first.text.length < second.text.length,
            "expected the indented first line to hold fewer characters " +
                "(${first.text.length} vs ${second.text.length})",
        )
    }
}
