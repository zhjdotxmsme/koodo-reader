package com.koodoreader.core.importer

/**
 * EPUB 真目录（EPUB3 NAV 优先，EPUB2 NCX 回退）。
 *
 * 此前 `EpubSpine` 只解析 OPF（container → manifest → spine），阅读器的
 * 目录对话框只能拿 **zip 文件名** 当章节标签（PDF 屏反而有真 outline，
 * engine/pdf OutlineResolver）——本类补上缺失的「章节名 → spine 章节」映射。
 *
 * 与 [EpubSpine]/[EpubBook] 同族：零依赖、正则 + 扫描、容错优先于严格
 * （坏 TOC 返回空列表，永不抛出，调用方降级回文件名标签）。
 *
 *  - EPUB3：manifest 项 `properties="nav"` 指向 XHTML；目录取其
 *    `<nav epub:type="toc">`（没有就第一个 `<nav>`）里的 `<a href>标题</a>`。
 *  - EPUB2：manifest 项 `media-type="application/x-dtbncx+xml"` 指向 NCX；
 *    目录是 `<navPoint><navLabel><text>标题</text></navLabel><content src=…/>`。
 *  - href 归一化复用 [EpubSpine.resolve]（decode %XX → 相对 OPF 目录解析 →
 *    正斜杠），与 [EpubSpine.SpineItem.href] 同一键空间。
 */
object EpubToc {

    /** One directory entry: [label] + [href] (zip-absolute, normalized). */
    data class Entry(val label: String, val href: String)

    /**
     * Parse the book's TOC. Returns an **empty list** when the package has
     * none or it is unreadable — never throws on malformed content.
     */
    fun parse(spine: EpubSpine): List<Entry> {
        val opf = spine.readResource(spine.opfHref)?.toString(Charsets.UTF_8) ?: return emptyList()

        val items = ArrayList<Triple<String, String, String>>() // (href, mediaType, properties)
        for (m in Regex("""<item\b[^>]*>""").findAll(opf)) {
            val attrs = tagAttrs(m.value)
            if (attrs["href"] != null) {
                items.add(Triple(attrs["href"]!!, attrs["media-type"] ?: "", attrs["properties"] ?: ""))
            }
        }

        // EPUB3 `properties="nav"` first, EPUB2 NCX media type as fallback.
        val navHref = items.firstOrNull { (_, _, props) ->
            props.lowercase().split(Regex("\\s+")).any { it == "nav" }
        }?.first
            ?: items.firstOrNull { (_, mediaType, _) ->
                mediaType.equals("application/x-dtbncx+xml", ignoreCase = true)
            }?.first
            ?: return emptyList()

        val text = spine.readResource(spine.resolve(navHref))?.toString(Charsets.UTF_8)
            ?: return emptyList()
        // NCX 判定必须先于 NAV：NCX 里的 <navPoint>/<navMap> 同样含 "<nav" 子串，
        // 若按子串判 NAV 会把合法 NCX 书解析成空目录。
        return when {
            text.contains("<navpoint", true) || text.contains("<navmap", true) -> ncxEntriesOf(text)
            else -> navEntriesOf(text)
        }
    }

    /**
     * Chapter labels keyed by lower-cased normalized href; first TOC entry
     * wins when a chapter is listed twice.
     */
    fun chapterLabels(spine: EpubSpine): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (e in parse(spine)) out.putIfAbsent(e.href.lowercase(), e.label)
        return out
    }

    // ---- EPUB3 NAV ---------------------------------------------------------

    private fun navEntriesOf(xhtml: String): List<Entry> {
        // Prefer the toc-typed nav block; else the first <nav> at all.
        val openTag = Regex("""<nav\b[^>]*>""").findAll(xhtml)
            .map { it.value }
            .firstOrNull { it.contains("toc", true) }
            ?: Regex("""<nav\b[^>]*>""").find(xhtml)?.value
            ?: return emptyList()

        val openStart = xhtml.indexOf(openTag)
        val blockEnd = xhtml.indexOf("</nav>", openStart, true)
        val block = if (blockEnd < 0) xhtml.substring(openStart) else xhtml.substring(openStart, blockEnd)

        val entries = ArrayList<Entry>()
        // Label may wrap text in nested <span>s — capture the full body and
        // strip inner tags before entity-decoding.
        for (m in Regex("""<a\b[^>]*\bhref\s*=\s*("([^"]*)"|'([^']*)')[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(block)) {
            val href = m.groupValues[2].ifEmpty { m.groupValues[3] }
            val label = entities(m.groupValues[4].replace(Regex("<[^>]+>"), " ").trim())
            if (href.isNotEmpty() && label.isNotEmpty()) entries.add(Entry(label, href))
        }
        return entries
    }

    // ---- EPUB2 NCX ---------------------------------------------------------

    private fun ncxEntriesOf(ncx: String): List<Entry> {
        val entries = ArrayList<Entry>()
        for (m in Regex("""<navPoint\b[^>]*>.*?</navPoint>""", RegexOption.DOT_MATCHES_ALL).findAll(ncx)) {
            val block = m.value
            val label = Regex("""<text>\s*([^<]+?)\s*</text>""").find(block)
                ?.groupValues?.get(1)
                ?.let { entities(it.trim()) }
            val src = Regex("""<content\b[^>]*\bsrc\s*=\s*("([^"]*)"|'([^']*)')""").find(block)
                ?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }
            if (label != null && src != null) entries.add(Entry(label, src))
        }
        return entries
    }

    // ---- shared helpers (same style as EpubSpine) --------------------------

    /** Attributes of one tag string — both quote styles, order-free. */
    private fun tagAttrs(tag: String): Map<String, String> {
        val attrs = HashMap<String, String>()
        for (m in Regex("""([a-zA-Z_:][\w:.-]*)\s*=\s*("([^"]*)"|'([^']*)')""").findAll(tag)) {
            val value = m.groupValues[3].ifEmpty { m.groupValues[4] }
            attrs.putIfAbsent(m.groupValues[1].lowercase(), value)
        }
        return attrs
    }

    /** The named entities EPUB authors actually use, plus numeric refs. */
    private fun entities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#160;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { Character(it).toString() } ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { Character(it).toString() } ?: m.value }
}
