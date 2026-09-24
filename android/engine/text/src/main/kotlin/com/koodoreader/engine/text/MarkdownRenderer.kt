package com.koodoreader.engine.text

/**
 * Dependency-free Markdown subset renderer.
 *
 * Replaces the desktop's `marked` dependency (`kookit/src/renders/MdRender.ts`
 * calls `marked(...)` on the whole file). Two deliberate differences:
 *
 *  1. **Safety.** `marked` emits raw HTML found in the document and, in older
 *     versions, passes `javascript:` URLs through. Books are untrusted input, so
 *     every text node and every attribute here is escaped (`& < > " '`) and link
 *     destinations are scheme-filtered. There is no "allow raw HTML" switch.
 *  2. **Streaming-friendly shape.** [render] returns a single HTML fragment as
 *     the reader expects, but the block parser is split from the inline parser so
 *     the pagination engine can lay out block-by-block later (see the report).
 *
 * Supported:
 *  - ATX headings `#`…`######` (with optional closing hashes) and setext
 *    headings (`===` / `---` underline);
 *  - bold `**`/`__`, italic `*`/`_`, bold+italic `***`, and intraword `_`
 *    protection (`snake_case` stays literal);
 *  - inline code `` `x` `` (multi-backtick spans included) — never emphasis-
 *    parsed inside;
 *  - fenced code blocks (``` and ~~~, with a language class) and 4-space
 *    indented code blocks;
 *  - unordered (`-` `*` `+`) and ordered (`1.` `1)`) lists, nested by
 *    indentation, tight and loose;
 *  - blockquotes `>`, nested;
 *  - thematic breaks (`---`, `***`, `___`);
 *  - inline links `[text](url "title")`, reference links
 *    `[text][id]` / `[text][]` / `[id]` with `[id]: url` definitions, image
 *    links `![alt](src "title")`, autolinks `<https://…>`;
 *  - tables (GFM pipe tables, with `:---:` alignment);
 *  - backslash escapes and hard line breaks (`two spaces` / trailing `\`).
 *
 * NOT supported (explicitly, so the reader can decide):
 *  footnotes, definition lists, strikethrough (`~~x~~`), task lists (`- [ ]`),
 *  inline HTML (escaped, not rendered), raw HTML blocks, `<details>`, YAML
 *  front matter (kept as text), subscript/superscript, emoji shortcodes,
 *  autolinking of bare URLs, footnotes/reference-style images beyond the plain
 *  forms above, math.
 * [UnsupportedSyntax.scan] reports which of these a given document actually
 * uses, so the app can warn instead of silently losing content.
 */
object MarkdownRenderer {

    private const val MAX_DEPTH = 8
    private const val MAX_FENCE = 10

    /**
     * In-band hard-break marker used between the block pass (which knows the
     * original line breaks) and the inline pass (which must not see them as
     * ordinary whitespace). A NUL can never appear in normalised text —
     * [TextNormalizer] removes it — so it is safe as a sentinel.
     */
    private val HARD_BREAK: Char = '\u0000'

    /** Renders [md] (already normalised to `\n` line endings) to an HTML fragment. */
    fun render(md: String): String = Builder(md).run()

    /** Convenience: normalise then render. */
    fun renderNormalized(md: String): String =
        render(TextNormalizer.normalize(md, TextNormalizer.Options.Paragraphs))

    /**
     * Escapes the five HTML-significant characters. Used for text nodes *and*
     * attribute values, which is why `"` and `'` are included.
     */
    fun escapeHtml(s: String): String {
        val needs = s.any { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }
        if (!needs) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** True when [url] may be used in `href`/`src` (no script/`data:` payloads). */
    fun isSafeUrl(url: String): Boolean {
        val u = url.trim().replace("\u0000", "")
        if (u.isEmpty()) return false
        // Normalise away the usual obfuscations before looking for a scheme.
        val compact = u.filterNot { it == '\n' || it == '\r' || it == '\t' || it == ' ' }
        val colon = compact.indexOf(':')
        val slash = compact.indexOf('/')
        val hash = compact.indexOf('#')
        val question = compact.indexOf('?')
        val firstDelim = listOf(slash, hash, question).filter { it >= 0 }.minOrNull() ?: Int.MAX_VALUE
        if (colon < 0 || colon > firstDelim) return true // relative URL
        val scheme = compact.substring(0, colon).lowercase()
        return scheme in setOf("http", "https", "mailto", "tel", "ftp", "file", "blob", "content")
    }

    // ------------------------------------------------------------------ block --

    private class Builder(
        private val src: String,
        /**
         * Link reference definitions. Shared with nested builders (blockquote /
         * list items) so `[id]: url` declared anywhere in the document resolves
         * everywhere, and collected in a *pre-pass* so forward references work.
         */
        val refs: HashMap<String, Pair<String, String>> = HashMap(),
        private val collect: Boolean = true,
    ) {
        private val lines: List<String> = src.split('\n')
        private val out = StringBuilder(src.length + src.length / 4)

        fun run(): String {
            if (collect) collectReferences()
            var i = 0
            while (i < lines.size) {
                i = block(i, 0)
            }
            return out.toString()
        }

        /**
         * Reference definitions (`[id]: url "title"`) are pulled out first so a
         * link can point forward, and so a definition inside a code fence is not
         * mistaken for one.
         */
        private fun collectReferences() {
            var fence: String? = null
            for (line in lines) {
                val t = line.trim()
                val f = fenceOf(t)
                if (f != null) {
                    fence = if (fence == null) f else if (fence == f) null else fence
                    continue
                }
                if (fence != null) continue
                val m = RE_REF.matchEntire(t) ?: continue
                val id = m.groupValues[1].trim().lowercase()
                val url = m.groupValues[2].trim().removeSurrounding("<", ">")
                val title = m.groupValues[3].ifEmpty { m.groupValues[4] }
                refs.putIfAbsent(id, url to title)
            }
        }

        /** Renders the block starting at [start]; returns the next line index. */
        private fun block(start: Int, depth: Int): Int {
            val line = lines[start]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                return start + 1
            }

            // Fenced code block.
            fenceOf(trimmed)?.let { fence ->
                val lang = trimmed.removePrefix(fence).trim().split(Regex("\\s+")).firstOrNull() ?: ""
                val body = StringBuilder()
                var i = start + 1
                while (i < lines.size) {
                    val cur = lines[i]
                    if (cur.trim().startsWith(fence) &&
                        cur.trim().dropWhile { it == fence[0] }.trim().isEmpty()
                    ) {
                        i++
                        break
                    }
                    body.append(cur).append('\n')
                    i++
                }
                out.append("<pre><code")
                if (lang.isNotEmpty() && lang.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '+' || it == '#' }) {
                    out.append(" class=\"language-").append(escapeHtml(lang)).append('"')
                }
                out.append('>').append(escapeHtml(body.toString())).append("</code></pre>\n")
                return i
            }

            // ATX heading.
            val atx = RE_ATX.matchEntire(trimmed)
            if (atx != null) {
                val level = atx.groupValues[1].length
                // An optional closing run of `#` must be stripped entirely.
                val text = atx.groupValues[2].trimEnd().trimEnd('#').trimEnd()
                out.append("<h").append(level).append('>')
                    .append(inline(text))
                    .append("</h").append(level).append(">\n")
                return start + 1
            }

            // Setext heading. Checked BEFORE the thematic break: `foo\n---` is a
            // level-2 heading (CommonMark), not a paragraph followed by an `<hr />`.
            if (start + 1 < lines.size && trimmed.isNotEmpty() &&
                !isBlockStart(trimmed) && !isTableDelimiter(lines[start + 1])
            ) {
                val under = lines[start + 1].trim()
                if (under.isNotEmpty() && under.all { it == '=' }) {
                    out.append("<h1>").append(inline(trimmed)).append("</h1>\n")
                    return start + 2
                }
                if (under.isNotEmpty() && under.all { it == '-' }) {
                    out.append("<h2>").append(inline(trimmed)).append("</h2>\n")
                    return start + 2
                }
            }

            // Thematic break.
            if (RE_RULE.matches(trimmed)) {
                out.append("<hr />\n")
                return start + 1
            }

            // Blockquote.
            if (trimmed.startsWith(">")) {
                val inner = ArrayList<String>()
                var i = start
                while (i < lines.size) {
                    val t = lines[i].trimStart()
                    if (!t.startsWith(">")) break
                    inner += t.removePrefix(">").removePrefix(" ")
                    i++
                }
                out.append("<blockquote>\n")
                if (depth < MAX_DEPTH) {
                    val innerText = inner.joinToString("\n") { it.trim() }
                    out.append(Builder(innerText, refs, collect = false).run())
                } else {
                    out.append("<p>").append(inline(inner.joinToString("\n"))).append("</p>\n")
                }
                out.append("</blockquote>\n")
                return i
            }

            // Table: current line has a pipe and the next is a delimiter row.
            if (trimmed.contains('|') && start + 1 < lines.size && isTableDelimiter(lines[start + 1])) {
                return table(start)
            }

            // A link reference definition renders as nothing (it was already
            // harvested by collectReferences).
            if (RE_REF.matches(trimmed)) return start + 1

            // Lists.
            if (isListItem(line)) return list(start, depth)

            // Indented code block (4 spaces / tab), not a lazy paragraph line.
            if ((line.startsWith("    ") || line.startsWith("\t")) && depth < MAX_DEPTH) {
                val body = StringBuilder()
                var i = start
                while (i < lines.size && (lines[i].startsWith("    ") || lines[i].startsWith("\t") ||
                        lines[i].isBlank())
                ) {
                    body.append(lines[i].removePrefix("\t").removePrefix("    ")).append('\n')
                    i++
                }
                out.append("<pre><code>").append(escapeHtml(body.toString())).append("</code></pre>\n")
                return i
            }

            // Paragraph: consume until a blank line or a block starter. Soft line
            // breaks become spaces (HTML semantics); a trailing `\` or two
            // trailing spaces becomes the U+0000 hard-break marker, which the
            // inline pass turns into `<br />`.
            val para = StringBuilder()
            var i = start
            while (i < lines.size) {
                val cur = lines[i]
                if (cur.trim().isEmpty()) break
                if (i > start && isBlockStart(cur.trim())) break
                if (para.isNotEmpty()) {
                    // Inside one paragraph a source line break is a soft break →
                    // a single space (HTML semantics). A hard break carries its
                    // own marker, so nothing needs to be inserted for it.
                    para.append(if (para.last() == HARD_BREAK) "" else ' ')
                }
                para.append(paragraphLine(cur))
                i++
            }
            out.append("<p>").append(inline(para.toString())).append("</p>\n")
            return i
        }

        private fun paragraphLine(raw: String): String {
            val body = raw.trim()
            if (body.endsWith("\\") && !body.endsWith("\\\\")) {
                return body.dropLast(1).trimEnd() + HARD_BREAK
            }
            if (raw.length - raw.trimEnd(' ').length >= 2) return body + HARD_BREAK
            return body
        }

        private fun table(start: Int): Int {
            val header = splitRow(lines[start])
            val aligns = splitRow(lines[start + 1]).map { cell ->
                val t = cell.trim()
                when {
                    t.startsWith(":") && t.endsWith(":") -> "center"
                    t.endsWith(":") -> "right"
                    t.startsWith(":") -> "left"
                    else -> ""
                }
            }
            out.append("<table>\n<thead>\n<tr>")
            for ((idx, cell) in header.withIndex()) {
                out.append("<th").append(alignAttr(aligns.getOrNull(idx))).append('>')
                    .append(inline(cell.trim())).append("</th>")
            }
            out.append("</tr>\n</thead>\n<tbody>\n")
            var i = start + 2
            while (i < lines.size && lines[i].contains('|') && lines[i].trim().isNotEmpty() &&
                !isBlockStart(lines[i].trim())
            ) {
                val row = splitRow(lines[i])
                out.append("<tr>")
                for (idx in header.indices) {
                    out.append("<td").append(alignAttr(aligns.getOrNull(idx))).append('>')
                        .append(inline(row.getOrNull(idx)?.trim() ?: "")).append("</td>")
                }
                out.append("</tr>\n")
                i++
            }
            out.append("</tbody>\n</table>\n")
            return i
        }

        private fun alignAttr(a: String?): String =
            if (a.isNullOrEmpty()) "" else " style=\"text-align:$a\""

        private fun list(start: Int, depth: Int): Int {
            if (depth >= MAX_DEPTH) {
                out.append("<p>").append(inline(lines[start].trim())).append("</p>\n")
                return start + 1
            }
            val first = lines[start]
            val baseIndent = indentOf(first)
            val ordered = orderedMarker(first) != null && bulletMarker(first) == null
            val items = ArrayList<List<String>>()
            var loose = false
            var i = start
            var pendingBlank = false

            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) {
                    if (items.isNotEmpty()) pendingBlank = true
                    i++
                    continue
                }
                val ind = indentOf(line)
                val content = itemContentOffset(line)

                // A blank line followed by a *non-indented* item starts a NEW list
                // (`- a\n- b\n\n1. c`), not a nested one.
                if (pendingBlank && ind <= baseIndent) break

                if (content != null && ind <= baseIndent + 1) {
                    if (pendingBlank && items.isNotEmpty()) loose = true
                    pendingBlank = false
                    items.add(arrayListOf(line.substring(content)))
                    i++
                    continue
                }
                if (items.isEmpty()) break
                // Deeper or continuation lines belong to the current item and are
                // kept with their original indentation, so the recursive renderer
                // can still see a nested list / code block.
                if (ind > baseIndent) {
                    if (pendingBlank) loose = true
                    pendingBlank = false
                    items[items.size - 1] = items[items.size - 1] + "\n" + line
                    i++
                    continue
                }
                // A non-indented, non-list line is a lazy paragraph continuation.
                if (isBlockStart(line.trim()) && !line.trim().startsWith(">")) break
                items[items.size - 1] = items[items.size - 1] + "\n" + line.trim()
                i++
            }

            val tag = if (ordered) "ol" else "ul"
            val startAttr = if (ordered) {
                // itemContentOffset points at the text after `N. `, so the digits
                // sit just before it.
                val digits = first.substring(0, itemContentOffset(first) ?: 0)
                    .trimStart().takeWhile { c -> c.isDigit() }
                if (digits.isNotEmpty() && digits != "1") " start=\"$digits\"" else ""
            } else {
                ""
            }

            out.append('<').append(tag).append(startAttr).append(">\n")
            for (itemLines in items) {
                val text = itemLines.joinToString("\n")
                // Nested structures (sub-lists, code blocks) need the block pass,
                // so the item body is rendered recursively.
                val html = Builder(text, refs, collect = false).run().trimEnd()
                out.append("<li>")
                    .append(if (loose) html else unwrapParagraph(html))
                    .append("</li>\n")
            }
            out.append("</").append(tag).append(">\n")
            return i
        }

        /**
         * Removes the `<p>` wrapper of a tight-list item. Only a *single*
         * paragraph is unwrapped: an item such as `- a\n\n  b` really is two
         * paragraphs and must keep its `<p>`s.
         */
        private fun unwrapParagraph(rendered: String): String {
            if (!rendered.startsWith("<p>") || !rendered.endsWith("</p>")) return rendered
            val closing = rendered.indexOf("</p>")
            return if (closing == rendered.length - 4) {
                rendered.substring(3, rendered.length - 4)
            } else {
                rendered
            }
        }

        // -------------------------------------------------------------- inline --

        /**
         * Inline pass. Single left-to-right scan so that nothing is ever
         * re-parsed (the classic `**<b>**`/escape bugs come from repeated
         * `replaceAll` rounds).
         */
        fun inline(text: String): String {
            val sb = StringBuilder(text.length + 16)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '\\' && i + 1 < text.length && text[i + 1].isAsciiPunct() -> {
                        sb.append(escapeHtml(text[i + 1].toString()))
                        i += 2
                    }
                    c == '`' -> {
                        val fenceLen = text.countRun(i, '`')
                        val close = text.indexOf("`".repeat(fenceLen), i + fenceLen)
                        if (close < 0) {
                            sb.append(escapeHtml(text.substring(i, i + fenceLen)))
                            i += fenceLen
                        } else {
                            var code = text.substring(i + fenceLen, close)
                            if (code.length >= 2 && code.startsWith(" ") && code.endsWith(" ") &&
                                code.isNotBlank()
                            ) {
                                code = code.substring(1, code.length - 1)
                            }
                            sb.append("<code>").append(escapeHtml(code)).append("</code>")
                            i = close + fenceLen
                        }
                    }
                    c == '!' && i + 1 < text.length && text[i + 1] == '[' -> {
                        i = emitImage(text, i, sb)
                    }
                    c == '[' -> {
                        i = emitLink(text, i, sb)
                    }
                    c == '<' -> {
                        // Autolink `<https://…>` or `<mailto:…>`; anything else
                        // (including raw HTML) is escaped, not rendered.
                        val end = text.indexOf('>', i + 1)
                        val inner = if (end > i) text.substring(i + 1, end) else ""
                        if (end > i && (inner.startsWith("http://") || inner.startsWith("https://") ||
                                inner.startsWith("mailto:") || inner.startsWith("ftp://"))
                        ) {
                            val url = inner
                            val href = if (url.startsWith("mailto:")) url else url
                            sb.append("<a href=\"").append(escapeHtml(href)).append("\">")
                                .append(escapeHtml(inner.removePrefix("mailto:"))).append("</a>")
                            i = end + 1
                        } else {
                            sb.append("&lt;")
                            i++
                        }
                    }
                    c == '*' || c == '_' -> {
                        i = emitEmphasis(text, i, sb)
                    }
                    c == HARD_BREAK -> {
                        sb.append("<br />")
                        i++
                    }
                    else -> {
                        // Escaping one char at a time keeps the loop simple;
                        // escapeHtml is only called on single characters here.
                        sb.append(escapeHtml(c.toString()))
                        i++
                    }
                }
            }
            return sb.toString()
        }

        private fun emitEmphasis(text: String, start: Int, sb: StringBuilder): Int {
            val c = text[start]
            val run = text.countRun(start, c)
            val isWordCharBefore = start > 0 && text[start - 1].isWordLike()
            val isWordCharAfter = start + run < text.length && text[start + run].isWordLike()

            // `_` inside a word (snake_case) is literal, per CommonMark.
            if (c == '_' && isWordCharBefore && isWordCharAfter) {
                sb.append(c)
                return start + 1
            }

            for (n in minOf(run, 3) downTo 1) {
                // No whitespace immediately inside the delimiters.
                if (start + n >= text.length || text[start + n].isWhitespace()) continue
                val closer = findCloser(text, start + n, c, n)
                if (closer < 0) continue
                val inner = text.substring(start + n, closer)
                if (inner.isEmpty()) continue
                val html = inline(inner)
                when (n) {
                    3 -> sb.append("<strong><em>").append(html).append("</em></strong>")
                    2 -> sb.append("<strong>").append(html).append("</strong>")
                    else -> sb.append("<em>").append(html).append("</em>")
                }
                return closer + n
            }
            sb.append(escapeHtml(text.substring(start, start + 1)))
            return start + 1
        }

        /** Finds a closing delimiter run of exactly [n] (or more) of [c]. */
        private fun findCloser(text: String, from: Int, c: Char, n: Int): Int {
            var i = from
            while (i < text.length) {
                if (text[i] == '\\') {
                    i += 2; continue
                }
                if (text[i] == c) {
                    val run = text.countRun(i, c)
                    val precededBySpace = i > 0 && text[i - 1].isWhitespace()
                    if (run >= n && !precededBySpace && i > from) return i
                    i += run
                    continue
                }
                i++
            }
            return -1
        }

        private fun emitLink(text: String, start: Int, sb: StringBuilder): Int {
            val close = matchBracket(text, start)
            if (close < 0) {
                sb.append(escapeHtml("["))
                return start + 1
            }
            val label = text.substring(start + 1, close)
            val after = close + 1
            val dest = resolveDestination(text, after, label)
            if (dest == null) {
                // Not a link: emit the label in brackets, emphasis still applied.
                sb.append('[').append(inline(label)).append(']')
                return after
            }
            val (url, title, next) = dest
            if (url.isEmpty() || !isSafeUrl(url)) {
                sb.append(inline(label))
                return next
            }
            sb.append("<a href=\"").append(escapeHtml(url)).append('"')
            appendTitle(sb, title)
            sb.append('>').append(inline(label)).append("</a>")
            return next
        }

        private fun emitImage(text: String, start: Int, sb: StringBuilder): Int {
            val close = matchBracket(text, start + 1)
            if (close < 0) {
                sb.append("![")
                return start + 2
            }
            val alt = text.substring(start + 2, close)
            val after = close + 1
            val dest = resolveDestination(text, after, alt)
            if (dest == null) {
                sb.append("![").append(inline(alt)).append(']')
                return after
            }
            val (url, title, next) = dest
            if (url.isEmpty() || !isSafeUrl(url)) {
                sb.append(inline(alt))
                return next
            }
            sb.append("<img src=\"").append(escapeHtml(url)).append("\" alt=\"")
                .append(escapeHtml(alt)).append('"')
            appendTitle(sb, title)
            sb.append(" />")
            return next
        }

        private fun appendTitle(sb: StringBuilder, title: String) {
            if (title.isNotEmpty()) {
                sb.append(" title=\"").append(escapeHtml(title)).append('"')
            }
        }

        /**
         * `[text](url "title")` → ("url","title",indexAfter) ·
         * `[text][id]` / `[text][]` → looked up in [refs] ·
         * `[text]` with a matching definition → same.
         */
        private fun resolveDestination(
            text: String,
            after: Int,
            label: String,
        ): Triple<String, String, Int>? {
            if (after < text.length && text[after] == '(') {
                val end = matchParen(text, after)
                if (end < 0) return null
                val body = text.substring(after + 1, end).trim()
                val (url, title) = splitUrlTitle(body)
                return Triple(url, title, end + 1)
            }
            if (after < text.length && text[after] == '[') {
                val end = text.indexOf(']', after + 1)
                if (end < 0) return null
                val id = text.substring(after + 1, end).ifEmpty { label }.lowercase()
                val ref = refs[id] ?: return Triple("", "", end + 1)
                return Triple(ref.first, ref.second, end + 1)
            }
            // Shortcut reference `[id]`.
            val ref = refs[label.lowercase()] ?: return null
            return Triple(ref.first, ref.second, after)
        }

        private fun splitUrlTitle(body: String): Pair<String, String> {
            if (body.isEmpty()) return "" to ""
            if (body.startsWith("<")) {
                val close = body.indexOf('>')
                if (close > 0) {
                    return body.substring(1, close) to unquote(body.substring(close + 1).trim())
                }
            }
            val space = body.indexOfFirst { it == ' ' || it == '\t' }
            if (space < 0) return body to ""
            return body.substring(0, space) to unquote(body.substring(space + 1).trim())
        }

        private fun unquote(s: String): String {
            if (s.length >= 2) {
                val first = s.first()
                val last = s.last()
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'') ||
                    (first == '(' && last == ')')
                ) {
                    return s.substring(1, s.length - 1)
                }
            }
            return s
        }

        /** Index of the matching `]` for the `[` at [open], honouring nesting. */
        private fun matchBracket(text: String, open: Int): Int {
            var depth = 0
            var i = open
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> i++
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
            return -1
        }

        private fun matchParen(text: String, open: Int): Int {
            var depth = 0
            var i = open
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> i++
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
            return -1
        }
    }

    // ------------------------------------------------------------- helpers ----

    private val RE_ATX = Regex("^(#{1,6})\\s+(.*)$")
    private val RE_RULE = Regex("^(?:\\*\\s*){3,}$|^(?:-\\s*){3,}$|^(?:_\\s*){3,}$")
    private val RE_REF = Regex(
        "^\\[([^\\]]+)\\]:\\s*(\\S+)(?:\\s+(?:\"([^\"]*)\"|'([^']*)'|\\(([^)]*)\\)))?\\s*$",
    )
    private val RE_BULLET = Regex("^(?:[-*+])(?:\\s+|$)")
    private val RE_ORDERED = Regex("^\\d{1,9}[.)](?:\\s+|$)")

    /** `- item` / `* item`, but not a `---` rule and not a bare marker. */
    private fun bulletMarker(line: String): Int? {
        val t = line.trimStart()
        val m = RE_BULLET.find(t) ?: return null
        val content = t.substring(m.value.length)
        if (content.isEmpty()) return null
        // `---`, `***`, `___` are thematic breaks, not list items.
        if (m.value.trim().length == 1 && content.all { it == m.value.trim()[0] }) return null
        return indentOf(line) + m.value.length
    }

    /** `1. item` / `2) item`, but not a lone `2024.` line. */
    private fun orderedMarker(line: String): Int? {
        val t = line.trimStart()
        val m = RE_ORDERED.find(t) ?: return null
        if (t.substring(m.value.length).isEmpty()) return null
        return indentOf(line) + m.value.length
    }

    /** Any list-item line (for block-start detection). */
    private fun isListItem(line: String): Boolean = itemContentOffset(line) != null

    /**
     * Offset of the first content character of a list item (`- x` → 2), or null
     * when [line] is not a list item. A `---` rule and a lone `2024.` line are
     * deliberately not items.
     */
    private fun itemContentOffset(line: String): Int? {
        val n = bulletMarker(line) ?: orderedMarker(line) ?: return null
        return n
    }

    /** Returns the fence string (```` ``` ```` or `~~~`) if [trimmed] opens one. */
    private fun fenceOf(trimmed: String): String? {
        if (trimmed.length < 3) return null
        val c = trimmed[0]
        if (c != '`' && c != '~') return null
        var n = 1
        while (n < trimmed.length && trimmed[n] == c) n++
        if (n < 3 || n > MAX_FENCE) return null
        // An info string is only legal after a backtick fence.
        val rest = trimmed.substring(n)
        if (c == '`' && rest.contains('`')) return null
        return c.toString().repeat(n)
    }

    private fun isTableDelimiter(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || !t.contains('-') || !t.contains('|')) return false
        return t.all { it == '-' || it == '|' || it == ':' || it == ' ' || it == '\t' }
    }

    private fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1).trimStart()
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
        val cells = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] == '|') {
                cur.append('|')
                i += 2
                continue
            }
            if (c == '|') {
                cells += cur.toString()
                cur.setLength(0)
                i++
                continue
            }
            cur.append(c)
            i++
        }
        if (cur.isNotEmpty() || cells.isEmpty()) cells += cur.toString()
        return cells
    }

    private fun indentOf(line: String): Int {
        var n = 0
        for (c in line) {
            when (c) {
                ' ' -> n++
                '\t' -> n += 4
                else -> return n
            }
        }
        return n
    }

    /** True when [trimmed] starts a block other than a paragraph. */
    private fun isBlockStart(trimmed: String): Boolean {
        if (trimmed.startsWith(">")) return true
        if (fenceOf(trimmed) != null) return true
        if (RE_ATX.matches(trimmed)) return true
        if (RE_RULE.matches(trimmed)) return true
        if (isListItem(trimmed)) return true
        if (RE_REF.matches(trimmed)) return true
        return false
    }

    private fun String.countRun(from: Int, c: Char): Int {
        var n = 0
        while (from + n < length && this[from + n] == c) n++
        return n
    }

    private fun Char.isAsciiPunct(): Boolean =
        this in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /** `_` inside a word must stay literal, so underscores count as word chars. */
    private fun Char.isWordLike(): Boolean = isLetterOrDigit() || this == '_'

    /**
     * Reports Markdown constructs this renderer knowingly does not support, so a
     * caller can surface "rendered as plain text" to the UI. Detection is
     * line-based and intentionally conservative (no false positives > no misses).
     */
    object UnsupportedSyntax {
        val FEATURES = listOf(
            "脚注 (footnote) [^1]",
            "定义列表 (definition list) Term\\n: definition",
            "删除线 (strikethrough) ~~text~~",
            "任务列表 (task list) - [ ] item",
            "原始 HTML / HTML 块",
            "YAML front matter (--- 开头元数据)",
            "数学公式 (math) $...$ / $$...$$",
            "上下标 ~sub~ / ^sup^",
            "emoji 短代码 :smile:",
            "裸 URL 自动链接",
        )

        /** @return the subset of [FEATURES] that [md] appears to use. */
        fun scan(md: String): List<String> {
            val found = LinkedHashSet<String>()
            val lines = md.split('\n')
            var inFence = false
            for ((idx, raw) in lines.withIndex()) {
                val line = raw.trim()
                if (fenceOf(line) != null) {
                    inFence = !inFence
                    continue
                }
                if (inFence) continue
                if (Regex("\\[\\^[^\\]]+\\]").containsMatchIn(line)) found += FEATURES[0]
                if (idx + 1 < lines.size && line.isNotEmpty() &&
                    lines[idx + 1].trim().startsWith(": ") && !line.startsWith("::")
                ) {
                    found += FEATURES[1]
                }
                if (Regex("~~[^~]+~~").containsMatchIn(line)) found += FEATURES[2]
                if (Regex("^[-*+]\\s+\\[[ xX]\\]").containsMatchIn(line)) found += FEATURES[3]
                if (Regex("</?[a-zA-Z][^>]*>").containsMatchIn(line) &&
                    !Regex("^<(https?://|mailto:)").containsMatchIn(line)
                ) {
                    found += FEATURES[4]
                }
                if (idx == 0 && line == "---") found += FEATURES[5]
                if (Regex("\\$\\$?[^$]+\\$\\$?").containsMatchIn(line)) found += FEATURES[6]
                if (Regex(":[a-z0-9_+-]+:").containsMatchIn(line)) found += FEATURES[8]
                if (Regex("(^|[^<\"'=(])(https?://\\S+)").containsMatchIn(line) &&
                    !line.contains("](")
                ) {
                    found += FEATURES[9]
                }
            }
            return found.toList()
        }
    }
}
