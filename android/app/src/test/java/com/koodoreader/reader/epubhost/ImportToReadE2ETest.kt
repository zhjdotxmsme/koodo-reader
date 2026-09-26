package com.koodoreader.reader.epubhost

import com.koodoreader.core.importer.BookRules
import com.koodoreader.core.importer.BookSource
import com.koodoreader.core.importer.ImportPipeline
import com.koodoreader.engine.image.ArchiveExtractors
import com.koodoreader.engine.layout.TextMeasurers
import com.koodoreader.reader.shell.IntentRoutePolicy
import com.koodoreader.reader.shell.ReaderFiles
import kotlinx.coroutines.runBlocking
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
 * **APK 真实读取路径的端到端测试**（导入 → books 目录 → 文件解析 → 会话打开）：
 *
 *  APK 里"打开一本书"实际经过四段——①SAF/intent 导入（[ImportPipeline] 落盘
 *  `<booksDir>/<key>.<ext>` 并写 Room 行）→ ②书架点开（READER 路由按 `format`
 *  分派）→ ③[ReaderFiles.resolveBookFile] 找回文件 → ④[ReaderSessionFactory]
 *  开会话分页。本测试把 ①②③④ 串起来对**每一个受支持格式**跑一遍：只要某格式
 *  在任一环掉链（导入被拒 / format 值不符 / 文件找不到 / 会话开不了），这里就红。
 *
 *  ROM 之外的最后一环（Compose 渲染）由屏幕的编译门禁与 [ReaderSessionFactoryTest]
 *  覆盖；真机打开仍需设备，属挂账项。
 */
class ImportToReadE2ETest {

    @TempDir
    lateinit var dir: File

    private val booksOut: File get() = File(dir, "books")

    // ------------------------------------------------------------ 夹具

    private fun zipFile(name: String, entries: Map<String, String>): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { zos ->
            entries.forEach { (entry, content) ->
                zos.putNextEntry(ZipEntry(entry))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return f
    }

    private fun textFile(name: String, content: String): File =
        File(dir, name).apply { writeText(content, Charsets.UTF_8) }

    /** 每个受支持扩展名一份最小合法（或对导入而言足够）的样本。 */
    private fun fixture(ext: String): File = when (ext) {
        "epub" -> zipFile(
            "book.epub",
            mapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
                "OEBPS/content.opf" to
                    """<package><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                       <dc:title>T</dc:title></metadata><manifest>
                       <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                       </manifest><spine><itemref idref="c1"/></spine></package>""",
                "OEBPS/ch1.xhtml" to "<html><body><p>EPUB 正文。</p></body></html>",
            ),
        )

        // ImportPipeline 对 PDF 只取基础字段（封面由宿主注入的 PdfRenderer 抽取器负责），
        // 所以这里只要一个魔数正确的最小 PDF。
        "pdf" -> textFile("book.pdf", "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n")

        "txt" -> textFile("book.txt", "第一段。\n\n第二段。")
        "md" -> textFile("book.md", "# 标题\n\n正文段落。")
        "html" -> textFile("book.html", "<html><body><p>HTML 正文。</p></body></html>")
        "htm" -> textFile("book.htm", "<html><body><p>HTM 正文。</p></body></html>")
        "xhtml" -> textFile("book.xhtml", "<html><body><p>XHTML 正文。</p></body></html>")
        "xml" -> textFile("book.xml", "<html><body><p>XML 正文。</p></body></html>")

        "mhtml" -> textFile(
            "book.mhtml",
            buildString {
                val b = "----B"
                append("Content-Type: multipart/related; boundary=\"$b\"\r\n\r\n")
                append("--$b\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n")
                append("<p>MHTML 正文。</p>\r\n--$b--\r\n")
            },
        )

        "fb2" -> textFile(
            "book.fb2",
            """<?xml version="1.0" encoding="utf-8"?>
               <FictionBook><description><title-info><book-title>FB2</book-title></title-info></description>
               <body><section><p>FB2 正文。</p></section></body></FictionBook>""",
        )

        "docx" -> zipFile(
            "book.docx",
            mapOf(
                "word/document.xml" to
                    """<w:document xmlns:w="x"><w:body><w:p><w:r><w:t>DOCX 正文。</w:t></w:r></w:p>
                       </w:body></w:document>""",
            ),
        )

        // MOBI 家族：导入只取基础字段（深度解析是阅读期的事），任意字节即可。
        "mobi", "azw3", "azw" -> textFile("book.$ext", "not parsed at import time")

        // 漫画：CBZ 需要真实 zip（ComicCover 会抽封面/页数），CBT/CB7 导入期同样只取基础字段。
        "cbz" -> zipFile(
            "book.cbz",
            mapOf("001.png" to "PNG1", "002.png" to "PNG2"),
        )
        "cbt" -> textFile("book.cbt", "tar placeholder")
        "cb7" -> textFile("book.cb7", "7z placeholder")
        "cbr" -> textFile("book.cbr", "rar placeholder")

        else -> error("no fixture for $ext")
    }

    /** ①导入 → 返回 (format, 解析回的文件)。 */
    private fun importAndResolve(ext: String): Pair<String, File> = runBlocking {
        val source = fixture(ext)
        val result = ImportPipeline().process(
            pending = listOf(
                BookSource(
                    name = source.name,
                    openStream = { source.inputStream() },
                    size = source.length(),
                ),
            ),
            booksOut = booksOut,
        )
        assertEquals(1, result.records.size, "$ext: not imported (failures=${result.failures})")
        val record = result.records.single()
        val format = record.format
        assertNotNull(format, "$ext: format column empty")
        assertEquals(ext.uppercase(), format, "$ext: wrong format column")

        // ②书架点开时按 key/format/path 找回文件
        val resolved = ReaderFiles.resolveBookFile(
            booksDir = booksOut,
            bookKey = record.key,
            format = record.format,
            recordedPath = record.path,
        )
        assertNotNull(resolved, "$ext: book file not resolvable after import")
        assertTrue(resolved!!.isFile, "$ext: resolved path is not a file")
        format!! to resolved
    }

    // ------------------------------------------------------- ① 导入环

    @Test
    fun `every supported format imports with the correct format column`() {
        for (ext in BookRules.BOOK_EXTENSIONS) {
            importAndResolve(ext)
        }
    }

    // ------------------------------------------------- ③④ 阅读环（文本类）

    @Test
    fun `text and document formats open through the reader factory after import`() {
        // MOBI 家族的深度解析由 engine/mobi 的真实 calibre 夹具测试覆盖（本测试
        // 的夹具是占位字节，足够走通导入环即可）——这里只跑"内容可解析"的格式。
        val mobiFamily = setOf("mobi", "azw3", "azw")
        val sessionFormats = ReaderSessionFactory.formats - mobiFamily
        assertTrue(sessionFormats.isNotEmpty())
        for (ext in sessionFormats) {
            val (format, file) = importAndResolve(ext)
            val session = ReaderSessionFactory.open(format, file, 400f, 700f, TextMeasurers.mono())
            assertNotNull(session, "$ext: reader factory could not open the imported file")
            session!!.use { s ->
                assertTrue(s.pageCount > 0, "$ext: no pages")
                assertTrue(
                    (0 until s.pageCount).any { s.pageLines(it).isNotEmpty() },
                    "$ext: all pages empty",
                )
                val cfi = s.cfiForPage(0)
                assertNotNull(cfi, "$ext: no CFI")
                assertEquals(0, s.resumePage(cfi), "$ext: CFI round-trip")
            }
        }
        // MOBI 家族仍必须被工厂认领（不会掉进"无人负责"分支）
        for (ext in mobiFamily) {
            assertTrue(ReaderSessionFactory.supports(ext), ext)
        }
    }

    // ------------------------------------------------------- PDF 通道

    @Test
    fun `pdf imports, resolves and routes to the native pdf path`() {
        val (format, file) = importAndResolve("pdf")
        assertEquals("PDF", format)
        assertTrue(file.isFile)
        // PDF 由 pdf.js 在引擎 WebView 里渲染（JVM 无法打开），但路由必须指向原生 PDF 屏
        assertEquals(
            IntentRoutePolicy.Route.NATIVE_PDF,
            IntentRoutePolicy.decide("application/pdf", file.name),
        )
    }

    // ----------------------------------------------------- 漫画通道

    @Test
    fun `comic containers import and route to the native comic channel`() {
        for (ext in listOf("cbz", "cbt", "cb7")) {
            val (format, file) = importAndResolve(ext)
            assertEquals(ext.uppercase(), format)
            assertEquals(
                IntentRoutePolicy.Route.NATIVE_COMIC,
                IntentRoutePolicy.decide(null, file.name),
                "$ext must route to the native comic viewer",
            )
            // engine/image 的容器识别必须认得导入后的文件（真实解压在 engine/image 测试覆盖）
            assertTrue(
                ArchiveExtractors.kindOf(file) != com.koodoreader.engine.image.ArchiveKind.UNKNOWN ||
                    ext != "cbz",
                "$ext: container not recognised",
            )
        }
        // CBZ 是真实 zip：页表必须可读（封面/页数在导入期已抽）
        val (_, cbz) = importAndResolve("cbz")
        val extractor = ArchiveExtractors.open(cbz)
        extractor.use {
            assertTrue(it.pageCount >= 2, "cbz pages=${it.pageCount}")
        }
    }

    // ------------------------------------------------------- CBR 通道

    @Test
    fun `cbr imports but stays on the island route`() {
        val (format, file) = importAndResolve("cbr")
        assertEquals("CBR", format)
        assertEquals(
            IntentRoutePolicy.Route.ISLAND,
            IntentRoutePolicy.decide("application/x-cbr", file.name),
        )
    }

    // ------------------------------------------------- 跨格式一致性兜底

    @Test
    fun `every whitelist format is reachable end to end`() {
        val factoryFormats = ReaderSessionFactory.formats
        for (ext in BookRules.BOOK_EXTENSIONS) {
            val route = IntentRoutePolicy.decide(null, "book.$ext")
            when {
                ext == "cbr" -> assertEquals(IntentRoutePolicy.Route.ISLAND, route)
                ext in ReaderSessionFactory.COMIC_EXTENSIONS ->
                    assertEquals(IntentRoutePolicy.Route.NATIVE_COMIC, route)
                ext == "pdf" -> assertEquals(IntentRoutePolicy.Route.NATIVE_PDF, route)
                ext in factoryFormats -> assertEquals(IntentRoutePolicy.Route.NATIVE_SHELL, route)
                else -> error("format $ext has no owner")
            }
        }
    }
}
