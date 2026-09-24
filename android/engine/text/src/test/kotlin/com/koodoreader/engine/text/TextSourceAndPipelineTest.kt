package com.koodoreader.engine.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/** [TextSource] windowed reading, and the end-to-end bytes → chapters pipeline. */
class TextSourceAndPipelineTest {

    private val ascii = java.nio.charset.StandardCharsets.US_ASCII
    private val utf8 = java.nio.charset.StandardCharsets.UTF_8

    @Test
    @DisplayName("ByteArrayTextSource clamps every out-of-range read instead of throwing")
    fun byteArraySourceClamps() {
        val source = ByteArrayTextSource("0123456789".toByteArray(ascii))
        assertEquals(10L, source.length)
        assertEquals("234", String(source.read(2, 3), ascii))
        assertEquals("89", String(source.read(8, 100), ascii))
        assertEquals(0, source.read(10, 5).size)
        assertEquals(0, source.read(-1, 5).size)
        assertEquals(0, source.read(0, 0).size)
        assertEquals("0123456789", String(source.readAll(), ascii))
    }

    @Test
    @DisplayName("A windowed ByteArrayTextSource sees only its slice")
    fun byteArraySourceWindow() {
        val backing = "HEADER|payload-of-the-book|TRAILER".toByteArray(ascii)
        val payload = ByteArrayTextSource(backing, baseOffset = 7, size = 19)
        assertEquals(19L, payload.length)
        assertEquals("payload-of-the-book", String(payload.readAll(), ascii))
    }

    @Test
    @DisplayName("FileTextSource reads windows from disk and survives random access")
    fun fileSourceReadsWindows() {
        val tmp = File.createTempFile("koodo-textsource", ".txt")
        try {
            val body = "第一章 甲\n内容\n".repeat(400)
            tmp.writeBytes(body.toByteArray(utf8))
            FileTextSource(tmp).use { source ->
                assertEquals(body.toByteArray(utf8).size.toLong(), source.length)
                assertEquals(body, String(source.readAll(), utf8))
                // A window in the middle decodes to the matching slice.
                val offset = 500L
                val window = source.read(offset, TextSource.DEFAULT_WINDOW)
                assertEquals(
                    String(body.toByteArray(utf8), offset.toInt(), window.size, utf8),
                    String(window, utf8),
                )
                assertEquals(0, source.read(source.length, 10).size)
            }
        } finally {
            tmp.delete()
        }
    }

    @Test
    @DisplayName("Detecting from a TextSource only reads the probe window")
    fun detectFromSource() {
        val text = "第一章 风起\n他走进屋子，看见桌上放着一封信。\n".repeat(10)
        val bytes = text.toByteArray(java.nio.charset.Charset.forName("GBK"))
        val source = ByteArrayTextSource(bytes)
        val guess = CharsetDetector.detect(source, probe = 120)
        assertEquals(Charsets.GBK, guess.charset, "guess=$guess")
    }

    @Test
    @DisplayName("End to end: GBK bytes → detect → decode → normalise → chapters")
    fun fullPipelineGbk() {
        // Long enough that the language prior has real evidence (see
        // CharsetDetector.MIN_LEGACY_PAIRS).
        val raw = ("第一章 甲\r\n内容一\r\n\r\n第二章 乙\r\n内容二\r\n").repeat(6)
        val bytes = raw.toByteArray(java.nio.charset.Charset.forName("GBK"))

        val guess = CharsetDetector.detect(bytes)
        assertEquals(Charsets.GBK, guess.charset, "guess=$guess")

        val decoded = TextDecoder.decode(bytes, guess)
        val text = TextNormalizer.normalize(decoded, TextNormalizer.Options.Paragraphs)
        assertFalse(text.contains('\r'))

        val chapters = ChapterSplitter.split(text)
        assertEquals(12, chapters.size)
        assertEquals(listOf("第一章 甲", "第二章 乙"), chapters.take(2).map { it.title })
        assertTrue(chapters[0].extract(text).startsWith("第一章 甲\n内容一"))
        assertEquals(text.length, chapters.last().endOffset)
    }

    @Test
    @DisplayName("End to end: UTF-8 BOM Markdown bytes → decode → render")
    fun fullPipelineMarkdown() {
        val raw = "\uFEFF# 标题\n\n正文 **粗体**\n"
        val bytes = raw.toByteArray(utf8)
        val guess = CharsetDetector.detect(bytes)
        assertEquals(Charsets.UTF_8, guess.charset)
        assertEquals(3, guess.bomLength)

        val text = TextNormalizer.normalize(TextDecoder.decode(bytes, guess))
        assertEquals("# 标题\n\n正文 **粗体**\n", text)
        assertEquals(
            "<h1>标题</h1>\n<p>正文 <strong>粗体</strong></p>\n",
            MarkdownRenderer.render(text),
        )
    }

    @Test
    @DisplayName("A 4 KiB probe is enough for a large file, and the windowed read stays cheap")
    fun largeFileProbe() {
        val head = "第一章 简体中文\n他走进屋子，看见桌上放着一封信。\n".toByteArray(
            java.nio.charset.Charset.forName("GBK"),
        )
        val tail = ByteArray(256 * 1024) { 'a'.code.toByte() }
        val bytes = head + tail
        val source = ByteArrayTextSource(bytes)
        // Only the probe is materialised; the verdict comes from the head.
        assertEquals(Charsets.GBK, CharsetDetector.detect(source).charset)
    }
}
