package com.koodoreader.engine.htmlbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * MHTML 解析测试（P5.5c）：MIME 拆包、传输编码（quoted-printable / base64）、
 * charset、缺 html part / 缺 boundary 的类型化错误。
 */
class MhtmlDocumentTest {

    private fun mhtml(
        htmlEncoding: String = "quoted-printable",
        htmlPart: String,
        charset: String = "UTF-8",
    ): ByteArray {
        val boundary = "----=_NextPart_000_0000"
        val body = buildString {
            append("From: <Saved by Example>\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: multipart/related; boundary=\"$boundary\"\r\n")
            append("\r\n")
            // 第一个 part：图片（base64，验证我们不会误取）
            append("--$boundary\r\n")
            append("Content-Type: image/png\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
            append(Base64.getMimeEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
            append("\r\n")
            // 第二个 part：HTML 正文
            append("--$boundary\r\n")
            append("Content-Type: text/html; charset=$charset\r\n")
            append("Content-Transfer-Encoding: $htmlEncoding\r\n")
            append("\r\n")
            append(htmlPart)
            append("\r\n")
            append("--$boundary--\r\n")
        }
        return body.toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun `quoted-printable html part is decoded and flattened`() {
        val bytes = mhtml(
            htmlPart = "<html><body><p>Hello MHTML world.</p></body></html>",
        )
        val blocks = MhtmlDocument.blocks(bytes)
        assertTrue(blocks.any { it.text.contains("Hello MHTML world") })
    }

    @Test
    fun `base64 html part is decoded`() {
        val html = "<html><body><p>Base64 body text.</p></body></html>"
        val encoded = Base64.getMimeEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
        val bytes = mhtml(htmlEncoding = "base64", htmlPart = encoded)
        assertTrue(MhtmlDocument.blocks(bytes).any { it.text.contains("Base64 body text") })
    }

    @Test
    fun `html part charset is honoured`() {
        val html = "<p>中文编码内容。</p>"
        val gbk = html.toByteArray(charset("GBK"))
        // 把 GBK 字节按 QP 十六进制转义写进 part，并标注 charset=gbk
        val qp = gbk.joinToString("") { "=%02X".format(it.toInt() and 0xFF) }
        val bytes = mhtml(htmlPart = qp, charset = "gbk")
        val text = MhtmlDocument.html(bytes)
        assertTrue(text.contains("中文编码内容"), "decoded: $text")
    }

    @Test
    fun `parts exposes every mime part with headers`() {
        val bytes = mhtml(htmlPart = "<p>x</p>")
        val parts = MhtmlDocument.parts(bytes)
        assertEquals(2, parts.size)
        assertTrue(parts[0].contentType.startsWith("image/png"))
        assertTrue(parts[1].contentType.startsWith("text/html"))
        assertEquals(4, parts[0].body.size) // base64 PNG 魔数已解出
    }

    @Test
    fun `missing html part returns null and html throws`() {
        val noHtml = """
            Content-Type: multipart/related; boundary="B"
            --B
            Content-Type: image/png
            Content-Transfer-Encoding: base64
            aGk=
            --B--
        """.trimIndent().toByteArray(Charsets.ISO_8859_1)
        assertNull(MhtmlDocument.htmlPart(noHtml))
        assertThrows(MhtmlDocument.MhtmlFormatException::class.java) { MhtmlDocument.html(noHtml) }
    }

    @Test
    fun `missing boundary is a typed error`() {
        val junk = "not a multipart document at all".toByteArray()
        assertThrows(MhtmlDocument.MhtmlFormatException::class.java) { MhtmlDocument.html(junk) }
    }

    @Test
    fun `quoted-printable soft line breaks are removed`() {
        val decoded = String(MhtmlDocument.decodeQuotedPrintable("Hello=\r\nWorld"), Charsets.ISO_8859_1)
        assertEquals("HelloWorld", decoded)
    }

    @Test
    fun `folded and multipart LFLF headers parse`() {
        // LF-only 行尾 + 折行头部（非标准但真实世界常见）
        val raw = (
            "Content-Type: multipart/related;\n boundary=\"XYZ\"\n\n" +
                "--XYZ\nContent-Type: text/html\n\n<p>lf only</p>\n--XYZ--\n"
            ).toByteArray(Charsets.ISO_8859_1)
        assertTrue(MhtmlDocument.blocks(raw).any { it.text.contains("lf only") })
    }
}
