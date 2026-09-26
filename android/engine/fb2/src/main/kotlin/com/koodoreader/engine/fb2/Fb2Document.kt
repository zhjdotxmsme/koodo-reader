package com.koodoreader.engine.fb2

import com.koodoreader.engine.htmlbook.HtmlDocument
import com.koodoreader.engine.layout.HtmlEntities
import com.koodoreader.engine.layout.TextBlock

/**
 * FictionBook 2 (`.fb2`) → [TextBlock]（P5.5a，ADR-006）。
 *
 * FB2 是 XML：`<description>`（元数据）+ `<body>`（正文，可多体）+ `<binary>`
 * （base64 资源）。原生侧只做「FB2 标签集 → HTML 等价物」的转换，扁平化
 * 复用 [HtmlDocument]（D0 容错 tokenizer + CFI 兄弟序保真），因此不需要
 * XML 库。
 *
 * 标签映射：
 *  - `<title>`   → `<h1>`（FB2 的章节标题；注意不能原样喂给扁平化器：HTML
 *    语义里 `<title>` 属隐藏内容，会被整体丢弃）；
 *  - `<subtitle>`→ `<h2>`；
 *  - `<p>` / `<section>` 原样保留（扁平化器认识）；
 *  - `<epigraph> <cite> <poem> <stanza> <v> <text-author>` 去掉标签保留文本；
 *  - `<empty-line/>` → 段落分隔；`<image>` / `<binary>` / `<description>` 丢弃。
 *
 * 编码：FB2 常见 `windows-1251` / `koi8-r` / UTF-8，按 `:engine:text` 的
 * 探测 + `encoding=` 声明解码。
 */
object Fb2Document {

    /** 转换结果：块 + 元数据（进度条/书架显示用）。 */
    data class Result(
        val blocks: List<TextBlock>,
        val title: String?,
        val author: String?,
    )

    /** 原始字节 → 结果（先按声明/探测解码，再转换 + 扁平化）。 */
    fun fromBytes(bytes: ByteArray, spineIndex: Int = 1): Result {
        val xml = decode(bytes)
        return fromText(xml, spineIndex)
    }

    /** 已解码的 FB2 XML → 结果。 */
    fun fromText(xml: String, spineIndex: Int = 1): Result {
        val title = firstTagText(xml, "book-title")
        val author = authorOf(xml)
        return Result(
            blocks = HtmlDocument.blocks(toHtml(xml), spineIndex),
            title = title,
            author = author,
        )
    }

    /** 只取块（调用方不需要元数据时）。 */
    fun blocks(xml: String, spineIndex: Int = 1): List<TextBlock> = fromText(xml, spineIndex).blocks

    // ------------------------------------------------------------- internals

    /**
     * 编码：优先 XML 声明的 `encoding="..."`（FB2 规范要求存在），否则用
     * `:engine:text` 的探测（UTF-8 / 1251 / koi8-r 等）。
     */
    internal fun decode(bytes: ByteArray): String {
        val declared = Regex("""encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(String(bytes.copyOf(minOf(bytes.size, 256)), Charsets.ISO_8859_1))
            ?.groupValues?.get(1)
        if (declared != null) {
            val decoded = runCatching {
                com.koodoreader.engine.text.TextDecoder.decodeWith(declared, bytes)
            }.getOrNull()
            if (!decoded.isNullOrEmpty()) return decoded
        }
        return com.koodoreader.engine.text.TextDecoder.decode(bytes)
    }

    /** FB2 → HTML 等价物（正文体）。 */
    internal fun toHtml(xml: String): String {
        var s = xml
        // 资源与元数据先在原始文本上剥离（binary 是 base64 大块）。
        s = s.replace(Regex("(?is)<binary\\b[^>]*>.*?</binary\\s*>"), "")
        s = s.replace(Regex("(?is)<binary\\b[^>]*/>"), "")
        s = s.replace(Regex("(?is)<description\\b[^>]*>.*?</description\\s*>"), "")

        // 正文：第一个非 notes 的 <body>。
        val body = Regex("(?is)<body\\b([^>]*)>(.*?)</body\\s*>").findAll(s)
            .firstOrNull { !it.groupValues[1].contains("notes", ignoreCase = true) }
            ?.groupValues?.get(2)
            ?: return ""

        var html = body
        // 标题类：FB2 <title> → HTML 标题（<title> 在扁平化器里是隐藏内容）。
        html = html.replace(Regex("(?is)<title\\b[^>]*>"), "<h1>")
            .replace(Regex("(?is)</title\\s*>"), "</h1>")
        html = html.replace(Regex("(?is)<subtitle\\b[^>]*>"), "<h2>")
            .replace(Regex("(?is)</subtitle\\s*>"), "</h2>")
        // 论外容器：去标签留文本（其内容会被后续 <p> 承载）。
        for (tag in listOf("epigraph", "cite", "poem", "stanza", "text-author", "v", "annotation")) {
            html = html.replace(Regex("(?is)</?$tag\\b[^>]*>"), "")
        }
        // 空行 = 段落分隔；图片丢弃（纯文本阅读不需要，资源在 binary 里）。
        html = html.replace(Regex("(?is)<empty-line\\s*/?>"), "<p></p>")
        html = html.replace(Regex("(?is)<image\\b[^>]*/?>"), "")
        // 其余未知标签保留：HtmlFlattener 的容错路径会把未知标签当容器处理
        // （内容保留、标签丢弃），这正是我们要的降级行为。

        return "<html><body>$html</body></html>"
    }

    private fun firstTagText(xml: String, tag: String): String? =
        Regex("(?is)<$tag\\b[^>]*>(.*?)</$tag\\s*>")
            .find(xml)
            ?.groupValues?.get(1)
            ?.let { stripTags(it) }
            ?.let { HtmlEntities.decode(it) }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** `<author>` 的姓名（first-name/middle-name/last-name 拼装）。 */
    internal fun authorOf(xml: String): String? {
        val block = Regex("(?is)<author\\b[^>]*>(.*?)</author\\s*>").find(xml)?.groupValues?.get(1)
            ?: return null
        val parts = listOf("first-name", "middle-name", "last-name")
            .mapNotNull { firstTagText(block, it) }
        return parts.joinToString(" ").trim().takeIf { it.isNotEmpty() }
    }

    private fun stripTags(s: String): String = s.replace(Regex("(?s)<[^>]*>"), "")
}
