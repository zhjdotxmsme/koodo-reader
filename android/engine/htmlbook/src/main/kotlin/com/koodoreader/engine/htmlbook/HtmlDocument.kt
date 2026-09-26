package com.koodoreader.engine.htmlbook

import com.koodoreader.engine.layout.HtmlFlattener
import com.koodoreader.engine.layout.TextBlock

/**
 * 单文件 HTML / XHTML / HTM / XML → [TextBlock]（P5.5b，ADR-006）。
 *
 * 只做"文件 → 块"这一件事：解析/编码探测交给已就绪的
 * `:engine:text`（charset），扁平化交给 `:engine:layout` 的
 * [HtmlFlattener]（D0：容错 tokenizer + CFI 兄弟序保真）。分页、CFI 读写、
 * 渲染由 :app 的 ReaderSession 统一承担——本模块零 Android 依赖。
 */
object HtmlDocument {

    /** 扁平化结果：块 + 文档标题（`<title>`，进度条/书架显示用）。 */
    data class Result(val blocks: List<TextBlock>, val title: String?)

    /** HTML 字符串 → 块（[spineIndex] 是该文档的 CFI spine 步）。 */
    fun blocks(html: String, spineIndex: Int = 1): List<TextBlock> =
        HtmlFlattener.DEFAULT.flatten(html, spineIndex = spineIndex).blocks

    /**
     * 原始字节 → 结果：先按 `:engine:text` 的探测/解码（GBK/Big5/UTF-8…，
     * 中文网页常见非 UTF-8），再扁平化。
     */
    fun fromBytes(bytes: ByteArray, spineIndex: Int = 1): Result {
        val guess = com.koodoreader.engine.text.CharsetDetector.detect(bytes)
        val html = com.koodoreader.engine.text.TextDecoder.decode(bytes, guess)
        return Result(blocks(html, spineIndex), titleOf(html))
    }

    /** `<title>…</title>`（大小写不敏感、容忍属性/空白）。 */
    fun titleOf(html: String): String? =
        Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues?.get(1)
            ?.let { com.koodoreader.engine.layout.HtmlEntities.decode(it) }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
