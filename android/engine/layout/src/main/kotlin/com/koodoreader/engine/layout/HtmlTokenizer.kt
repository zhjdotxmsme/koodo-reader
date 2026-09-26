package com.koodoreader.engine.layout

/**
 * Tolerant HTML tokenizer for the block flattener (D0, ADR-007).
 *
 * Design goals, in priority order:
 *  1. **Never throw** — malformed input degrades to text or is dropped, the
 *     scan always terminates (FB2/DOCX/HTML producers in the wild emit
 *     broken markup; a crash here kills the whole book).
 *  2. HTML5-ish recovery — `<` followed by a non-letter is literal text;
 *     comments / doctype / CDATA are skipped; a tag open whose name runs to
 *     EOF without `>` is dropped (browsers drop it too); `script`/`style`
 *     consume raw text until their (case-insensitive) close tag.
 *  3. Attribute values may contain `>` and `<` when quoted (`title="a>b"`).
 *
 * Entities are NOT decoded here — the flattener decodes them in text nodes
 * and attribute values, so an escaped `&lt;p&gt;` in the source never turns
 * into markup.
 */
internal class HtmlTokenizer(private val src: String) {

    internal sealed class Token {
        /** Raw text (entities still encoded). */
        class Text(val raw: String) : Token()

        /** Start tag; [attrs] keys lowercased; [selfClosing] from a trailing `/`. */
        class Open(val name: String, val attrs: Map<String, String>, val selfClosing: Boolean) : Token()

        /** End tag (name lowercased). */
        class Close(val name: String) : Token()
    }

    private var pos = 0

    /** Elements whose content is raw text, consumed verbatim until their close tag. */
    private var rawTextUntil: String? = null

    fun hasNext(): Boolean = pos < src.length

    /**
     * Next token, or null at EOF. Text between tags is coalesced into a
     * single Text token per contiguous run.
     */
    fun next(): Token? {
        if (pos >= src.length) return null

        if (rawTextUntil != null) {
            consumeRawText()
            if (pos >= src.length) return null
        }

        if (src[pos] != '<') return textToken()

        // Positions: '<' at pos.
        val rest = src.length - pos
        if (rest == 1) { // lone '<' at EOF — literal text
            pos = src.length
            return Token.Text("<")
        }
        val c1 = src[pos + 1]

        if (c1 == '!') {
            return when {
                src.startsWith("<!--", pos) -> { skipComment(); next() }
                else -> { skipBang() } // doctype / <![CDATA[ ... ]]> — dropped
            }
        }
        if (c1 == '?') { // processing instruction — skip to '>'
            skipUntil('>')
            return next()
        }
        if (c1 == '/') return closeTag()
        if (c1.isLetter()) return openTag()

        // '<' not starting a tag — literal text.
        pos++
        return Token.Text("<")
    }

    /** Text up to the next '<' (or EOF); empty runs are skipped internally. */
    private fun textToken(): Token {
        val start = pos
        while (pos < src.length && src[pos] != '<') pos++
        return Token.Text(src.substring(start, pos))
    }

    private fun skipComment() {
        val end = src.indexOf("-->", pos + 4)
        pos = if (end < 0) src.length else end + 3
    }

    /** `<!DOCTYPE …>` / `<![CDATA[…]]>` and other bogus-markup forms. */
    private fun skipBang(): Token? {
        if (src.startsWith("<![CDATA[", pos)) {
            val end = src.indexOf("]]>", pos + 9)
            val stop = if (end < 0) src.length else end + 3
            val content = src.substring(pos + 9, if (end < 0) src.length else end)
            pos = stop
            // CDATA is character data by definition — emit as text.
            return if (content.isEmpty()) next() else Token.Text(content)
        }
        skipUntil('>')
        return next()
    }

    private fun skipUntil(ch: Char) {
        val end = src.indexOf(ch, pos)
        pos = if (end < 0) src.length else end + 1
    }

    private fun closeTag(): Token? {
        var i = pos + 2
        val start = i
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '-' || src[i] == ':')) i++
        val name = src.substring(start, i)
        // Skip anything up to '>' (tolerates `</p >`, `</p attr>` junk).
        val end = src.indexOf('>', i)
        pos = if (end < 0) src.length else end + 1
        if (name.isEmpty()) return next() // `</>` / `</ >` — drop, continue
        return Token.Close(name.lowercase())
    }

    private fun openTag(): Token {
        var i = pos + 1
        val nameStart = i
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '-' || src[i] == ':')) i++
        val name = src.substring(nameStart, i).lowercase()

        val attrs = HashMap<String, String>()
        var selfClosing = false
        while (i < src.length) {
            // Skip whitespace between attributes.
            while (i < src.length && src[i].isWhitespace()) i++
            if (i >= src.length) break // unterminated tag — dropped by caller contract below
            when {
                src[i] == '>' -> { i++; pos = i; return finish(name, attrs, selfClosing) }
                src[i] == '/' -> {
                    // '/>' — self-closing. A bare '/' before '>' too.
                    var j = i + 1
                    while (j < src.length && src[j].isWhitespace()) j++
                    if (j < src.length && src[j] == '>') {
                        selfClosing = true
                        pos = j + 1
                        return finish(name, attrs, true)
                    }
                    i++ // stray '/' inside the tag — ignore
                }
                else -> i = readAttribute(i, attrs)
            }
        }
        // EOF inside an unterminated tag: HTML5 drops the whole token.
        pos = src.length
        return finish(name, attrs, false)
    }

    /** Reads one `name[=value]` attribute at [i]; returns the next scan index. */
    private fun readAttribute(i: Int, attrs: HashMap<String, String>): Int {
        var j = i
        val nameStart = j
        while (j < src.length && src[j] != '=' && src[j] != '>' && src[j] != '/' &&
            !src[j].isWhitespace()
        ) {
            j++
        }
        val key = src.substring(nameStart, j).lowercase()
        // Skip whitespace before '='.
        var k = j
        while (k < src.length && src[k].isWhitespace()) k++
        if (k >= src.length || src[k] != '=') {
            if (key.isNotEmpty()) attrs.putIfAbsent(key, "")
            return if (j == i) i + 1 else j // always make progress
        }
        k++ // past '='
        while (k < src.length && src[k].isWhitespace()) k++
        val value: String
        if (k < src.length && (src[k] == '"' || src[k] == '\'')) {
            val quote = src[k]
            k++
            val vStart = k
            while (k < src.length && src[k] != quote) k++
            value = src.substring(vStart, k.coerceAtMost(src.length))
            if (k < src.length) k++ // past quote
        } else {
            val vStart = k
            while (k < src.length && !src[k].isWhitespace() && src[k] != '>') k++
            value = src.substring(vStart, k)
        }
        if (key.isNotEmpty()) attrs.putIfAbsent(key, decodeEntities(value))
        return k
    }

    /** Consume raw `script`/`style` content up to the pending close tag. */
    private fun consumeRawText() {
        val close = rawTextUntil!!.lowercase()
        rawTextUntil = null
        val needle = "</$close"
        var search = pos
        while (true) {
            val hit = src.indexOf(needle, search, ignoreCase = true)
            if (hit < 0) {
                pos = src.length
                return
            }
            // The char after the name must be whitespace, '/' or '>' — else
            // it's a longer tag name (`</scriptx`), keep searching.
            val after = hit + needle.length
            val nextCh = src.getOrNull(after)
            if (nextCh == null || nextCh.isWhitespace() || nextCh == '/' || nextCh == '>') {
                // Position just past the close tag's '>'.
                val end = src.indexOf('>', after)
                pos = if (end < 0) src.length else end + 1
                return
            }
            search = hit + 1
        }
    }

    private fun finish(name: String, attrs: Map<String, String>, selfClosing: Boolean): Token {
        if (!selfClosing && (name == "script" || name == "style")) {
            rawTextUntil = name
        }
        return Token.Open(name, attrs, selfClosing)
    }

    companion object {
        /**
         * Decode HTML entities in text / attribute values. Unknown or broken
         * entities pass through literally (browser-tolerant); a trailing
         * semicolon is optional after a valid named/numeric reference.
         *
         * Implementation lives in the public [HtmlEntities] so other module
         * producers (htmlbook / fb2) can decode plain text without reaching
         * into this internal tokenizer.
         */
        fun decodeEntities(s: String): String = HtmlEntities.decode(s)
    }
}
