package com.koodoreader.engine.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Chapter segmentation: title heuristics, offset correctness, length fallback. */
class ChapterSplitterTest {

    private fun split(text: String, options: ChapterSplitter.Options = ChapterSplitter.Options.Default) =
        ChapterSplitter.split(TextNormalizer.normalize(text), options)

    /** The invariant the pagination engine relies on: spans tile the text exactly. */
    private fun assertTiles(text: String, chapters: List<Chapter>) {
        var cursor = 0
        for (c in chapters) {
            assertEquals(cursor, c.startOffset, "gap/overlap before $c")
            assertTrue(c.endOffset > c.startOffset || text.isEmpty(), "empty span $c")
            cursor = c.endOffset
        }
        assertEquals(text.length, cursor, "chapters do not cover the whole text")
        chapters.forEachIndexed { i, c -> assertEquals(i, c.index) }
    }

    @Test
    @DisplayName("Chinese 第N章 / 第N节 / 第N卷 titles are recognised")
    fun chineseTitles() {
        val text = "第一章 初见\n内容一\n第二章 重逢\n内容二\n第三节 尾声\n内容三\n"
        val chapters = split(text)
        assertEquals(
            listOf("第一章 初见", "第二章 重逢", "第三节 尾声"),
            chapters.map { it.title },
            "detected=" + chapters.joinToString { "[${it.title}]${it.startOffset}..${it.endOffset}" },
        )
        assertTiles(text, chapters)
        assertEquals("第二章 重逢\n内容二\n", chapters[1].extract(text))
    }

    @Test
    @DisplayName("Chinese numerals, Arabic numerals and 卷N forms are all accepted")
    fun chineseNumeralVariants() {
        val text = "第一卷 启程\n甲\n第12章 数字\n乙\n第一百零八章 终局\n丙\n"
        assertEquals(
            listOf("第一卷 启程", "第12章 数字", "第一百零八章 终局"),
            split(text).map { it.title },
        )
    }

    @Test
    @DisplayName("Offsets are exact and every chapter extracts its own text")
    fun offsetsAreExact() {
        val text = "第一章 甲\nAAA\n第二章 乙\nBBB\n"
        val chapters = split(text)
        assertEquals(2, chapters.size)
        assertEquals("第一章 甲\nAAA\n", chapters[0].extract(text))
        assertEquals("第二章 乙\nBBB\n", chapters[1].extract(text))
        assertEquals(0, chapters[0].startOffset)
        assertEquals(chapters[0].endOffset, chapters[1].startOffset)
        assertEquals(text.length, chapters[1].endOffset)
    }

    @Test
    @DisplayName("English Chapter/Part/Section titles are recognised case-insensitively")
    fun englishTitles() {
        val text = "Chapter 1\nAlpha\nPART 2\nBeta\nSection 3.1\nGamma\nchapter four\nDelta\n"
        assertEquals(
            listOf("Chapter 1", "PART 2", "Section 3.1", "chapter four"),
            split(text).map { it.title },
        )
    }

    @Test
    @DisplayName("Special Chinese section names (序章/前言/后记…) start a chapter")
    fun specialTitles() {
        val text = "序章\n序的内容\n第一章 开始\n正文\n后记\n结束语\n"
        assertEquals(listOf("序章", "第一章 开始", "后记"), split(text).map { it.title })
    }

    @Test
    @DisplayName("Markdown ATX headings become chapters with their level")
    fun markdownHeadings() {
        val text = "# 第一卷\n内容\n## 第一章 起\n内容二\n### 细节\n内容三\n"
        val chapters = split(text)
        assertEquals(listOf("第一卷", "第一章 起", "细节"), chapters.map { it.title })
        assertEquals(listOf(1, 2, 3), chapters.map { it.level })
    }

    @Test
    @DisplayName("Text before the first title becomes an explicit preface chapter")
    fun prefaceChapter() {
        val text = "这是封面上的文字。\n作者：某人\n第一章 开始\n正文\n"
        val chapters = split(text)
        assertEquals(
            2, chapters.size,
            "detected=" + chapters.joinToString { "[${it.title}]${it.startOffset}..${it.endOffset}" },
        )
        assertEquals("", chapters[0].title)
        assertTrue(chapters[0].isPreface)
        assertEquals("这是封面上的文字。\n作者：某人\n", chapters[0].extract(text))
    }

    @Test
    @DisplayName("A file with no titles yields one whole-text chapter (then the length fallback)")
    fun noChapters() {
        val text = "只是一段普通的文字，没有任何章节标题。\n第二行也是正文。\n"
        val chapters = split(text)
        assertEquals(1, chapters.size)
        assertEquals("", chapters[0].title)
        assertEquals(0, chapters[0].startOffset)
        assertEquals(text.length, chapters[0].endOffset)
        assertFalse(ChapterSplitter.hasChapters(text))
    }

    @Test
    @DisplayName("A very long chapter is cut at a paragraph break and marked synthesized")
    fun oversizedChapterFallback() {
        val paragraph = "这是一段用来填充内容的中文段落，长度大约二十个字符。\n\n"
        val body = paragraph.repeat(40) // ~ 1000+ chars
        val text = "第一章 长章\n$body"
        val options = ChapterSplitter.Options(maxChapterLength = 300)
        val chapters = split(text, options)
        assertTrue(chapters.size > 2, "expected several parts, got ${chapters.size}: ${chapters.map { it.title }}")
        assertTiles(text, chapters)
        chapters.forEach { assertTrue(it.length <= 300, "part too long: $it") }
        assertTrue(chapters.drop(1).all { it.synthesized }, "only the first part keeps the real title")
        assertEquals("第一章 长章", chapters[0].title)
        assertEquals("第一章 长章 (2)", chapters[1].title)
        // Cuts land on paragraph boundaries, so no part starts mid-sentence.
        chapters.drop(1).forEach { assertFalse(it.extract(text).startsWith("段"), "cut mid-paragraph: $it") }
    }

    @Test
    @DisplayName("The length fallback joins back into exactly the original text")
    fun fallbackIsLossless() {
        val text = "正文".repeat(500)
        val chapters = split(text, ChapterSplitter.Options(maxChapterLength = 137))
        assertTiles(text, chapters)
        assertEquals(text, chapters.joinToString("") { it.extract(text) })
    }

    @Test
    @DisplayName("A custom parserRegex overrides the built-in heuristics")
    fun customParserRegex() {
        val text = "⟨一⟩ 开端\n内容\n⟨二⟩ 发展\n内容\n"
        val chapters = split(text, ChapterSplitter.Options(parserRegex = "^⟨.+⟩.*$"))
        assertEquals(listOf("⟨一⟩ 开端", "⟨二⟩ 发展"), chapters.map { it.title })
    }

    @Test
    @DisplayName("Title-like prose (第二天早上…) and long lines are not chapters")
    fun falsePositiveGuards() {
        val text = "第二天早上，他很早就醒了过来，然后穿好衣服出门去了。\n" +
            "第" + "很长的标题".repeat(12) + "章\n" +
            "普通正文\n"
        assertFalse(ChapterSplitter.hasChapters(text), "false positives: ${split(text).map { it.title }}")
    }

    @Test
    @DisplayName("Without titles the whole body stays covered even with includePreface=false")
    fun preambleWithoutTitles() {
        val text = "封面\n作者\n"
        val chapters = split(text, ChapterSplitter.Options(includePreface = false))
        // With no titles at all the whole body must still be covered, otherwise
        // the reader would show an empty book.
        assertEquals(1, chapters.size)
        assertEquals(0, chapters[0].startOffset)
        assertEquals(text.length, chapters[0].endOffset)
    }

    @Test
    @DisplayName("Empty input returns no chapters")
    fun emptyInput() {
        assertTrue(split("").isEmpty())
        assertTrue(ChapterSplitter.titles("").isEmpty())
    }

    @Test
    @DisplayName("CRLF input normalises before splitting, so offsets refer to the LF text")
    fun crlfOffsets() {
        val raw = "第一章 甲\r\nAAA\r\n第二章 乙\r\nBBB\r\n"
        val text = TextNormalizer.normalize(raw)
        val chapters = ChapterSplitter.split(text)
        assertEquals(2, chapters.size)
        assertEquals(text.indexOf("第二章"), chapters[1].startOffset)
        assertTiles(text, chapters)
    }
}
