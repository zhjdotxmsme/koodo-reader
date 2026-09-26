package com.koodoreader.reader.epubhost

import com.koodoreader.engine.layout.TextMeasurers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * TXT / MD 会话测试（多格式接入）：编码探测解码 → 划章 → 段落 TextBlock →
 * 分页 → CFI 往返（确定性 mono measurer）。
 */
class TextBookSessionTest {

    @TempDir
    lateinit var dir: File

    private val txt = """
        第一章 开始
        这是第一章的第一段正文，内容足够长以便分页。

        这是第一章的第二段正文。

        第二章 继续
        第二章的正文段落，同样需要足够的文字量来产生多行。

        第三章 结束
        最后一章的正文。
    """.trimIndent()

    private fun open(file: File): TextBookSession =
        TextBookSession.open(file, 400f, 700f, TextMeasurers.mono())

    @Test
    fun `txt is split into chapters and paginated`() {
        val f = File(dir, "book.txt").apply { writeText(txt, Charsets.UTF_8) }
        open(f).use { s ->
            assertTrue(s.pageCount > 0)
            assertTrue(s.chapterCount >= 2, "expected chapter split, got ${s.chapterCount}")
            assertTrue(s.pageLines(0).isNotEmpty())
            assertTrue(s.pageLines(0).any { it.text.contains("第一章") })
        }
    }

    @Test
    fun `txt chapter labels and page mapping are consistent`() {
        val f = File(dir, "labels.txt").apply { writeText(txt, Charsets.UTF_8) }
        open(f).use { s ->
            val pages = (0 until s.chapterCount).map { s.pageOfChapter(it) }
            assertTrue(pages.zipWithNext().all { (a, b) -> a <= b }, "chapter pages ordered: $pages")
            assertTrue(s.chapterLabel(0).isNotBlank())
        }
    }

    @Test
    fun `txt cfi round-trips across pages`() {
        val f = File(dir, "cfi.txt").apply { writeText(txt, Charsets.UTF_8) }
        open(f).use { s ->
            for (p in 0 until s.pageCount) {
                val cfi = s.cfiForPage(p) ?: continue
                assertTrue(cfi.startsWith("epubcfi("), cfi)
                assertEquals(p, s.pageForCfi(cfi), "page $p round-trip")
            }
            assertNull(s.pageForCfi("epubcfi(/6/99!/2/0)"))
        }
    }

    @Test
    fun `md is rendered through the markdown pipeline`() {
        val md = "# Title\n\nSome **bold** text that is long enough to paginate.\n\n- item one\n- item two\n"
        val f = File(dir, "doc.md").apply { writeText(md, Charsets.UTF_8) }
        open(f).use { s ->
            assertTrue(s.pageCount > 0)
            val all = s.pageLines(0).joinToString(" ") { it.text }
            assertTrue(all.contains("Title") || all.contains("bold"), "rendered text: $all")
        }
    }

    @Test
    fun `extension support table`() {
        assertTrue(TextBookSession.supportsExtension("a.txt"))
        assertTrue(TextBookSession.supportsExtension("b.MD"))
        assertTrue(TextBookSession.supportsExtension("c.markdown"))
        assertTrue(!TextBookSession.supportsExtension("d.epub"))
        assertTrue(!TextBookSession.supportsExtension("e.pdf"))
    }

    @Test
    fun `empty file yields a single blank page instead of failing`() {
        val f = File(dir, "empty.txt").apply { writeText("") }
        open(f).use { s ->
            // 分页器对空文档仍产出一页（空白），宿主据此显示空页而非报错。
            assertEquals(1, s.pageCount)
            assertTrue(s.pageLines(0).isEmpty())
        }
    }
}
