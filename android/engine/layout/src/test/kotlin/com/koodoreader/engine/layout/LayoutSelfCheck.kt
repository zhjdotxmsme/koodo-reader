package com.koodoreader.engine.layout

import kotlin.system.exitProcess

/**
 * Framework-free verification runner for the native layout engine.
 *
 * Mirrors :engine:cfi:CfiSelfCheck: all assertions live in this file so the
 * engine can be verified with nothing but a Kotlin compiler (offline review,
 * air-gapped machines, a fast CI smoke step before Gradle).  The JUnit test
 * classes are thin wrappers around exactly this code.
 *
 * Usage:
 *   java -cp <classes>[:<resources>] com.koodoreader.engine.layout.LayoutSelfCheckKt
 *
 * Exit codes: 0 all good, 1 assertions failed.
 */
fun main() {
    println("Koodo Reader - native layout self-check")

    val failures = mutableListOf<String>()

    // ── LayoutTokens arithmetic ─────────────────────────────────────────────
    run {
        val t = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 20f,
            fontSizePx = 17f,
            lineHeightMultiple = 1.5f,
        )
        checkEq("contentWidthPx", 80f, t.contentWidthPx, failures)
        checkEq("contentHeightPx", 60f, t.contentHeightPx, failures)
        checkEq("columnWidthPx(1)", 80f, t.columnWidthPx(1), failures)
        checkEq("columnWidthPx(2)", 40f, t.columnWidthPx(2), failures)
        checkEq("columnLeftPx(0,2)", 10f, t.columnLeftPx(0, 2), failures)
        checkEq("columnLeftPx(1,2)", 50f, t.columnLeftPx(1, 2), failures)

        // Negative margin expands the content box (CSS margin semantics).
        val neg = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = -20f,
            marginVerticalPx = -20f,
        )
        checkEq("neg contentWidthPx", 140f, neg.contentWidthPx, failures)
        checkEq("neg contentHeightPx", 140f, neg.contentHeightPx, failures)

        // Column gap splits the content box.
        val gapped = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            columnGapPx = 10f,
        )
        checkEq("gap contentWidthPx", 80f, gapped.contentWidthPx, failures)
        checkEq("gap columnWidthPx(2)", 35f, gapped.columnWidthPx(2), failures)
        checkEq("gap columnLeftPx(1,2)", 55f, gapped.columnLeftPx(1, 2), failures)
    }

    // ── DesktopReaderConfig parsing ─────────────────────────────────────────
    run {
        checkEq("fontSizeOf null", 17f, DesktopReaderConfig.fontSizeOf(null), failures)
        checkEq("fontSizeOf 20", 20f, DesktopReaderConfig.fontSizeOf("20"), failures)
        checkEq("fontSizeOf clamped", 80f, DesktopReaderConfig.fontSizeOf("999"), failures)
        checkEq("lineHeightOf empty", 1.5f, DesktopReaderConfig.lineHeightOf(""), failures)
        checkEq("marginOf -40", -40f, DesktopReaderConfig.marginOf("-40"), failures)

        val tokens = DesktopReaderConfig.tokensOf(
            mapOf(
                "fontSize" to "20",
                "margin" to "10",
                "lineHeight" to "1.5",
                "letterSpacing" to "2",
                "paraSpacing" to "8",
                "textAlign" to "Justify",
            ),
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
        )
        checkEq("tokens fontSize", 20f, tokens.fontSizePx, failures)
        checkEq("tokens margin", 10f, tokens.marginHorizontalPx, failures)
        checkEq("tokens lineHeight", 1.5f, tokens.lineHeightMultiple, failures)
        checkEq("tokens letterSpacing", 2f, tokens.letterSpacingPx, failures)
        checkEq("tokens paraSpacing", 8f, tokens.paraSpacingPx, failures)
        checkEq("tokens textAlign", TextAlign.JUSTIFY, tokens.textAlign, failures)

        // Unknown keys are ignored.
        val sparse = DesktopReaderConfig.tokensOf(
            mapOf("fontFamily" to "serif"),
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
        )
        checkEq("sparse fontSize", 17f, sparse.fontSizePx, failures)

        // tokensOrNull falls back to defaults on null config.
        val fallback = DesktopReaderConfig.tokensOrNull(null, 100f, 100f)
        checkTrue("tokensOrNull non-null", fallback != null, failures)
        checkEq("tokensOrNull default fontSize", 17f, fallback!!.fontSizePx, failures)
    }

    // ── BreakOpportunities ──────────────────────────────────────────────────
    run {
        checkTrue("space is collapsible", isCollapsibleWhitespace(' '), failures)
        checkTrue("tab is collapsible", isCollapsibleWhitespace('\t'), failures)
        checkTrue("newline is collapsible", isCollapsibleWhitespace('\n'), failures)
        checkTrue("NBSP is not collapsible", !isCollapsibleWhitespace('\u00A0'), failures)

        checkTrue("CJK is ideograph", isIdeograph('一'), failures)
        checkTrue("ASCII is not ideograph", !isIdeograph('a'), failures)

        // breakEnds: plain ASCII — only after spaces.
        val ascii = breakEnds("aa bb cc", WordBreak.NORMAL)
        checkEq("ascii breakEnds size", 2, ascii.size, failures)
        checkEq("ascii breakEnds[0]", 3, ascii[0], failures)
        checkEq("ascii breakEnds[1]", 6, ascii[1], failures)

        // breakEnds: CJK — between every pair of ideographs.
        val cjk = breakEnds("一二三四", WordBreak.NORMAL)
        checkEq("cjk breakEnds size", 3, cjk.size, failures)
        checkEq("cjk breakEnds[0]", 1, cjk[0], failures)
        checkEq("cjk breakEnds[1]", 2, cjk[1], failures)
        checkEq("cjk breakEnds[2]", 3, cjk[2], failures)

        // breakEnds: KEEP_ALL — CJK has no breaks.
        val keepAll = breakEnds("一二三四", WordBreak.KEEP_ALL)
        checkEq("keepAll breakEnds size", 0, keepAll.size, failures)

        // breakEnds: BREAK_ALL — between every character.
        val breakAll = breakEnds("ab", WordBreak.BREAK_ALL)
        checkEq("breakAll breakEnds size", 2, breakAll.size, failures)
        checkEq("breakAll breakEnds[0]", 1, breakAll[0], failures)
        checkEq("breakAll breakEnds[1]", 2, breakAll[1], failures)

        // trimTrailingWhitespace.
        checkEq("trim end", 5, trimTrailingWhitespace("hello   ", 0, 8), failures)
        checkEq("trim no-op", 5, trimTrailingWhitespace("hello", 0, 5), failures)
        checkEq("trim all-ws keeps end", 3, trimTrailingWhitespace("   ", 0, 3), failures)
    }

    // ── LayoutEngine: basic single-line block ───────────────────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
        )
        // contentWidth = 80, contentHeight = 80.
        // lineH = 1.5 × 1.2 × 10 = 18, baseline = 1.5 × 1.2 × 0.8 × 10 = 14.4.
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of("hello", elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        checkEq("single page", 1, result.pageCount, failures)
        checkEq("single line", 1, result.lineCount, failures)
        val line = result.lines[0]
        checkEq("line text", "hello", line.text, failures)
        checkEq("line x", 10f, line.x, failures)
        checkEq("line y", 10f, line.y, failures)
        checkEq("line width", 50f, line.width, failures)
        checkEq("line height", 18f, line.height, failures)
        checkEq("line baseline", 24.4f, line.baselineY, failures)
        checkEq("line page", 0, line.page, failures)
        checkEq("line column", 0, line.column, failures)
        checkEq("line fontSize", 10f, line.fontSizePx, failures)
        checkEq("line start", 0, line.start, failures)
        checkEq("line end", 5, line.end, failures)
    }

    // ── LayoutEngine: line breaking at word boundaries ─────────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
        )
        // contentWidth = 80 → 8 chars per line.
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of("aa bb cc dd", elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        checkEq("break line count", 2, result.lineCount, failures)
        val l0 = result.lines[0]
        val l1 = result.lines[1]
        checkEq("break l0 text", "aa bb cc", l0.text, failures)
        checkEq("break l0 start", 0, l0.start, failures)
        checkEq("break l0 end", 8, l0.end, failures)
        checkEq("break l0 width", 80f, l0.width, failures)
        checkEq("break l1 text", "dd", l1.text, failures)
        checkEq("break l1 start", 9, l1.start, failures)
        checkEq("break l1 end", 11, l1.end, failures)
        checkEq("break l1 width", 20f, l1.width, failures)
    }

    // ── LayoutEngine: overflow-wrap BREAK_WORD force-breaks long runs ──────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
            overflowWrap = OverflowWrap.BREAK_WORD,
        )
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of("abcdefghij", elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        checkEq("overflow line count", 2, result.lineCount, failures)
        checkEq("overflow l0 text", "abcdefgh", result.lines[0].text, failures)
        checkTrue("overflow l0 forced", result.lines[0].forced, failures)
        checkEq("overflow l1 text", "ij", result.lines[1].text, failures)
        checkTrue("overflow l1 not forced", !result.lines[1].forced, failures)
    }

    // ── LayoutEngine: overflow-wrap NORMAL lets text overflow ──────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
            overflowWrap = OverflowWrap.NORMAL,
        )
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of("abcdefghij", elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        checkEq("normal overflow line count", 1, result.lineCount, failures)
        checkEq("normal overflow text", "abcdefghij", result.lines[0].text, failures)
        checkEq("normal overflow width", 100f, result.lines[0].width, failures)
    }

    // ── LayoutEngine: letterSpacing ─────────────────────────────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
            letterSpacingPx = 2f,
        )
        // 8 chars × 10 + 7 × 2 = 94 > 80 → 7 chars: 70 + 12 = 82 > 80 → 6 chars: 60+10=70 ≤ 80.
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of("abcdefghij", elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        checkEq("letterSpacing line count", 2, result.lineCount, failures)
        checkEq("letterSpacing l0 text", "abcdef", result.lines[0].text, failures)
        checkEq("letterSpacing l0 width", 70f, result.lines[0].width, failures)
        checkEq("letterSpacing l1 text", "ghij", result.lines[1].text, failures)
        checkEq("letterSpacing l1 width", 48f, result.lines[1].width, failures)
    }

    // ── LayoutEngine: pagination fills columns then pages ──────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
        )
        // contentHeight = 80, lineH = 18 → 4 lines per column.
        // 80 chars = 10 lines of 8 → 4 + 4 + 2 = 3 pages.
        val text = "01234567".repeat(10)
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of(text, elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        checkEq("pagination page count", 3, result.pageCount, failures)
        checkEq("pagination line count", 10, result.lineCount, failures)
        checkEq("pagination p0 lines", 4, result.pages[0].lines.size, failures)
        checkEq("pagination p1 lines", 4, result.pages[1].lines.size, failures)
        checkEq("pagination p2 lines", 2, result.pages[2].lines.size, failures)
        checkEq("pagination p0 l0 y", 10f, result.pages[0].lines[0].y, failures)
        checkEq("pagination p0 l3 y", 64f, result.pages[0].lines[3].y, failures)
        checkEq("pagination p1 l0 y", 10f, result.pages[1].lines[0].y, failures)
    }

    // ── LayoutEngine: widow/orphan control ─────────────────────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
        )
        // contentHeight = 80, lineH = 18 → 4 lines per column.
        // 5 lines of 8 chars: 4 fit, 1 left over → widows rule pulls one back → 3 + 2.
        val text = "aaaaaaaa bbbbbbbb cccccccc dddddddd eeeeeeee"
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of(text, elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        checkEq("widow page count", 2, result.pageCount, failures)
        checkEq("widow p0 lines", 3, result.pages[0].lines.size, failures)
        checkEq("widow p1 lines", 2, result.pages[1].lines.size, failures)
    }

    // ── LayoutEngine: two-column layout ────────────────────────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
            columnGapPx = 10f,
        )
        // contentWidth = 80, gap = 10 → columnWidth = 35 → 3 chars per line.
        // contentHeight = 80, lineH = 18 → 4 lines per column.
        // 15 chars = 5 lines. 4 fit in col 0, but widow control pulls 1 back → 3 + 2.
        val text = "aaaaaaaaaaaaaaa"
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of(text, elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer, PaginatorOptions(columnsPerPage = 2)).layout(doc, tokens)
        checkEq("2col page count", 1, result.pageCount, failures)
        checkEq("2col column count", 2, result.pages[0].columns.size, failures)
        checkEq("2col col0 lines", 3, result.pages[0].columns[0].lines.size, failures)
        checkEq("2col col1 lines", 2, result.pages[0].columns[1].lines.size, failures)
        checkEq("2col col0 x", 10f, result.pages[0].columns[0].x, failures)
        checkEq("2col col1 x", 55f, result.pages[0].columns[1].x, failures)
    }

    // ── LayoutEngine: chapter starts new page ──────────────────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
        )
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of("hello", elementIndex = 0)),
                ),
                SpineItem(
                    index = 1,
                    href = "ch2.xhtml",
                    blocks = listOf(TextBlock.of("world", elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        checkEq("chapter page count", 2, result.pageCount, failures)
        checkEq("chapter p0 lines", 1, result.pages[0].lines.size, failures)
        checkEq("chapter p1 lines", 1, result.pages[1].lines.size, failures)
        checkEq("chapter p0 text", "hello", result.pages[0].lines[0].text, failures)
        checkEq("chapter p1 text", "world", result.pages[1].lines[0].text, failures)
    }

    // ── LayoutEngine: textAlign right ──────────────────────────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
            textAlign = TextAlign.RIGHT,
        )
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of("abc", elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        // contentWidth = 80, text width = 30 → x = 10 + (80 - 30) = 60.
        checkEq("right align x", 60f, result.lines[0].x, failures)
        checkEq("right align width", 30f, result.lines[0].width, failures)
    }

    // ── LayoutEngine: textAlign justify ────────────────────────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
            textAlign = TextAlign.JUSTIFY,
        )
        // "a b c d e f g h" → 2 lines: "a b c d" (70px, 3 gaps, stretch=10) + "e f g h" (last, no stretch).
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of("a b c d e f g h", elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        checkEq("justify line count", 2, result.lineCount, failures)
        checkEq("justify l0 stretch", 10f, result.lines[0].justifyStretchPx, failures)
        checkEq("justify l0 align", TextAlign.JUSTIFY, result.lines[0].align, failures)
        checkEq("justify l1 stretch", 0f, result.lines[1].justifyStretchPx, failures)
        checkEq("justify l1 align", TextAlign.LEFT, result.lines[1].align, failures)
    }

    // ── LayoutEngine: first-line indent ────────────────────────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
            firstLineIndentPx = 20f,
        )
        // contentWidth = 80, indent = 20 → available = 60 → 6 chars first line.
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of("aa bb cc dd", elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        val l0 = result.lines[0]
        val l1 = result.lines[1]
        checkEq("indent l0 text", "aa bb", l0.text, failures)
        checkEq("indent l0 indentPx", 20f, l0.indentPx, failures)
        checkEq("indent l0 x", 30f, l0.x, failures)
        checkEq("indent l0 width", 50f, l0.width, failures)
        checkEq("indent l1 text", "cc dd", l1.text, failures)
        checkEq("indent l1 indentPx", 0f, l1.indentPx, failures)
        checkEq("indent l1 x", 10f, l1.x, failures)
        checkEq("indent l1 width", 50f, l1.width, failures)
    }

    // ── LayoutEngine: paragraph spacing (margin collapsing) ────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
            paraSpacingPx = 10f,
        )
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(
                        TextBlock.of("aaaa", elementIndex = 0),
                        TextBlock.of("bbbb", elementIndex = 1),
                    ),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        checkEq("para line count", 2, result.lineCount, failures)
        val l0 = result.lines[0]
        val l1 = result.lines[1]
        checkEq("para l0 y", 10f, l0.y, failures)
        // l0 bottom = 10 + 18 = 28. gap = 10. l1 y = 38.
        checkEq("para l1 y", 38f, l1.y, failures)
    }

    // ── LayoutEngine: fontSizeScale on block style ─────────────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
        )
        // fontSizeScale = 2 → fontSize = 20, lineH = 1.5 × 1.2 × 20 = 36.
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(
                        TextBlock.of("hello", elementIndex = 0, style = ParagraphStyle(fontSizeScale = 2f)),
                    ),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        val line = result.lines[0]
        checkEq("scale fontSize", 20f, line.fontSizePx, failures)
        checkEq("scale height", 36f, line.height, failures)
        checkEq("scale baseline", 28.8f, line.baselineY - line.y, failures)
        // "hello" at 20px = 100px > 80px → BREAK_WORD → 4 chars (80px) + 1 char.
        checkEq("scale line count", 2, result.lineCount, failures)
        checkEq("scale l0 width", 80f, line.width, failures)
    }

    // ── LayoutEngine: CJK text with custom width table ─────────────────────
    run {
        val cjkMeasurer = TextMeasurers.widthTable(
            '一'.code to 1f, '二'.code to 1f, '三'.code to 1f,
            '四'.code to 1f, '五'.code to 1f, '六'.code to 1f,
            '七'.code to 1f, '八'.code to 1f, '九'.code to 1f,
            '十'.code to 1f,
        )
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
        )
        // contentWidth = 80. CJK char = 10 px. 8 chars per line.
        val cjkText = "一二三四五六七八九十" // 10 chars → 8 + 2.
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of(cjkText, elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(cjkMeasurer).layout(doc, tokens)
        checkEq("cjk line count", 2, result.lineCount, failures)
        checkEq("cjk l0 text", "一二三四五六七八", result.lines[0].text, failures)
        checkEq("cjk l1 text", "九十", result.lines[1].text, failures)
    }

    // ── LayoutEngine: positionAt hit-testing ───────────────────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
        )
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of("hello", elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        // Line at x=10, y=10, width=50, height=18.
        val pos0 = result.positionAt(15f, 12f)
        checkTrue("positionAt non-null", pos0 != null, failures)
        checkEq("positionAt offset", 0, pos0!!.charOffset, failures)

        val pos2 = result.positionAt(35f, 12f)
        checkEq("positionAt offset 2", 2, pos2!!.charOffset, failures)

        checkTrue("positionAt outside x", result.positionAt(5f, 12f) == null, failures)
        checkTrue("positionAt outside y", result.positionAt(15f, 50f) == null, failures)
    }

    // ── LayoutEngine: lineAt boundary semantics ────────────────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
        )
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of("aa bb cc dd", elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        // Lines: "aa bb cc" [0,8), "dd" [9,11).
        val lineAt8 = result.lineAt(LayoutPosition(0, 0, 8))
        checkTrue("lineAt boundary non-null", lineAt8 != null, failures)
        checkEq("lineAt boundary text", "aa bb cc", lineAt8!!.text, failures)

        val lineAt9 = result.lineAt(LayoutPosition(0, 0, 9))
        checkEq("lineAt second text", "dd", lineAt9!!.text, failures)
    }

    // ── LayoutEngine: progress ─────────────────────────────────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
        )
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of("hello", elementIndex = 0)),
                ),
                SpineItem(
                    index = 1,
                    href = "ch2.xhtml",
                    blocks = listOf(TextBlock.of("world", elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        val p0 = result.progress(LayoutPosition(0, 0, 0))
        checkEq("progress ch0 start", 0f, p0.chapterPercent, failures)
        checkEq("progress doc start", 0f, p0.documentPercent, failures)

        val p1 = result.progress(LayoutPosition(1, 0, 0))
        checkEq("progress ch1 start", 0f, p1.chapterPercent, failures)
        checkEq("progress doc ch1", 0.5f, p1.documentPercent, failures)

        val pMid = result.progress(LayoutPosition(0, 0, 2))
        checkEq("progress ch0 mid", 0.4f, pMid.chapterPercent, failures)
        checkEq("progress doc mid", 0.2f, pMid.documentPercent, failures)
    }

    // ── CfiAddressing: round-trip ──────────────────────────────────────────
    run {
        val pos = LayoutPosition(spineIndex = 3, elementIndex = 2, charOffset = 15)
        val cfi = CfiAddressing.toCfi(pos)
        checkEq("cfi string", "epubcfi(/6/3!/2/0/2/1:15)", cfi, failures)

        val parsed = CfiAddressing.fromCfi(cfi)
        checkTrue("cfi round-trip non-null", parsed != null, failures)
        checkEq("cfi round-trip spine", 3, parsed!!.spineIndex, failures)
        checkEq("cfi round-trip element", 2, parsed.elementIndex, failures)
        checkEq("cfi round-trip offset", 15, parsed.charOffset, failures)

        // Chapter start: no element, no offset.
        val chapterPos = LayoutPosition(1, -1, 0)
        val chapterCfi = CfiAddressing.toCfi(chapterPos)
        checkEq("chapter cfi", "epubcfi(/6/1!/2/0)", chapterCfi, failures)
        val parsedChapter = CfiAddressing.fromCfi(chapterCfi)
        checkTrue("chapter round-trip", parsedChapter != null, failures)
        checkEq("chapter round-trip spine", 1, parsedChapter!!.spineIndex, failures)
        checkTrue("chapter isChapterStart", parsedChapter.isChapterStart, failures)
    }

    // ── LayoutResult: cfiSequence is deterministic ─────────────────────────
    run {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
        )
        val doc = EpubDocument(
            listOf(
                SpineItem(
                    index = 0,
                    href = "ch1.xhtml",
                    blocks = listOf(TextBlock.of("hello", elementIndex = 0)),
                ),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)
        val seq = result.cfiSequence()
        checkTrue("cfiSequence non-empty", seq.isNotEmpty(), failures)
        checkTrue("cfiSequence starts with p1:", seq.startsWith("p1:"), failures)
        checkTrue("cfiSequence contains CFI", seq.contains("epubcfi("), failures)
    }

    // ── Summary ─────────────────────────────────────────────────────────────
    if (failures.isEmpty()) {
        println("OK  (all checks passed)")
        exitProcess(0)
    }
    println("FAILED  (${failures.size} failures)")
    failures.forEach { println("  FAIL $it") }
    exitProcess(1)
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun checkEq(name: String, expected: Float, actual: Float, failures: MutableList<String>) {
    if (kotlin.math.abs(expected - actual) > 0.01f) {
        failures.add("$name: expected $expected, got $actual")
    }
}

private fun checkEq(name: String, expected: Int, actual: Int, failures: MutableList<String>) {
    if (expected != actual) failures.add("$name: expected $expected, got $actual")
}

private fun checkEq(name: String, expected: String, actual: String, failures: MutableList<String>) {
    if (expected != actual) failures.add("$name: expected \"$expected\", got \"$actual\"")
}

private fun checkEq(name: String, expected: TextAlign, actual: TextAlign, failures: MutableList<String>) {
    if (expected != actual) failures.add("$name: expected $expected, got $actual")
}

private fun checkTrue(name: String, condition: Boolean, failures: MutableList<String>) {
    if (!condition) failures.add("$name: expected true")
}
