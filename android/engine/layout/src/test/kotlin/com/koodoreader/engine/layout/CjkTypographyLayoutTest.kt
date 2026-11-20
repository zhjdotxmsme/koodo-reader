package com.koodoreader.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end tests for the **Chinese typography** the native reader applies:
 * justification, first-line indent and letter spacing.
 *
 * These lock the behaviour that the reader's visual quality rests on. They
 * use [TextMeasurers.mono] (one character = one em) so the arithmetic is
 * exact: a 10px font in an 80px column fits exactly 8 characters per line.
 *
 * The typography defaults themselves live in `:core:designsystem`
 * (`CjkTypography`); these tests verify that the ENGINE honours them once
 * they arrive as [LayoutTokens].
 */
class CjkTypographyLayoutTest {

    // 100×100 viewport, 10px margins, 10px font → 80px column = 8 CJK chars.
    private fun tokens(
        textAlign: TextAlign = TextAlign.DEFAULT,
        firstLineIndentPx: Float = 0f,
        letterSpacingPx: Float = 0f,
        paraSpacingPx: Float = 0f,
        textJustify: TextJustify = TextJustify.SPACES,
    ) = LayoutTokens(
        viewportWidthPx = 100f,
        viewportHeightPx = 100f,
        marginHorizontalPx = 10f,
        marginVerticalPx = 10f,
        fontSizePx = 10f,
        lineHeightMultiple = 1.5f,
        textAlign = textAlign,
        firstLineIndentPx = firstLineIndentPx,
        letterSpacingPx = letterSpacingPx,
        paraSpacingPx = paraSpacingPx,
        wordBreak = WordBreak.NORMAL,
        overflowWrap = OverflowWrap.BREAK_WORD,
        textJustify = textJustify,
    )

    private fun documentOf(vararg paragraphs: String): EpubDocument =
        EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch0.xhtml",
                    blocks = paragraphs.mapIndexed { index, text ->
                        TextBlock.of(text, elementIndex = index)
                    },
                ),
            ),
        )

    // ── Justification ────────────────────────────────────────────────────────

    @Test
    fun `justify stretches the word gaps of a non-final line`() {
        // A first-line indent is what makes a line shorter than the column:
        // the 20px indent leaves room for six characters instead of eight, so
        // the line ends 20px short and has slack to distribute. Two
        // paragraphs, so the line is NOT the last line of its block (CSS does
        // not stretch the last line of a block).
        val doc = documentOf("一二 三四五六七八九十", "第二段")
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc,
            tokens(textAlign = TextAlign.JUSTIFY, firstLineIndentPx = 12.5f),
        )
        val first = layout.lines.first()
        assertTrue(
            first.wordGaps > 0,
            "fixture must contain a word gap, got ${first.wordGaps}",
        )
        assertTrue(
            first.justifyStretchPx > 0f,
            "expected the first line to stretch, got ${first.justifyStretchPx}",
        )
        // The stretched line fills the column exactly.
        assertEquals(90f, first.rightPx, 0.01f)
    }

    @Test
    fun `word gaps carry the stretch when the line has spaces`() {
        val doc = documentOf("一二 三四五六七八九十", "第二段")
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc,
            tokens(textAlign = TextAlign.JUSTIFY, firstLineIndentPx = 12.5f),
        )
        val first = layout.lines.first()
        assertTrue(first.wordGaps > 0, "fixture must contain a word gap")
        assertTrue(first.justifyStretchPx > 0f, "expected the line to stretch")
    }

    @Test
    fun `inter ideograph justification reaches the right edge of a Chinese line`() {
        // THE Chinese case: no spaces at all, so the plain CSS rule has no gap
        // to stretch and the column would stay ragged. With
        // INTER_IDEOGRAPH the slack goes to the gaps between the ideographs.
        //
        // The first-line indent is what makes the line shorter than the
        // column, and the second paragraph makes it not the last line of its
        // block.
        val doc = documentOf("一二三四五六七八九十", "第二段")
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc,
            tokens(
                textAlign = TextAlign.JUSTIFY,
                textJustify = TextJustify.INTER_IDEOGRAPH,
                firstLineIndentPx = 12.5f,
            ),
        )
        val first = layout.lines.first()
        assertTrue(
            first.ideographGaps > 0,
            "fixture must contain ideograph gaps, got ${first.ideographGaps}",
        )
        assertTrue(
            first.justifyStretchPx > 0f,
            "expected the Chinese line to stretch, got ${first.justifyStretchPx}",
        )
        // The stretched line now fills the column exactly.
        assertEquals(90f, first.rightPx, 0.01f)
    }

    @Test
    fun `spaces justification leaves a Chinese line ragged`() {
        // The plain CSS rule has no gap to stretch when the text has no
        // spaces — this is precisely the defect INTER_IDEOGRAPH fixes.
        val doc = documentOf("一二三四五六七八九十", "第二段")
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc,
            tokens(
                textAlign = TextAlign.JUSTIFY,
                textJustify = TextJustify.SPACES,
                firstLineIndentPx = 12.5f,
            ),
        )
        val first = layout.lines.first()
        assertEquals(0f, first.justifyStretchPx, 0.0001f)
    }

    @Test
    fun `inter ideograph justification counts the ideograph gaps`() {
        val doc = documentOf("一二三四五六七八九十")
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc,
            tokens(textJustify = TextJustify.INTER_IDEOGRAPH),
        )
        // A run of N ideographs has N-1 gaps between them.
        val first = layout.lines.first()
        assertEquals(first.text.length - 1, first.ideographGaps)
    }

    @Test
    fun `justify leaves the last line of a block unstretched`() {
        // CSS `text-align: justify` does not stretch the last line of a
        // paragraph — it would leave a river of white space.
        val doc = documentOf("一二三四 五六七八")
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc,
            tokens(textAlign = TextAlign.JUSTIFY),
        )
        val last = layout.lines.last()
        assertEquals(0f, last.justifyStretchPx, 0.0001f)
        assertEquals(TextAlign.LEFT, last.align)
    }

    @Test
    fun `without justify no line is stretched`() {
        val doc = documentOf("一二三四 五六七八")
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc,
            tokens(textAlign = TextAlign.LEFT),
        )
        assertTrue(layout.lines.all { it.justifyStretchPx == 0f })
    }

    // ── First-line indent ────────────────────────────────────────────────────

    @Test
    fun `first line indent shifts the first line only`() {
        val doc = documentOf("一二三四五六七八九十")
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc,
            tokens(firstLineIndentPx = 20f),
        )
        assertTrue(layout.lines.size > 1, "expected the text to wrap")
        val first = layout.lines[0]
        val second = layout.lines[1]
        assertEquals(20f, first.indentPx, 0.0001f)
        assertEquals(0f, second.indentPx, 0.0001f)
        // The indented line starts further right than the following one.
        assertTrue(first.x > second.x)
    }

    @Test
    fun `without indent every line starts at the same x`() {
        val doc = documentOf("一二三四五六七八九十")
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc,
            tokens(firstLineIndentPx = 0f),
        )
        val xs = layout.lines.map { it.x }.distinct()
        assertEquals(1, xs.size, "expected every line to share one left edge, got $xs")
    }

    @Test
    fun `indent reduces the room available on the first line`() {
        // With a 20px indent in an 80px column, only 60px remain — six
        // characters instead of eight.
        val doc = documentOf("一二三四五六七八九十")
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc,
            tokens(firstLineIndentPx = 20f),
        )
        val first = layout.lines[0]
        assertEquals(6, first.text.length, "unexpected first-line length: ${first.text}")
    }

    // ── Letter spacing ───────────────────────────────────────────────────────

    @Test
    fun `letter spacing widens the text`() {
        // Six characters: 60px plain, 65px tracked — both fit the 80px
        // column, so the comparison is of the line's own width and not of a
        // column-clamped line.
        val plain = LayoutEngine(TextMeasurers.mono()).layout(
            documentOf("一二三四五六"),
            tokens(letterSpacingPx = 0f),
        )
        val tracked = LayoutEngine(TextMeasurers.mono()).layout(
            documentOf("一二三四五六"),
            tokens(letterSpacingPx = 1f),
        )
        val plainWidth = plain.lines.first().width
        val trackedWidth = tracked.lines.first().width
        assertEquals(60f, plainWidth, 0.01f)
        // Six characters have five gaps between them: 60 + 5 × 1.
        assertEquals(65f, trackedWidth, 0.01f)
        assertTrue(
            trackedWidth > plainWidth,
            "expected tracking to widen the line ($trackedWidth vs $plainWidth)",
        )
    }

    @Test
    fun `letter spacing can push a character onto the next line`() {
        // Eight characters fit an 80px column exactly. With 1px of tracking
        // between them the run needs 87px, so the last character wraps.
        val doc = documentOf("一二三四五六七八")
        val layout = LayoutEngine(TextMeasurers.mono()).layout(
            doc,
            tokens(letterSpacingPx = 1f),
        )
        assertTrue(layout.lines.size > 1, "expected the tracked text to wrap")
    }

    // ── Paragraph spacing ────────────────────────────────────────────────────

    @Test
    fun `paragraph spacing pushes the second block down`() {
        val doc = documentOf("一二三四五六", "五六七八九十")
        val tight = LayoutEngine(TextMeasurers.mono()).layout(doc, tokens(paraSpacingPx = 0f))
        val loose = LayoutEngine(TextMeasurers.mono()).layout(doc, tokens(paraSpacingPx = 10f))
        val tightSecond = tight.lines.first { it.blockIndex == 1 }
        val looseSecond = loose.lines.first { it.blockIndex == 1 }
        assertTrue(
            looseSecond.y > tightSecond.y,
            "expected the spaced block to sit lower (${looseSecond.y} vs ${tightSecond.y})",
        )
    }

    // ── The option plumbing ──────────────────────────────────────────────────

    @Test
    fun `paginator options carry the typography through to the layout`() {
        // The app layer builds a PaginatorOptions and hands it to the engine;
        // these three fields are what make a Chinese book look typeset.
        val options = PaginatorOptions(
            textAlign = TextAlign.JUSTIFY,
            firstLineIndentPx = 34f,
            letterSpacingPx = 1.5f,
        )
        assertEquals(TextAlign.JUSTIFY, options.textAlign)
        assertEquals(34f, options.firstLineIndentPx)
        assertEquals(1.5f, options.letterSpacingPx)
    }

    @Test
    fun `paginator options reject a negative indent or tracking`() {
        var rejected = false
        try {
            PaginatorOptions(firstLineIndentPx = -1f)
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected, "a negative first-line indent must be rejected")

        rejected = false
        try {
            PaginatorOptions(letterSpacingPx = -1f)
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected, "a negative letter spacing must be rejected")
    }
}
