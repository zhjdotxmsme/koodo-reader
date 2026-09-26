package com.koodoreader.reader.epubhost

import com.koodoreader.engine.layout.TextMeasurers
import com.koodoreader.engine.mobi.MobiBook
import com.koodoreader.engine.mobi.MobiCompression
import com.koodoreader.engine.mobi.MobiMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * MOBI / AZW3 会话测试（多格式接入）：[MobiBook]（text = HTML 正文）经
 * HtmlFlattener → PagedDocumentSession 分页 + CFI 往返。
 *
 * 用 fromBook 注入程序化 books（真实 .mobi 夹具属 engine/mobi 的测试资源，
 * 本层只验证接线与分页/CFI 语义）。
 */
class MobiBookSessionTest {

    private fun book(
        text: String,
        title: String? = "Test Book",
        pdbName: String = "test",
    ) = MobiBook(
        metadata = MobiMetadata(title = title),
        text = text,
        resources = emptyList(),
        cover = null,
        compression = MobiCompression.NONE,
        encoding = "UTF-8",
        pdbName = pdbName,
    )

    private fun session(b: MobiBook): MobiBookSession =
        MobiBookSession.fromBook(b, 400f, 700f, TextMeasurers.mono())

    @Test
    fun `mobi html is flattened and paginated`() {
        val html = (1..40).joinToString("") { "<p>Paragraph $it with enough text to fill a line or two.</p>" }
        session(book(html)).use { s ->
            assertTrue(s.pageCount > 1, "expected multiple pages, got ${s.pageCount}")
            assertTrue(s.pageLines(0).isNotEmpty())
            assertTrue(s.pageLines(0).any { it.text.contains("Paragraph") })
        }
    }

    @Test
    fun `chapter label comes from metadata title`() {
        session(book("<p>x</p>", title = "My Title")).use { s ->
            assertEquals(1, s.chapterCount)
            assertEquals("My Title", s.chapterLabel(0))
        }
    }

    @Test
    fun `empty title falls back to pdb name`() {
        session(book("<p>x</p>", title = null, pdbName = "PDBNAME")).use { s ->
            assertEquals("PDBNAME", s.chapterLabel(0))
        }
    }

    @Test
    fun `mobi cfi round-trips across pages`() {
        val html = (1..60).joinToString("") { "<p>Line $it content here.</p>" }
        session(book(html)).use { s ->
            for (p in 0 until s.pageCount) {
                val cfi = s.cfiForPage(p) ?: continue
                assertTrue(cfi.startsWith("epubcfi("), cfi)
                assertEquals(p, s.pageForCfi(cfi), "page $p round-trip")
            }
            assertNull(s.pageForCfi("not a cfi"))
            assertNotNull(s.cfiForPage(0))
        }
    }

    @Test
    fun `extension support table`() {
        assertTrue(MobiBookSession.supportsExtension("a.mobi"))
        assertTrue(MobiBookSession.supportsExtension("b.AZW3"))
        assertTrue(MobiBookSession.supportsExtension("c.azw"))
        assertTrue(!MobiBookSession.supportsExtension("d.epub"))
    }

    @Test
    fun `unparseable moBI file fails with a typed error`() {
        val junk = File.createTempFile("junk", ".mobi").apply {
            writeText("definitely not a mobi")
            deleteOnExit()
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            MobiBookSession.open(junk, 400f, 700f, TextMeasurers.mono())
        }
    }
}
