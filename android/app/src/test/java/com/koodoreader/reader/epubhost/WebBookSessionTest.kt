package com.koodoreader.reader.epubhost

import com.koodoreader.engine.layout.TextMeasurers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64

/**
 * HTML / XHTML / XML / MHTML 会话测试（P5.5b/c 接入）：
 * :engine:htmlbook 产出块 → PagedDocumentSession 分页 → CFI 往返。
 */
class WebBookSessionTest {

    @TempDir
    lateinit var dir: File

    private fun open(f: File): WebBookSession =
        WebBookSession.open(f, 400f, 700f, TextMeasurers.mono())

    @Test
    fun `html file is paginated with title as chapter label`() {
        val body = (1..30).joinToString("") { "<p>段落 $it 的正文内容。</p>" }
        val f = File(dir, "page.html").apply {
            writeText("<html><head><title>我的网页</title></head><body>$body</body></html>", Charsets.UTF_8)
        }
        open(f).use { s ->
            assertTrue(s.pageCount > 1, "expected multiple pages, got ${s.pageCount}")
            assertEquals("我的网页", s.chapterLabel(0))
            assertTrue(s.pageLines(0).any { it.text.contains("段落") })
        }
    }

    @Test
    fun `xhtml and xml are handled by the same path`() {
        val f = File(dir, "doc.xhtml").apply {
            writeText(
                "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\">" +
                    "<body><p>XHTML content paragraph.</p></body></html>",
                Charsets.UTF_8,
            )
        }
        open(f).use { s ->
            assertTrue(s.pageLines(0).any { it.text.contains("XHTML content") })
        }
    }

    @Test
    fun `mhtml file is unwrapped and paginated`() {
        val boundary = "----BOUNDARY"
        val mhtml = buildString {
            append("Content-Type: multipart/related; boundary=\"$boundary\"\r\n\r\n")
            append("--$boundary\r\nContent-Type: image/png\r\nContent-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.getMimeEncoder().encodeToString(byteArrayOf(1, 2, 3)))
            append("\r\n--$boundary\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n")
            append("<html><body><p>MHTML 正文内容。</p></body></html>")
            append("\r\n--$boundary--\r\n")
        }
        // MHTML 的 html part 声明 charset=UTF-8，字节就该是 UTF-8。
        val f = File(dir, "saved.mhtml").apply { writeText(mhtml, Charsets.UTF_8) }
        open(f).use { s ->
            assertTrue(s.pageLines(0).any { it.text.contains("MHTML 正文内容") })
            assertEquals("saved", s.chapterLabel(0)) // 文件名回退（MHTML 无 <title>）
        }
    }

    @Test
    fun `cfi round-trips for web documents`() {
        val body = (1..60).joinToString("") { "<p>Line $it text.</p>" }
        val f = File(dir, "cfi.html").apply { writeText("<html><body>$body</body></html>") }
        open(f).use { s ->
            for (p in 0 until s.pageCount) {
                val cfi = s.cfiForPage(p) ?: continue
                assertEquals(p, s.pageForCfi(cfi), "page $p")
            }
        }
    }

    @Test
    fun `extension support table`() {
        for (name in listOf("a.html", "b.HTM", "c.xhtml", "d.xml", "e.mhtml", "f.mht")) {
            assertTrue(WebBookSession.supportsExtension(name), name)
        }
        assertTrue(!WebBookSession.supportsExtension("g.epub"))
        assertTrue(!WebBookSession.supportsExtension("h.mobi"))
    }
}
