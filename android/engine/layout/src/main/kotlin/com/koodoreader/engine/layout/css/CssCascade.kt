package com.koodoreader.engine.layout.css

import com.koodoreader.engine.layout.ParagraphStyle
import com.koodoreader.engine.layout.TextAlign

data class CssElement(
    val tag: String,
    val id: String? = null,
    val classes: Set<String> = emptySet(),
    val inline: String? = null,
    val parentTags: List<String> = emptyList(),
)

data class CascadedStyle(
    val fontSizePx: Float = 16f,
    val textAlign: TextAlign? = null,
    val textIndentEm: Float = 0f,
    val lineHeightMultiple: Float? = null,
    val spaceBeforePx: Float = 0f,
    val spaceAfterPx: Float = 0f,
    val displayNone: Boolean = false,
) {
    fun toParagraphStyle(baseFontPx: Float = 16f, tagPreset: ParagraphStyle = ParagraphStyle.DEFAULT): ParagraphStyle {
        val scale = if (fontSizePx > 0f) fontSizePx / baseFontPx else tagPreset.fontSizeScale
        return ParagraphStyle(
            fontSizeScale = scale,
            lineHeightMultiple = lineHeightMultiple ?: tagPreset.lineHeightMultiple,
            textAlign = textAlign ?: tagPreset.textAlign,
            textIndentEm = if (textIndentEm != 0f) textIndentEm else tagPreset.textIndentEm,
            spaceBeforePx = if (spaceBeforePx != 0f) spaceBeforePx else tagPreset.spaceBeforePx,
            spaceAfterPx = if (spaceAfterPx != 0f) spaceAfterPx else tagPreset.spaceAfterPx,
        )
    }
}

object CssCascade {

    private const val BASE_FONT_PX = 16f

    fun styleFor(element: CssElement, rules: List<CssRule>): CascadedStyle {
        var style = CascadedStyle(fontSizePx = BASE_FONT_PX)
        for (rule in rules) {
            if (!selectorMatches(rule.selector, element)) continue
            style = applyDeclarations(style, rule.declarations, specificity(rule.selector))
        }
        element.inline?.let { inline ->
            style = applyDeclarations(style, parseInline(inline), specificity = 1000)
        }
        return style
    }

    fun paragraphStyleFor(
        element: CssElement,
        rules: List<CssRule>,
        tagPreset: ParagraphStyle,
        baseFontPx: Float = BASE_FONT_PX,
    ): ParagraphStyle {
        val cascaded = styleFor(element, rules)
        if (cascaded.displayNone) return tagPreset
        return cascaded.toParagraphStyle(baseFontPx, tagPreset)
    }

    private fun applyDeclarations(
        current: CascadedStyle,
        declarations: Map<String, String>,
        @Suppress("UNUSED_PARAMETER") specificity: Int,
    ): CascadedStyle {
        var next = current
        for ((key, raw) in declarations) {
            when (key) {
                "font-size" -> parseFontSizePx(raw)?.let { px ->
                    next = next.copy(fontSizePx = px)
                }
                "text-align" -> parseTextAlign(raw)?.let { align ->
                    next = next.copy(textAlign = align)
                }
                "text-indent" -> parseEm(raw)?.let { em ->
                    next = next.copy(textIndentEm = em)
                }
                "line-height" -> parseLineHeight(raw)?.let { lh ->
                    next = next.copy(lineHeightMultiple = lh)
                }
                "margin-top" -> parsePx(raw)?.let { px ->
                    next = next.copy(spaceBeforePx = px)
                }
                "margin-bottom" -> parsePx(raw)?.let { px ->
                    next = next.copy(spaceAfterPx = px)
                }
                "display" -> if (raw.equals("none", ignoreCase = true)) {
                    next = next.copy(displayNone = true)
                }
            }
        }
        return next
    }

    private fun parseInline(inline: String): Map<String, String> =
        inline.split(';').mapNotNull { part ->
            val colon = part.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            val key = part.substring(0, colon).trim().lowercase()
            val value = part.substring(colon + 1).trim()
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }.toMap()

    private fun selectorMatches(selector: String, element: CssElement): Boolean {
        val parts = selector.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        val target = parts.last()
        if (!simpleSelectorMatches(target, element)) return false
        if (parts.size == 1) return true
        val ancestors = listOf(element.tag.lowercase()) + element.parentTags.map { it.lowercase() }
        var ancestorIndex = ancestors.size - 1
        for (i in parts.size - 2 downTo 0) {
            val need = parts[i]
            while (ancestorIndex >= 0) {
                if (simpleSelectorMatches(need, ancestors[ancestorIndex], id = null, classes = emptySet())) {
                    ancestorIndex--
                    break
                }
                ancestorIndex--
            }
            if (ancestorIndex < 0 && i >= 0) return false
        }
        return true
    }

    private fun simpleSelectorMatches(
        token: String,
        tagOrName: String,
        id: String? = null,
        classes: Set<String> = emptySet(),
    ): Boolean {
        val element = if (id != null || classes.isNotEmpty()) {
            CssElement(tag = tagOrName, id = id, classes = classes)
        } else {
            CssElement(tag = tagOrName)
        }
        return simpleSelectorMatches(token, element)
    }

    private fun simpleSelectorMatches(token: String, element: CssElement): Boolean {
        var sel = token.trim()
        if (sel.isEmpty()) return false
        var requiredId: String? = null
        val requiredClasses = mutableSetOf<String>()
        val idMatch = Regex("""#([\w-]+)""").findAll(sel).toList()
        if (idMatch.isNotEmpty()) {
            requiredId = idMatch.last().groupValues[1]
            sel = sel.replace(Regex("""#[\w-]+"""), "").trim()
        }
        Regex("""\.([\w-]+)""").findAll(token).forEach { requiredClasses += it.groupValues[1] }
        val tag = sel.replace(Regex("""\.[\w-]+"""), "").trim().lowercase()
        if (tag.isNotEmpty() && tag != element.tag.lowercase()) return false
        if (requiredId != null && requiredId != element.id) return false
        if (requiredClasses.isNotEmpty() && !requiredClasses.all { it in element.classes }) return false
        return true
    }

    private fun specificity(selector: String): Int {
        var score = 0
        if ('#' in selector) score += 100
        if ('.' in selector) score += 10
        if (selector.any { it.isLetter() && !selector.contains('.') && !selector.contains('#') }) score += 1
        return score
    }

    private fun parseFontSizePx(raw: String): Float? {
        val v = raw.trim().lowercase()
        return when {
            v.endsWith("px") -> v.removeSuffix("px").trim().toFloatOrNull()
            v.endsWith("em") -> v.removeSuffix("em").trim().toFloatOrNull()?.times(BASE_FONT_PX)
            v.endsWith("%") -> v.removeSuffix("%").trim().toFloatOrNull()?.times(BASE_FONT_PX / 100f)
            else -> v.toFloatOrNull()
        }
    }

    private fun parsePx(raw: String): Float? {
        val v = raw.trim().lowercase()
        return if (v.endsWith("px")) v.removeSuffix("px").trim().toFloatOrNull() else v.toFloatOrNull()
    }

    private fun parseEm(raw: String): Float? {
        val v = raw.trim().lowercase()
        return if (v.endsWith("em")) v.removeSuffix("em").trim().toFloatOrNull() else v.toFloatOrNull()
    }

    private fun parseLineHeight(raw: String): Float? {
        val v = raw.trim().lowercase()
        return if (v.endsWith("px")) {
            v.removeSuffix("px").trim().toFloatOrNull()?.div(BASE_FONT_PX)
        } else {
            v.toFloatOrNull()
        }
    }

    private fun parseTextAlign(raw: String): TextAlign? =
        when (raw.trim().lowercase()) {
            "left", "start" -> TextAlign.LEFT
            "right", "end" -> TextAlign.RIGHT
            "center" -> TextAlign.LEFT
            "justify" -> TextAlign.JUSTIFY
            else -> null
        }
}
