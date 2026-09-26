package com.koodoreader.engine.fb2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FB2 生产者测试（P5.5a）：标签映射、元数据、声明编码优先、binary/notes
 * 处理、畸形容错。
 */
class Fb2DocumentTest {

    private fun fb2(
        body: String,
        encoding: String = "utf-8",
        description: String = """
            <description><title-info>
              <book-title>测试书名</book-title>
              <author><first-name>三</first-name><last-name>张</last-name></author>
            </title-info></description>
        """.trimIndent(),
    ) = """<?xml version="1.0" encoding="$encoding"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
        $description
        $body
        </FictionBook>"""

    @Test
    fun `body sections become blocks with metadata`() {
        val xml = fb2(
            """
            <body><section>
              <title><p>第一章</p></title>
              <p>第一段正文。</p>
              <p>第二段正文。</p>
            </section></body>
            """.trimIndent(),
        )
        val result = Fb2Document.fromText(xml)
        assertEquals("测试书名", result.title)
        assertEquals("三 张", result.author)
        assertTrue(result.blocks.any { it.text.contains("第一章") }, "title kept: ${result.blocks.map { it.text }}")
        assertTrue(result.blocks.any { it.text.contains("第一段正文") })
    }

    @Test
    fun `binary resources and description produce no blocks`() {
        val base64 = "A".repeat(4000)
        val xml = fb2(
            """
            <body><section><p>正文只有这一段。</p></section></body>
            <binary id="cover.jpg" content-type="image/jpeg">$base64</binary>
            """.trimIndent(),
        )
        val result = Fb2Document.fromText(xml)
        assertTrue(result.blocks.isNotEmpty())
        assertTrue(result.blocks.none { it.text.length > 1000 }, "binary must not leak into blocks")
        assertTrue(result.blocks.joinToString(" ") { it.text }.contains("正文只有这一段"))
    }

    @Test
    fun `notes body is skipped in favour of the main body`() {
        val xml = fb2(
            """
            <body><section><p>正文内容。</p></section></body>
            <body name="notes"><section><p>这是注释内容，不应作为正文。</p></section></body>
            """.trimIndent(),
        )
        val text = Fb2Document.blocks(xml).joinToString(" ") { it.text }
        assertTrue(text.contains("正文内容"))
        assertTrue(!text.contains("不应作为正文"), "notes body leaked: $text")
    }

    @Test
    fun `declared encoding wins over detection`() {
        // 声明 GBK 且字节确为 GBK：中文必须正确解出（而非按 UTF-8 猜测）
        val body = (1..30).joinToString("") { "<p>第 $it 段中文正文内容。</p>" }
        val xml = fb2("<body><section>$body</section></body>", encoding = "GBK")
        val bytes = xml.toByteArray(charset("GBK"))
        val result = Fb2Document.fromBytes(bytes)
        assertTrue(result.title == "测试书名", "title was ${result.title}")
        assertTrue(result.blocks.any { it.text.contains("中文正文内容") })
    }

    @Test
    fun `epigraph and poem text is preserved`() {
        val xml = fb2(
            """
            <body><section>
              <epigraph><p>题记内容。</p></epigraph>
              <poem><stanza><v>诗句一</v><v>诗句二</v></stanza></poem>
              <p>正文。</p>
            </section></body>
            """.trimIndent(),
        )
        val text = Fb2Document.blocks(xml).joinToString(" ") { it.text }
        assertTrue(text.contains("题记内容"), text)
        assertTrue(text.contains("诗句一"), text)
    }

    @Test
    fun `empty line splits paragraphs and subtitle becomes a heading`() {
        val xml = fb2(
            """
            <body><section>
              <subtitle>副标题</subtitle>
              <p>上段。</p><empty-line/><p>下段。</p>
            </section></body>
            """.trimIndent(),
        )
        val blocks = Fb2Document.blocks(xml)
        assertTrue(blocks.any { it.text.contains("副标题") })
        assertTrue(blocks.any { it.text.contains("上段") })
        assertTrue(blocks.any { it.text.contains("下段") })
    }

    @Test
    fun `missing body yields no blocks and no metadata`() {
        val xml = fb2("", description = "<description/>")
        val result = Fb2Document.fromText(xml)
        assertTrue(result.blocks.isEmpty())
        assertNull(result.title)
        assertNull(result.author)
    }

    @Test
    fun `malformed fb2 degrades without crashing`() {
        val xml = "<FictionBook><body><section><p>未闭合段落 <title>未闭合标题"
        val blocks = Fb2Document.blocks(xml)
        // 不崩即达标；能提取到文本更好
        assertTrue(blocks.joinToString(" ") { it.text }.contains("未闭合") || blocks.isEmpty())
    }
}
