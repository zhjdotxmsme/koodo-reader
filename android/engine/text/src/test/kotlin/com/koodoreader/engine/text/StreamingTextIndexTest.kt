package com.koodoreader.engine.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class StreamingTextIndexTest {

    private class RecordingSource(
        bytes: ByteArray,
        private val forbidReadAll: Boolean = false,
    ) : TextSource {
        private val delegate = ByteArrayTextSource(bytes)
        var bytesRead = 0L
        var readAllCalled = false

        override val length: Long get() = delegate.length

        override fun read(offset: Long, len: Int): ByteArray {
            val result = delegate.read(offset, len)
            bytesRead += result.size
            return result
        }

        override fun readAll(): ByteArray {
            readAllCalled = true
            if (forbidReadAll) throw AssertionError("readAll() must not be called")
            return delegate.readAll()
        }
    }

    private fun openIndex(
        source: RecordingSource,
        charset: String = "UTF-8",
        options: StreamingTextIndex.Options = StreamingTextIndex.Options.Default,
    ): TextChapterIndex =
        StreamingTextIndex(source, CharsetGuess(charset, 1.0), options).buildIndex()

    @Test
    fun `loading one chapter reads only its byte range`() {
        val text = "Preface\n\n第一章 开始\n正文A\n\n第二章 后续\n正文B"
        val source = RecordingSource(text.toByteArray(StandardCharsets.UTF_8), forbidReadAll = true)
        val index = openIndex(source)
        val loader = TextChapterLoader.open(source, index)
        // preface + 第一章 + 第二章（includePreface 默认开启）
        assertEquals(3, index.spans.size)

        source.bytesRead = 0
        val chapter = loader.load(loader.descriptors().last().id)

        assertTrue(source.bytesRead < source.length)
        assertFalse(source.readAllCalled)
        assertTrue(chapter.blocks.any { it.text.contains("正文B") })
    }

    @Test
    fun `gbk gb2312 and big5 chapters decode correctly`() {
        val text = "第一章\n正文甲\n\n第二章\n正文乙"
        for (name in listOf("GBK", "GB2312", "Big5")) {
            val source = RecordingSource(text.toByteArray(Charset.forName(name)))
            val index = openIndex(source, charset = name)
            assertEquals(2, index.spans.size, name)
            val loader = TextChapterLoader.open(source, index)
            val chapter = loader.load("c1")
            assertTrue(chapter.blocks.single().text.contains("正文乙"), name)
        }
    }

    @Test
    fun `utf8 sequences split across scan windows still decode`() {
        // Tiny windows force 3-byte CJK sequences to straddle scan boundaries.
        val options = StreamingTextIndex.Options(windowBytes = 7, pendingLineBytes = 64)
        val text = "第一章\n" + "汉".repeat(50) + "\n第二章\n" + "字".repeat(50)
        val source = RecordingSource(text.toByteArray(StandardCharsets.UTF_8), forbidReadAll = true)
        val index = openIndex(source, options = options)
        assertEquals(2, index.spans.size)
        val loader = TextChapterLoader.open(source, index)

        val first = loader.load("c0").blocks.single().text
        val second = loader.load("c1").blocks.single().text
        assertTrue(first.contains("汉".repeat(50)))
        assertTrue(second.contains("字".repeat(50)))
        assertFalse(first.contains(0xFFFD.toChar()))
        assertFalse(second.contains(0xFFFD.toChar()))
    }

    @Test
    fun `no title text is chunked into 5000 char spans without readAll`() {
        val text = "汉".repeat(12_000)
        val source = RecordingSource(text.toByteArray(StandardCharsets.UTF_8), forbidReadAll = true)
        val index = openIndex(source)

        assertEquals(3, index.spans.size)
        assertTrue(index.spans.all { it.synthesized })
        assertFalse(source.readAllCalled)

        val loader = TextChapterLoader.open(source, index)
        val joined = index.spans.joinToString("") { span ->
            loader.load(span.id).blocks.single().text
        }
        assertEquals(text, joined)
    }

    @Test
    fun `markdown headings become chapters with levels`() {
        val text = "# 第一章\n正文A\n\n## 第二节\n正文B"
        val source = RecordingSource(text.toByteArray(StandardCharsets.UTF_8), forbidReadAll = true)
        val index = openIndex(source)

        assertEquals(2, index.spans.size)
        assertEquals("第一章", index.spans[0].title)
        assertEquals(1, index.spans[0].level)
        assertEquals("第二节", index.spans[1].title)
        assertEquals(2, index.spans[1].level)
    }

    @Test
    fun `truncated utf8 tail does not crash`() {
        val bytes = "第一章\n正文".toByteArray(StandardCharsets.UTF_8)
        val source = RecordingSource(bytes.copyOf(bytes.size - 1))
        val index = openIndex(source)
        val loader = TextChapterLoader.open(source, index)

        val chapter = loader.load(index.spans.last().id)
        assertTrue(chapter.blocks.isNotEmpty())
    }

    @Test
    fun `single character book still indexes`() {
        val source = RecordingSource("A".toByteArray(StandardCharsets.UTF_8))
        val index = openIndex(source)
        assertEquals(1, index.spans.size)
        assertEquals(1, index.spans.single().charCount)
    }
}
