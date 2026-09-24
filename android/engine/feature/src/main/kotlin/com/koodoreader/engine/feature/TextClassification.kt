package com.koodoreader.engine.feature

/**
 * Character classification shared by the P6 text features (bionic reading,
 * paragraph splitting, RSVP chunking).
 *
 * These are the only "language awareness" primitives in the module: no ICU, no
 * Android `TextUtils`, so the module stays a pure JVM library.
 */

/** True for CJK ideographs, kana, compatibility ideographs and Hangul syllables. */
fun isCjkChar(ch: Char): Boolean = when (ch.code) {
    in 0x1100..0x11FF -> true   // Hangul Jamo
    in 0x2E80..0x2EFF -> true   // CJK radicals
    in 0x3000..0x303F -> false  // CJK punctuation / ideographic space — NOT a letter
    in 0x3040..0x30FF -> true   // Hiragana + Katakana
    in 0x3400..0x4DBF -> true   // CJK unified ideographs extension A
    in 0x4E00..0x9FFF -> true   // CJK unified ideographs
    in 0xA960..0xA97F -> true   // Hangul Jamo Extended-A
    in 0xAC00..0xD7AF -> true   // Hangul syllables
    in 0xF900..0xFAFF -> true   // CJK compatibility ideographs
    else -> false
}

/**
 * True for "word" characters that may form a Latin word: letters and digits
 * that are not CJK, plus the word-internal joiners `'`, `’` and `-`.
 *
 * Joiners are only accepted when they sit *inside* a word (see
 * [scanWordEnd]); a leading hyphen therefore still opens a punctuation run.
 */
fun isWordChar(ch: Char): Boolean = ch.isLetterOrDigit() && !isCjkChar(ch)

/** Length of the word starting at [start] (`text[start]` must be a word char). */
internal fun scanWordEnd(text: String, start: Int): Int {
    var j = start
    while (j < text.length) {
        val c = text[j]
        if (isWordChar(c)) {
            j++
            continue
        }
        val joiner = c == '\'' || c == '\u2019' || c == '-'
        if (joiner && j + 1 < text.length && isWordChar(text[j + 1])) {
            j++
            continue
        }
        break
    }
    return j
}

/** True for whitespace, including the ideographic space U+3000. */
internal fun isBreakSpace(ch: Char): Boolean = ch.isWhitespace() || ch == '\u3000'
