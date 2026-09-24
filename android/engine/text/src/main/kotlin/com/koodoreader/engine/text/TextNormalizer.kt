package com.koodoreader.engine.text

/**
 * Text clean-up applied right after decoding and *before* chapter splitting, so
 * that every offset the reader stores is expressed in the normalised string.
 *
 * The desktop relies on the browser to do part of this (`TextDecoder` BOM
 * handling, `marked`/HTML whitespace collapsing) and does the rest ad hoc in
 * `textProcessor.cleanText()` (`trim` + strip `\r\n\t`). A native engine needs
 * it explicit and reproducible, because page/CFI anchors are computed from the
 * result.
 *
 * Defaults are chosen to be *lossless for reading position*: line endings are
 * unified (1 byte → 1 char, so it changes offsets but deterministically),
 * BOMs are dropped, and only characters that cannot be rendered are removed.
 */
object TextNormalizer {

    /** Options; `NormalizeOptions.Default` is the recommended pipeline. */
    data class Options(
        /** `\r\n` / `\r` → `\n`. */
        val unifyLineEndings: Boolean = true,
        /** Drop a leading U+FEFF (and the UTF-8/UTF-16 BOM chars if still present). */
        val stripBom: Boolean = true,
        /** Remove C0/C1 control characters other than `\t`/`\n`. */
        val stripControlChars: Boolean = true,
        /** Drop U+200B..U+200D / U+2060 / U+FEFF zero-width characters. */
        val stripZeroWidth: Boolean = true,
        /** `\t` → single space (tabs inside a paragraph break column alignment). */
        val tabsToSpaces: Boolean = false,
        /** Collapse runs of spaces/tabs into one space. */
        val collapseSpaces: Boolean = false,
        /** Collapse 3+ consecutive newlines into exactly two (paragraph gap). */
        val collapseBlankLines: Boolean = false,
        /** Normalise the exotic spaces (NBSP, ideographic space, …) to U+0020. */
        val normalizeExoticSpaces: Boolean = true,
    ) {
        companion object {
            val Default = Options()

            /** For TXT bodies: keeps paragraph structure, folds blank-line runs. */
            val Paragraphs = Options(
                collapseBlankLines = true,
                collapseSpaces = true,
                tabsToSpaces = true,
            )

            /** For metadata/titles: everything folded to single spaces, trimmed. */
            val SingleLine = Options(
                unifyLineEndings = true,
                stripBom = true,
                stripControlChars = true,
                stripZeroWidth = true,
                tabsToSpaces = true,
                collapseSpaces = true,
                collapseBlankLines = true,
            )
        }
    }

    /** Applies the default pipeline. */
    fun normalize(text: String, options: Options = Options.Default): String {
        if (text.isEmpty()) return text
        var out = text

        if (options.stripBom) {
            // Some files carry several concatenated BOMs (a known Windows
            // notepad artefact); drop every leading one, like TextDecoder does.
            var i = 0
            while (i < out.length && out[i] == '\uFEFF') i++
            if (i > 0) out = out.substring(i)
        }

        if (options.unifyLineEndings) {
            out = out.replace("\r\n", "\n").replace('\r', '\n')
        }

        if (options.normalizeExoticSpaces) {
            out = buildString(out.length) {
                for (c in out) {
                    when (c) {
                        '\u00A0', '\u2000', '\u2001', '\u2002', '\u2003', '\u2004',
                        '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200A',
                        '\u202F', '\u205F', '\u3000',
                        -> append(' ')
                        else -> append(c)
                    }
                }
            }
        }

        if (options.tabsToSpaces) out = out.replace('\t', ' ')

        if (options.stripZeroWidth) {
            out = out.filterNot {
                it == '\u200B' || it == '\u200C' || it == '\u200D' ||
                    it == '\u2060' || it == '\uFEFF'
            }
        }

        if (options.stripControlChars) {
            out = out.filterNot { it.isRemovableControl() }
        }

        if (options.collapseSpaces) {
            // Fold spaces AND tabs together, so `a \t b` cannot survive as two
            // runs (the TAB is gone, but two spaces around it must not pair up).
            out = collapseRuns(out, ' ', ' ')
            out = collapseRuns(out, '\t', '\t')
            out = out.replace(" \t", " ").replace("\t ", " ")
        }

        if (options.collapseBlankLines) {
            // Runs of 3+ newlines become exactly two (one blank line). Trailing
            // spaces/tabs are folded away FIRST — note the replacement keeps the
            // newline itself: `trimStart()` on the match would eat it, because
            // `\n` counts as whitespace.
            out = Regex("[ \t]*\n").replace(out, "\n")
            out = Regex("\n{3,}").replace(out, "\n\n")
            // Drop trailing spaces/tabs at the very end of the document.
            out = out.trimEnd(' ', '\t')
        }

        return out
    }

    /** True for characters that carry no meaning in an ebook body. */
    private fun Char.isRemovableControl(): Boolean {
        if (this == '\n' || this == '\t') return false
        // `\r` is NOT removed here on purpose: [normalize] already unified line
        // endings, and when the caller switched that off the CR is meaningful
        // (the desktop splits on `\r` for old Mac files). Only other C0/C1
        // controls are stripped.
        if (this == '\r') return false
        if (this in '\u0000'..'\u001F') return true
        if (this == '\u007F') return true
        // C1 controls, minus the ones that are legal printable text.
        if (this in '\u0080'..'\u009F') return true
        return false
    }

    /** Collapses every run of [target] into a single [replacement]. */
    private fun collapseRuns(s: String, target: Char, replacement: Char): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == target) {
                sb.append(replacement)
                while (i < s.length && s[i] == target) i++
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Convenience: normalise and `trim()`. */
    fun normalizeTrimmed(text: String, options: Options = Options.Default): String =
        normalize(text, options).trim()

    /**
     * Desktop parity for `textProcessor.cleanText`: trim, strip line breaks and
     * tabs, drop the `= - _ +` rulers, then cut at 100 characters. Used by the
     * chapter splitter when comparing titles.
     */
    fun cleanText(str: String): String {
        val trimmed = str.trim()
            .replace(Regex("(\r\n|\n|\r|\t)"), "")
        val cut = trimmed.take(100)
        return buildString(cut.length) {
            for (c in cut) {
                if (c != '=' && c != '-' && c != '_' && c != '+') append(c)
            }
        }
    }
}
