package com.koodoreader.engine.docx

import com.koodoreader.core.archive.ZipArchives
import com.koodoreader.engine.htmlbook.HtmlDocument
import com.koodoreader.engine.layout.HtmlEntities
import com.koodoreader.engine.layout.TextBlock
import java.io.File

/**
 * DOCX (WordprocessingML) → [TextBlock]（P5.5d，ADR-006）。
 *
 * DOCX 是 OOXML zip：正文在 `word/document.xml`，标题元数据在
 * `docProps/core.xml`。zip 读取走 `:core:archive` 的 [ZipArchives]（R1：
 * 唯一门面，zip-slip/句柄语义统一），WordprocessingML 子集用正则扫描
 * （段落 / 文本 run / 制表 / 换行 / 标题样式 / 表格内段落），再交给
 * [HtmlDocument] 扁平化——与 EPUB/FB2/HTML 同一条管线。
 *
 * 覆盖范围（ADR-006 的"子集"口径）：段落与标题、文本 run 合并、`<w:tab/>`
 * 与 `<w:br/>`、表格单元格内的段落（表格内也是 `<w:p>`，自然拍平）、
 * 核心属性标题。**不覆盖**：图片/公式/脚注/批注/样式表继承（纯文本阅读
 * 不需要；后续要覆盖时按 ADR 回滚方案换 vendored mammoth）。
 */
object DocxDocument {

    /** 转换结果：块 + 标题（进度条/书架显示用）。 */
    data class Result(val blocks: List<TextBlock>, val title: String?)

    /** 从 .docx 文件读取（zip → document.xml / core.xml → 扁平化）。 */
    fun fromFile(file: File, spineIndex: Int = 1): Result {
        ZipArchives.open(file).use { archive ->
            val docEntry = archive.entry("word/document.xml")
                ?: archive.entry("word/document.xml".lowercase())
                ?: throw IllegalArgumentException(
                    "DOCX without word/document.xml: ${file.name}",
                )
            val xml = archive.readBytes(docEntry.name).toString(Charsets.UTF_8)
            val title = archive.entry("docProps/core.xml")
                ?.let { archive.readBytes(it.name).toString(Charsets.UTF_8) }
                ?.let { coreTitleOf(it) }
            return Result(blocks = HtmlDocument.blocks(toHtml(xml), spineIndex), title = title)
        }
    }

    /** 已解码的 document.xml → 结果（测试与导入期转换共用）。 */
    fun fromXml(documentXml: String, spineIndex: Int = 1): Result =
        Result(blocks = HtmlDocument.blocks(toHtml(documentXml), spineIndex), title = null)

    /** document.xml → HTML 等价物（`<hN>` / `<p>` / 表格拍平为段落）。 */
    internal fun toHtml(documentXml: String): String {
        val body = Regex("(?is)<w:body\\b[^>]*>(.*?)</w:body\\s*>")
            .find(documentXml)?.groupValues?.get(1)
            ?: documentXml // 容错：没有 w:body 就整体扫

        val out = StringBuilder("<html><body>")
        // 逐段落：<w:p …>…</w:p>（表格单元格内的 w:p 同样被捕获 → 自然拍平）。
        for (m in Regex("(?is)<w:p\\b[^>]*>(.*?)</w:p\\s*>").findAll(body)) {
            val paragraph = m.groupValues[1]
            val text = paragraphText(paragraph)
            if (text.isEmpty()) continue
            val level = headingLevel(paragraph)
            if (level != null) {
                out.append("<h").append(level).append('>')
                    .append(escape(text)).append("</h").append(level).append('>')
            } else {
                out.append("<p>").append(escape(text)).append("</p>")
            }
        }
        out.append("</body></html>")
        return out.toString()
    }

    /** 段落文本：合并 `<w:t>` 内容，`<w:tab/>`→制表符、`<w:br/>`→空格。 */
    internal fun paragraphText(paragraphXml: String): String {
        val sb = StringBuilder()
        // 按出现顺序扫 <w:t> / <w:tab/> / <w:br/>，保持 run 内顺序。
        val token = Regex("""(?is)<w:t\b[^>]*>(.*?)</w:t\s*>|<w:tab\b[^>]*/?>|<w:br\b[^>]*/?>""")
        for (m in token.findAll(paragraphXml)) {
            when {
                m.groupValues[1].isNotEmpty() || m.value.startsWith("<w:t", ignoreCase = true) ->
                    sb.append(HtmlEntities.decode(m.groupValues[1]))
                m.value.startsWith("<w:tab", ignoreCase = true) -> sb.append('\t')
                m.value.startsWith("<w:br", ignoreCase = true) -> sb.append(' ')
            }
        }
        return sb.toString().trim()
    }

    /** 标题级别：`pStyle` 里的 HeadingN，或 `outlineLvl`；普通段落为 null。 */
    internal fun headingLevel(paragraphXml: String): Int? {
        val style = Regex("""<w:pStyle\b[^>]*w:val\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(paragraphXml)?.groupValues?.get(1)
        if (style != null) {
            val n = Regex("""(?i)heading\s*(\d+)""").find(style)?.groupValues?.get(1)?.toIntOrNull()
            if (n != null) return n.coerceIn(1, 6)
            if (style.equals("Title", ignoreCase = true)) return 1
        }
        val outline = Regex("""<w:outlineLvl\b[^>]*w:val\s*=\s*"(\d+)"""", RegexOption.IGNORE_CASE)
            .find(paragraphXml)?.groupValues?.get(1)?.toIntOrNull()
        return outline?.let { (it + 1).coerceIn(1, 6) }
    }

    /** `docProps/core.xml` 的 `dc:title`。 */
    internal fun coreTitleOf(coreXml: String): String? =
        Regex("""(?is)<dc:title\b[^>]*>(.*?)</dc:title\s*>""")
            .find(coreXml)
            ?.groupValues?.get(1)
            ?.let { HtmlEntities.decode(it) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
