package com.koodoreader.reader.epubhost

import com.koodoreader.engine.layout.TextMeasurers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * FB2 / DOCX 会话接入测试（P5.5a/d）：生产者 → PagedDocumentSession 分页 →
 * CFI 往返，并验证标题进入章节标签。
 */
class P5BookSessionTest {

    @TempDir
    lateinit var dir: File

    private fun fb2File(): File {
        val body = (1..30).joinToString("") { "<p>第 $it 段 FB2 正文。</p>" }
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description><title-info><book-title>FB2 书名</book-title></title-info></description>
              <body><section><title><p>第一章</p></title>$body</section></body>
            </FictionBook>"""
        return File(dir, "book.fb2").apply { writeText(xml, Charsets.UTF_8) }
    }

    private fun docxFile(): File {
        val doc = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>第一章</w:t></w:r></w:p>
            ${(1..30).joinToString("") { "<w:p><w:r><w:t>第 $it 段 DOCX 正文。</w:t></w:r></w:p>" }}
            </w:body></w:document>"""
        val core = """<cp:coreProperties xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>DOCX 书名</dc:title></cp:coreProperties>"""
        val f = File(dir, "book.docx")
        ZipOutputStream(f.outputStream()).use { zos ->
            fun put(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("word/document.xml", doc)
            put("docProps/core.xml", core)
        }
        return f
    }

    @Test
    fun `fb2 session paginates with metadata title as label`() {
        Fb2BookSession.open(fb2File(), 400f, 700f, TextMeasurers.mono()).use { s ->
            assertTrue(s.pageCount > 1, "pages=${s.pageCount}")
            assertEquals("FB2 书名", s.chapterLabel(0))
            assertTrue(s.pageLines(0).any { it.text.contains("FB2 正文") })
        }
    }

    @Test
    fun `fb2 cfi round-trips`() {
        Fb2BookSession.open(fb2File(), 400f, 700f, TextMeasurers.mono()).use { s ->
            for (p in 0 until s.pageCount) {
                val cfi = s.cfiForPage(p) ?: continue
                assertEquals(p, s.pageForCfi(cfi), "page $p")
            }
        }
    }

    @Test
    fun `docx session paginates with core title as label`() {
        DocxBookSession.open(docxFile(), 400f, 700f, TextMeasurers.mono()).use { s ->
            assertTrue(s.pageCount > 1, "pages=${s.pageCount}")
            assertEquals("DOCX 书名", s.chapterLabel(0))
            assertTrue(s.pageLines(0).any { it.text.contains("DOCX 正文") })
        }
    }

    @Test
    fun `docx cfi round-trips and resume works`() {
        DocxBookSession.open(docxFile(), 400f, 700f, TextMeasurers.mono()).use { s ->
            val last = s.cfiForPage(s.pageCount - 1)
            assertTrue(last != null)
            assertEquals(s.pageCount - 1, s.resumePage(last))
        }
    }

    @Test
    fun `extension support tables`() {
        assertTrue(Fb2BookSession.supportsExtension("a.fb2"))
        assertTrue(DocxBookSession.supportsExtension("b.DOCX"))
        assertTrue(!Fb2BookSession.supportsExtension("c.epub"))
        assertTrue(!DocxBookSession.supportsExtension("d.docx.bak"))
    }
}
