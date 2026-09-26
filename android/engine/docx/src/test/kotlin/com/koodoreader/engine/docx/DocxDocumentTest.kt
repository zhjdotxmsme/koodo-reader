package com.koodoreader.engine.docx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * DOCX 生产者测试（P5.5d）：程序化 OOXML zip 夹具，覆盖段落/标题/多 run/
 * 制表与换行/表格拍平/核心属性标题/类型化错误。
 */
class DocxDocumentTest {

    @TempDir
    lateinit var dir: File

    private val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
        <w:body>
          <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>文档标题</w:t></w:r></w:p>
          <w:p><w:r><w:t>第一段</w:t></w:r><w:r><w:t> 拼接的 run。</w:t></w:r></w:p>
          <w:p><w:r><w:t>带</w:t></w:r><w:tab/><w:r><w:t>制表符</w:t></w:r><w:br/><w:r><w:t>与换行。</w:t></w:r></w:p>
          <w:p/>
          <w:p><w:pPr><w:outlineLvl w:val="1"/></w:pPr><w:r><w:t>二级大纲标题</w:t></w:r></w:p>
          <w:tbl>
            <w:tr><w:tc><w:p><w:r><w:t>表格单元格文字</w:t></w:r></w:p></w:tc></w:tr>
          </w:tbl>
        </w:body></w:document>"""

    private val coreXml = """<?xml version="1.0" encoding="UTF-8"?>
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
          xmlns:dc="http://purl.org/dc/elements/1.1/">
          <dc:title>我的文档 &amp; 副标题</dc:title>
        </cp:coreProperties>"""

    private fun makeDocx(name: String, withCore: Boolean = true, doc: String = documentXml): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { zos ->
            fun put(entry: String, content: String) {
                zos.putNextEntry(ZipEntry(entry))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put(
                "[Content_Types].xml",
                """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                   <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                   </Types>""",
            )
            put("_rels/.rels", """<?xml version="1.0"?><Relationships/>""")
            put("word/document.xml", doc)
            if (withCore) put("docProps/core.xml", coreXml)
        }
        return f
    }

    @Test
    fun `docx is unzipped and paragraphs become blocks`() {
        val result = DocxDocument.fromFile(makeDocx("a.docx"))
        val text = result.blocks.joinToString(" | ") { it.text }
        assertTrue(text.contains("文档标题"), text)
        assertTrue(text.contains("第一段 拼接的 run。"), text)
    }

    @Test
    fun `core properties supply the title`() {
        val result = DocxDocument.fromFile(makeDocx("b.docx"))
        assertEquals("我的文档 & 副标题", result.title)
    }

    @Test
    fun `missing core properties yields null title`() {
        val result = DocxDocument.fromFile(makeDocx("c.docx", withCore = false))
        assertNull(result.title)
        assertTrue(result.blocks.isNotEmpty())
    }

    @Test
    fun `tab and break are turned into whitespace`() {
        val result = DocxDocument.fromFile(makeDocx("d.docx"))
        assertTrue(result.blocks.any { it.text.contains("带") && it.text.contains("制表符") })
        assertTrue(result.blocks.any { it.text.contains("与换行") })
    }

    @Test
    fun `table cell paragraphs are flattened in order`() {
        val text = DocxDocument.fromFile(makeDocx("e.docx")).blocks.joinToString(" | ") { it.text }
        assertTrue(text.contains("表格单元格文字"), text)
        // 顺序：标题 → 段落 → 表格（表格在 body 末尾）
        assertTrue(text.indexOf("文档标题") < text.indexOf("表格单元格文字"))
    }

    @Test
    fun `heading styles produce heading levels`() {
        assertEquals(1, DocxDocument.headingLevel("""<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr></w:p>"""))
        assertEquals(3, DocxDocument.headingLevel("""<w:p><w:pPr><w:pStyle w:val="heading3"/></w:pPr></w:p>"""))
        assertEquals(2, DocxDocument.headingLevel("""<w:p><w:pPr><w:outlineLvl w:val="1"/></w:pPr></w:p>"""))
        assertNull(DocxDocument.headingLevel("""<w:p><w:r><w:t>plain</w:t></w:r></w:p>"""))
    }

    @Test
    fun `xml entities in runs are decoded`() {
        val doc = """<w:document><w:body><w:p><w:r><w:t>A &amp; B &lt;tag&gt;</w:t></w:r></w:p></w:body></w:document>"""
        val text = DocxDocument.fromXml(doc).blocks.joinToString(" ") { it.text }
        assertTrue(text.contains("A & B <tag>"), text)
    }

    @Test
    fun `zip without document xml is a typed error`() {
        val f = File(dir, "empty.docx")
        ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("word/styles.xml"))
            zos.write("<styles/>".toByteArray())
            zos.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) { DocxDocument.fromFile(f) }
    }

    @Test
    fun `non zip docx fails with archive error`() {
        val junk = File(dir, "junk.docx").apply { writeText("not a zip") }
        assertThrows(com.koodoreader.core.archive.ArchiveException::class.java) {
            DocxDocument.fromFile(junk)
        }
    }
}
