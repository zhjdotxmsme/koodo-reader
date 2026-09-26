package com.koodoreader.engine.layout

/**
 * HTML 实体解码（公开工具）：D0 的 [HtmlTokenizer] 内部使用，同时也供
 * `:engine:htmlbook` / `:engine:fb2` 等"文件 → 块"生产者复用——它们需要
 * 解码标题等纯文本，但不能依赖 internal 的 tokenizer。
 *
 * 容错口径与浏览器一致：未知/损坏实体原样透传，命名与数字引用的结尾分号
 * 可省；超范围码点映射为 U+FFFD。
 */
object HtmlEntities {

    private val NAMED = mapOf(
        "lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00A0", "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122",
        "hellip" to "\u2026", "mdash" to "\u2014", "ndash" to "\u2013",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201C", "rdquo" to "\u201D",
        "bull" to "\u2022", "middot" to "\u00B7", "deg" to "\u00B0",
        "laquo" to "\u00AB", "raquo" to "\u00BB", "times" to "\u00D7", "divide" to "\u00F7",
        "euro" to "\u20AC", "pound" to "\u00A3", "yen" to "\u00A5", "cent" to "\u00A2",
        "sect" to "\u00A7", "para" to "\u00B6", "plusmn" to "\u00B1", "micro" to "\u00B5",
    )

    private val ENTITY_RE = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);?")

    /** 解码 [s] 中的 HTML 实体（未知/损坏原样保留）。 */
    fun decode(s: String): String {
        if (!s.contains('&')) return s
        return ENTITY_RE.replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.toCharOrNull()?.toString() ?: m.value
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.toCharOrNull()?.toString() ?: m.value
                else -> NAMED[body.lowercase()] ?: m.value
            }
        }
    }

    private fun Int.toCharOrNull(): Char? {
        if (this < 0) return null
        if (this > 0xFFFF) return '\uFFFD'
        val ch = this.toChar()
        return if (Character.isHighSurrogate(ch) || Character.isLowSurrogate(ch)) '\uFFFD' else ch
    }
}
