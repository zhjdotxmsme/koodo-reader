package com.koodoreader.engine.feature

import kotlin.math.ceil

/** One rendered slice of a bionic-reading split. */
data class BionicRun(val text: String, val emphasized: Boolean)

/** How many leading characters of a word get emphasised. */
enum class BionicEmphasis {
    /** Always the first [BionicConfig.boldChars] characters (P6 default). */
    FIXED_CHARS,

    /**
     * First half of the word (rounded up), i.e. the desktop setting
     * `isBionic` — "Fast reading mode (make the first half of the word bold)".
     */
    HALF_WORD,
}

/**
 * Emphasis policy for CJK characters.
 *
 * CJK text has no word separators, so "bold the first N characters of every
 * word" degenerates (a Chinese "word" is 1–2 characters, so everything would be
 * bold). The default therefore leaves CJK runs unemphasised and only bolds the
 * Latin words around them.
 */
enum class CjkEmphasis {
    /** Never emphasise CJK characters (default). */
    NONE,

    /** Emphasise every CJK character. */
    ALL,

    /** Emphasise the first [BionicConfig.boldChars] characters of each CJK sequence. */
    SEQUENCE_LEADING,
}

/**
 * @param boldChars   leading characters emphasised per word (default 3).
 * @param emphasis    emphasis-length strategy; see [BionicEmphasis].
 * @param cjkEmphasis CJK policy; see [CjkEmphasis].
 */
data class BionicConfig(
    val boldChars: Int = DEFAULT_BOLD_CHARS,
    val emphasis: BionicEmphasis = BionicEmphasis.FIXED_CHARS,
    val cjkEmphasis: CjkEmphasis = CjkEmphasis.NONE,
) {
    /** Effective prefix length, never below 1. */
    val safeBoldChars: Int get() = boldChars.coerceAtLeast(1)

    companion object {
        const val DEFAULT_BOLD_CHARS = 3

        /** Desktop `isBionic` parity: first half of each word. */
        val DESKTOP_PARITY: BionicConfig =
            BionicConfig(emphasis = BionicEmphasis.HALF_WORD)
    }
}

/**
 * Bionic reading (a.k.a. "fast reading mode"): split a text into runs where the
 * leading part of every word is emphasised, so the eye is guided down the page.
 *
 * Contract:
 *  - `split(text).joinToString("") { it.text } == text` — the split is lossless,
 *    the reader can rebuild the paragraph (or map runs back onto source offsets).
 *  - A word shorter than the emphasis length becomes a *single* emphasised run.
 *  - Punctuation and whitespace never start a run of their own once a run
 *    exists: they are appended to the previous run ("标点归前段"), keeping the
 *    run count low enough for the renderer.
 *  - CJK characters are emitted one run per character (so the renderer can apply
 *    per-glyph ruby/spacing), governed by [CjkEmphasis].
 *
 * Reference: kookit `bionicUtil` (docs/android-native-migration.md, P6).
 */
object BionicReading {

    /**
     * Split [text] into emphasised / plain runs.
     *
     * @return an empty list for an empty input; a single plain run for input
     *   that contains no word characters (e.g. `"..."`).
     */
    fun split(text: String, config: BionicConfig = BionicConfig()): List<BionicRun> {
        if (text.isEmpty()) return emptyList()

        val runs = ArrayList<BionicRun>()
        // Punctuation / whitespace waiting to be attached to the previous run.
        val pending = StringBuilder()

        fun flushPending() {
            if (pending.isEmpty()) return
            val tail = pending.toString()
            pending.setLength(0)
            if (runs.isEmpty()) {
                runs.add(BionicRun(tail, false))
            } else {
                val last = runs.removeAt(runs.size - 1)
                runs.add(BionicRun(last.text + tail, last.emphasized))
            }
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (isCjkChar(ch)) {
                flushPending()
                var j = i
                while (j < text.length && isCjkChar(text[j])) j++
                val leading =
                    if (config.cjkEmphasis == CjkEmphasis.SEQUENCE_LEADING) config.safeBoldChars else 0
                for (k in i until j) {
                    val emphasized = when (config.cjkEmphasis) {
                        CjkEmphasis.NONE -> false
                        CjkEmphasis.ALL -> true
                        CjkEmphasis.SEQUENCE_LEADING -> k - i < leading
                    }
                    runs.add(BionicRun(text[k].toString(), emphasized))
                }
                i = j
            } else if (isWordChar(ch)) {
                flushPending()
                val end = scanWordEnd(text, i)
                val word = text.substring(i, end)
                val n = emphasisLength(word, config)
                if (n >= word.length) {
                    runs.add(BionicRun(word, true))
                } else {
                    runs.add(BionicRun(word.substring(0, n), true))
                    runs.add(BionicRun(word.substring(n), false))
                }
                i = end
            } else {
                pending.append(ch)
                i++
            }
        }
        flushPending()
        return runs
    }

    /** Convenience: the emphasised prefix length for [word] under [config]. */
    fun emphasisLength(word: String, config: BionicConfig = BionicConfig()): Int {
        if (word.isEmpty()) return 0
        val n = when (config.emphasis) {
            BionicEmphasis.FIXED_CHARS -> config.safeBoldChars
            BionicEmphasis.HALF_WORD -> ceil(word.length / 2.0).toInt()
        }
        return n.coerceIn(1, word.length)
    }

    /**
     * Render [text] as an HTML fragment with `<b>` around emphasised runs.
     * Text content is escaped; used by the reader when injecting native styles.
     */
    fun toHtml(text: String, config: BionicConfig = BionicConfig()): String {
        val sb = StringBuilder(text.length + 16)
        for (run in split(text, config)) {
            if (run.emphasized) sb.append("<b>").append(escapeHtml(run.text)).append("</b>")
            else sb.append(escapeHtml(run.text))
        }
        return sb.toString()
    }

    private fun escapeHtml(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
