package com.koodoreader.engine.layout

/**
 * Line-break opportunity rules — the CSS `word-break` / `line-break` subset the
 * pagination needs.
 *
 * CSS defines break opportunities per *character pair* (a break is allowed
 * between characters `a` and `b` when the `line-break` property permits it).
 * The full table is huge (UAX #14); this module implements the three cases that
 * actually occur in EPUB text:
 *
 *  1. a break **after** a collapsible whitespace character (all modes);
 *  2. a break **between two adjacent CJK ideographs** (NORMAL only — the
 *     browser default `line-break: auto` allows it, which is how CJK text
 *     wraps without spaces);
 *  3. a break between any two characters (BREAK_ALL — the mode the desktop
 *     engine applies to CJK paragraphs via its default stylesheet).
 *
 * KEEP_ALL keeps only rule 1, which is the CJK "禁则"/`word-break: keep-all`
 * behaviour the desktop reader offers for Western-language CJK paragraphs.
 *
 * CJK Ext-B ideographs (U+20000+) are surrogate pairs in UTF-16, so a
 * per-character scan cannot see them; those characters therefore only break
 * under BREAK_ALL (or through [OverflowWrap.BREAK_WORD] in the shaper). This is
 * a deliberate, documented approximation — real CJK Ext-B text is rare in
 * EPUBs and the desktop engine (browser) handles it natively.
 */

/** True for a collapsible whitespace character (CSS `white-space: normal`). */
fun isCollapsibleWhitespace(ch: Char): Boolean = when (ch) {
    ' ', '\t', '\n', '\r', '\u000C', '\u000B' -> true
    else -> false
}

/**
 * True for a character the browser treats as breakable between neighbours:
 * CJK ideographs, kana, Hangul, and fullwidth forms.
 */
fun isIdeograph(ch: Char): Boolean = when {
    ch in '\u3400'..'\u4DBF' -> true // CJK Extension A
    ch in '\u4E00'..'\u9FFF' -> true // CJK Unified Ideographs
    ch in '\uF900'..'\uFAFF' -> true // CJK Compatibility Ideographs
    ch in '\u3040'..'\u30FF' -> true // Hiragana + Katakana
    ch in '\u3005'..'\u3007' -> true // vertical ideographic description
    ch in '\uAC00'..'\uD7A3' -> true // Hangul Jamo + Syllables
    ch in '\uFF00'..'\uFF60' -> true // fullwidth forms
    else -> false
}

/**
 * All indices `e` at which a line **may end** (i.e. break *after* char `e - 1`),
 * in ascending order. `0` is never included: a line must be non-empty.
 */
fun breakEnds(text: String, wordBreak: WordBreak): IntArray {
    when {
        text.isEmpty() -> return IntArray(0)

        wordBreak == WordBreak.BREAK_ALL ->
            return IntArray(text.length) { i -> i + 1 }

        else -> {
            val out = ArrayList<Int>()
            for (i in 1..text.length) {
                val before = text[i - 1]
                val breakable = when {
                    isCollapsibleWhitespace(before) -> true
                    wordBreak == WordBreak.NORMAL && i < text.length &&
                        isIdeograph(before) && isIdeograph(text[i]) -> true
                    else -> false
                }
                if (breakable) out.add(i)
            }
            return IntArray(out.size) { i -> out[i] }
        }
    }
}

/**
 * Trim the trailing whitespace of a line, since the browser moves a line-ending
 * space to the next line and drops it there.
 *
 * @return `end` moved back past any trailing collapsible whitespace, never below
 *   [start] (a whitespace-only remainder is kept as-is so the shaper always
 *   advances).
 */
fun trimTrailingWhitespace(text: String, start: Int, end: Int): Int {
    var e = end
    while (e > start && isCollapsibleWhitespace(text[e - 1])) e--
    return if (e == start) end else e
}
