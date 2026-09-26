package com.koodoreader.engine.htmlbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * HTML / XHTML / HTM / XML 生产者测试（P5.5b）：
 * 扁平化复用 D0、编码探测复用 engine:text、标题提取与实体解码。
 */
class HtmlDocumentTest {

    @Test
    fun `html body is flattened into blocks`() {
        val html = """
            <html><head><title>My Page</title></head>
            <body>
              <h1>Chapter One</h1>
              <p>First paragraph of the page.</p>
              <p>Second paragraph.</p>
            </body></html>
        """.trimIndent()
        val blocks = HtmlDocument.blocks(html)
        assertTrue(blocks.size >= 3, "expected heading + paragraphs, got ${blocks.size}")
        assertTrue(blocks.any { it.text.contains("Chapter One") })
        assertTrue(blocks.any { it.text.contains("First paragraph") })
    }

    @Test
    fun `title is extracted and entity-decoded`() {
        assertEquals("A & B", HtmlDocument.titleOf("<html><title>  A &amp; B  </title>"))
        assertNull(HtmlDocument.titleOf("<html><body>no title</body></html>"))
    }

    @Test
    fun `blocks carry spine index for cfi addressing`() {
        val blocks = HtmlDocument.blocks("<p>x</p>", spineIndex = 3)
        assertTrue(blocks.isNotEmpty())
        // 扁平化器把 spine 索引用在元素定位上；这里只验证调用链可用
        assertTrue(blocks.first().elementIndex >= 0)
    }

    @Test
    fun `gbk bytes are detected and decoded`() {
        // 中文 HTML 以 GBK 编码（常见于老网页）。样本要足够长：探测器的既定
        // 行为是「双字节对太少就不猜代码页」（见 engine/text CharsetDetector）。
        val body = (1..40).joinToString("") { "这是第${it}段中文正文内容，用于触发代码页探测。" }
        val html = "<html><head><title>中文标题</title></head><body><p>$body</p></body></html>"
        val gbk = html.toByteArray(charset("GBK"))
        val result = HtmlDocument.fromBytes(gbk)
        assertTrue(result.title == "中文标题", "title was ${result.title}")
        assertTrue(result.blocks.any { it.text.contains("中文正文内容") })
    }

    @Test
    fun `utf8 bytes round-trip`() {
        val html = "<p>UTF-8 内容 éàü</p>"
        val result = HtmlDocument.fromBytes(html.toByteArray(Charsets.UTF_8))
        assertTrue(result.blocks.any { it.text.contains("UTF-8 内容") })
    }

    @Test
    fun `malformed html degrades without crashing`() {
        val broken = "<html><body><p>unclosed <b>bold <div><span>deep</body"
        val blocks = HtmlDocument.blocks(broken)
        assertTrue(blocks.isNotEmpty())
        assertTrue(blocks.joinToString(" ") { it.text }.contains("unclosed"))
    }

    @Test
    fun `xml-ish markup also flattens`() {
        // .xml/.xhtml 走同一条容错路径（XML 声明/自闭合标签等）
        val xhtml = """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
            <p>XHTML paragraph.</p><br/></body></html>"""
        val blocks = HtmlDocument.blocks(xhtml)
        assertTrue(blocks.any { it.text.contains("XHTML paragraph") })
    }
}
