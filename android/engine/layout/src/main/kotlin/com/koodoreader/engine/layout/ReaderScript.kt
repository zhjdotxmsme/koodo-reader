package com.koodoreader.engine.layout

/**
 * Per-block script classification for the pagination rules whose right answer
 * differs between Chinese and Latin typesetting.
 *
 * The only rule it answers today is the CSS `orphans`/`widows` minimum: a
 * Latin paragraph stranded with a single line looks broken, so the default is
 * 2 — but a printed Chinese book ends paragraphs (and columns) with one line
 * all the time, so for CJK-dominant text the reader relaxes the rule to 1.
 * See [PaginatorOptions.languageAwareWidowsOrphans].
 */
object ReaderScript {
    /**
     * The `orphans`/`widows` minimum for one block of [text].
     *
     * 1 when the block carries any CJK character at least as often as a Latin
     * letter (mixed-script Chinese text with the occasional Latin word typesets
     * like Chinese); otherwise [fallback] — the reader's configured minimum,
     * which also covers Latin text, digits and punctuation-only runs.
     *
     * Deterministic: a pure scan of the characters, no locale or font input.
     */
    fun widowsOrphans(text: String, fallback: Int): Int {
        var cjk = 0
        var latin = 0
        for (ch in text) {
            when {
                isCjk(ch) -> cjk++
                ch in 'A'..'Z' || ch in 'a'..'z' -> latin++
            }
        }
        return if (cjk > 0 && cjk >= latin) 1 else fallback
    }

    private fun isCjk(ch: Char): Boolean = when {
        ch in '\u3400'..'\u4DBF' -> true   // CJK Extension A
        ch in '\u4E00'..'\u9FFF' -> true   // CJK Unified Ideographs
        ch in '\uF900'..'\uFAFF' -> true   // compatibility ideographs
        ch in '\u3040'..'\u30FF' -> true   // hiragana + katakana
        ch in '\uAC00'..'\uD7A3' -> true   // Hangul syllables
        else -> false
    }
}
