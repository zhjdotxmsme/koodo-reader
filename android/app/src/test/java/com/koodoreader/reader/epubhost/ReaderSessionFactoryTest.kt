package com.koodoreader.reader.epubhost

import com.koodoreader.core.importer.BookRules
import com.koodoreader.engine.layout.TextMeasurers
import com.koodoreader.reader.shell.IntentRoutePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「规划格式全适配 + APK 打开能正确读取」的逻辑层证明：
 *
 *  1. **覆盖一致性**：`BookRules.BOOK_EXTENSIONS`（导入白名单，与桌面
 *     supportedFormats 集合相等，CI 有 check-import-rules.js 守卫）里每一个
 *     格式都必须有归宿——漫画 / PDF / ReaderSessionFactory / CBR(岛)，
 *     不允许有格式"导得进来但打不开"；
 *  2. **路由一致性**：intent 路由对白名单里每个格式都判原生（除 CBR），
 *     即外部打开与书架打开走同一条能读的路径；
 *  3. **端到端打开**：对可程序化构造夹具的格式，真实「文件 → 会话 → 分页」
 *     跑通并产出非空页（mobi/azw/azw3 的解析由 engine/mobi 的真实 calibre
 *     夹具测试覆盖，这里只断言工厂认领）。
 */
class ReaderSessionFactoryTest {

    @TempDir
    lateinit var dir: File

    // ---------------------------------------------------------- 覆盖一致性

    @Test
    fun `every supported book format has exactly one owner`() {
        val whitelist = BookRules.BOOK_EXTENSIONS.toSet()
        val comic = ReaderSessionFactory.COMIC_EXTENSIONS
        val island = ReaderSessionFactory.ISLAND_ONLY_EXTENSIONS
        val pdf = setOf("pdf")
        val factory = ReaderSessionFactory.formats

        // 无重叠
        val owners = listOf(comic, island, pdf, factory)
        for (i in owners.indices) {
            for (j in i + 1 until owners.size) {
                assertTrue(
                    owners[i].intersect(owners[j]).isEmpty(),
                    "overlap: ${owners[i].intersect(owners[j])}",
                )
            }
        }
        // 无遗漏：白名单里每个格式都被某一方认领
        val covered = owners.flatten().toSet()
        val uncovered = whitelist - covered
        assertTrue(uncovered.isEmpty(), "uncovered formats: $uncovered")
        // 也不该认领白名单外的格式
        assertTrue(covered.all { it in whitelist }, "claims outside whitelist: ${covered - whitelist}")
    }

    @Test
    fun `intent routing sends every whitelist format to a native path except cbr`() {
        for (ext in BookRules.BOOK_EXTENSIONS) {
            val route = IntentRoutePolicy.decide(null, "book.$ext")
            if (ext == "cbr") {
                assertEquals(IntentRoutePolicy.Route.ISLAND, route, "cbr must stay on the island")
            } else {
                assertTrue(
                    route != IntentRoutePolicy.Route.ISLAND,
                    "format $ext would fall to the island but has no island in the native APK",
                )
            }
        }
    }

    @Test
    fun `factory claims every non-comic non-pdf non-cbr whitelist format`() {
        for (ext in ReaderSessionFactory.formats) {
            assertTrue(ReaderSessionFactory.supports(ext), "factory must claim $ext")
            assertTrue(ReaderSessionFactory.supports(ext.uppercase()), "case-insensitive: $ext")
        }
        assertTrue(!ReaderSessionFactory.supports("cbz"))
        assertTrue(!ReaderSessionFactory.supports("pdf"))
        assertTrue(!ReaderSessionFactory.supports("cbr"))
        assertTrue(!ReaderSessionFactory.supports(null))
    }

    // ---------------------------------------------------------- 端到端打开

    private fun open(format: String, file: File): ReaderSession? =
        ReaderSessionFactory.open(format, file, 400f, 700f, TextMeasurers.mono())

    private fun assertReadable(format: String, file: File) {
        val session = open(format, file)
        assertNotNull(session, "$format: factory returned null")
        session!!.use { s ->
            assertTrue(s.pageCount > 0, "$format: no pages")
            assertTrue(
                (0 until s.pageCount).any { s.pageLines(it).isNotEmpty() },
                "$format: every page is empty",
            )
            // CFI 可写可回读（阅读进度/标注的基础）
            val cfi = s.cfiForPage(0)
            assertNotNull(cfi, "$format: no CFI for page 0")
            assertEquals(0, s.resumePage(cfi), "$format: CFI round-trip")
        }
    }

    private fun textFile(name: String, content: String): File =
        File(dir, name).apply { writeText(content, Charsets.UTF_8) }

    private fun epubFile(): File {
        val f = File(dir, "book.epub")
        ZipOutputStream(f.outputStream()).use { zos ->
            fun put(entry: String, content: String) {
                zos.putNextEntry(ZipEntry(entry))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            )
            put(
                "OEBPS/content.opf",
                """<package version="3.0"><manifest>
                   <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                   </manifest><spine><itemref idref="c1"/></spine></package>""",
            )
            put("OEBPS/ch1.xhtml", "<html><body><p>EPUB 正文内容。</p></body></html>")
        }
        return f
    }

    private fun docxFile(): File {
        val f = File(dir, "book.docx")
        ZipOutputStream(f.outputStream()).use { zos ->
            fun put(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put(
                "word/document.xml",
                """<w:document xmlns:w="x"><w:body>
                   <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>标题</w:t></w:r></w:p>
                   <w:p><w:r><w:t>DOCX 正文内容。</w:t></w:r></w:p>
                   </w:body></w:document>""",
            )
        }
        return f
    }

    private fun mhtmlFile(): File {
        val boundary = "----B"
        val body = buildString {
            append("Content-Type: multipart/related; boundary=\"$boundary\"\r\n\r\n")
            append("--$boundary\r\nContent-Type: image/png\r\nContent-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.getMimeEncoder().encodeToString(byteArrayOf(1, 2, 3)))
            append("\r\n--$boundary\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n")
            append("<p>MHTML 正文内容。</p>")
            append("\r\n--$boundary--\r\n")
        }
        return File(dir, "page.mhtml").apply { writeText(body, Charsets.UTF_8) }
    }

    private fun fb2File(): File {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <FictionBook><description><title-info><book-title>FB2 书名</book-title></title-info></description>
            <body><section><p>FB2 正文内容。</p></section></body></FictionBook>"""
        return File(dir, "book.fb2").apply { writeText(xml, Charsets.UTF_8) }
    }

    @Test
    fun `epub opens and paginates`() = assertReadable("EPUB", epubFile())

    @Test
    fun `txt opens and paginates`() =
        assertReadable("TXT", textFile("book.txt", "第一段正文。\n\n第二段正文。"))

    @Test
    fun `md opens and paginates`() =
        assertReadable("MD", textFile("book.md", "# 标题\n\n**粗体**段落正文。"))

    @Test
    fun `html opens and paginates`() =
        assertReadable("HTML", textFile("page.html", "<html><body><p>HTML 正文。</p></body></html>"))

    @Test
    fun `htm opens and paginates`() =
        assertReadable("HTM", textFile("page.htm", "<html><body><p>HTM 正文。</p></body></html>"))

    @Test
    fun `xhtml opens and paginates`() =
        assertReadable("XHTML", textFile("doc.xhtml", "<html><body><p>XHTML 正文。</p></body></html>"))

    @Test
    fun `xml opens and paginates`() =
        assertReadable("XML", textFile("doc.xml", "<html><body><p>XML 正文。</p></body></html>"))

    @Test
    fun `mhtml opens and paginates`() = assertReadable("MHTML", mhtmlFile())

    @Test
    fun `fb2 opens and paginates`() = assertReadable("FB2", fb2File())

    @Test
    fun `docx opens and paginates`() = assertReadable("DOCX", docxFile())

    @Test
    fun `mobi family is claimed by the factory and parsed by the mobi engine`() {
        // 真实 .mobi/.azw3 夹具属 engine/mobi（calibre 生成，已验证 74 项）；
        // 这里保证格式被认领、不会落到"无人负责"的兜底分支。
        for (ext in listOf("mobi", "azw3", "azw")) {
            assertTrue(ReaderSessionFactory.supports(ext), ext)
            assertEquals(
                IntentRoutePolicy.Route.NATIVE_SHELL,
                IntentRoutePolicy.decide(null, "book.$ext"),
                ext,
            )
        }
    }

    @Test
    fun `unparseable file yields null instead of crashing`() {
        val junk = File(dir, "junk.epub").apply { writeText("not a zip") }
        assertEquals(null, open("EPUB", junk))
        val junkDocx = File(dir, "junk.docx").apply { writeText("not a zip") }
        assertEquals(null, open("DOCX", junkDocx))
        assertEquals(null, open("UNKNOWN", junk))
    }
}
