package com.koodoreader.feature.tts

/**
 * Port of the desktop listening-text segmentation.
 *
 * Desktop sources (both in `src/utils/common.ts`):
 *  - `detectLocalLanguage(text)` (line 1032) — CJK ratio test, returns `en` /
 *    `zh` / `ja` / `ko`.
 *  - `splitSentences(text, maxLength?)` (line 1172) — `Intl.Segmenter`
 *    sentence segmentation, trimming, long-sentence splitting on CJK/western
 *    punctuation with a greedy merge, and a final "must contain a letter or
 *    digit" filter.
 *
 * The two deviations from a literal port, both documented in
 * `docs/p6-tts-semantics-mapping.md`:
 *  1. `Intl.Segmenter` has no JVM equivalent (ICU `BreakIterator` would pull in
 *     a dependency and differ per Android version), so sentences are cut on an
 *     explicit terminator set with abbreviation guards.
 *  2. Kotlin `String.length` is UTF-16 code units, exactly like JavaScript's,
 *     so the length maths — and therefore the 150 / 50 chunking — matches.
 *
 * Pure Kotlin, no `android.*` import.
 */
object TtsLanguageDetector {

    /** Desktop `cjkTotal / text.length <= 0.3` → `en`. */
    const val CJK_RATIO_THRESHOLD = 0.3

    private val CHINESE = Regex("[\\u4e00-\\u9fff\\u3000-\\u303f\\uf900-\\ufaff]")
    private val JAPANESE = Regex("[\\u3040-\\u309f\\u30a0-\\u30ff]")
    private val KOREAN = Regex("[\\uac00-\\ud7af\\u1100-\\u11ff]")

    /** Desktop `detectLocalLanguage`: `en` unless CJK characters exceed 30% of the text. */
    fun detect(text: String): String {
        if (text.isEmpty()) return "en"
        val chinese = CHINESE.findAll(text).count()
        val japanese = JAPANESE.findAll(text).count()
        val korean = KOREAN.findAll(text).count()
        val cjkTotal = chinese + japanese + korean
        if (cjkTotal.toDouble() / text.length.toDouble() <= CJK_RATIO_THRESHOLD) return "en"
        if (chinese >= japanese && chinese >= korean) return "zh"
        if (japanese >= chinese && japanese >= korean) return "ja"
        return "ko"
    }
}

/**
 * Sentence queue builder for the TTS playback loop.
 *
 * One `TtsUtterance` per returned chunk is what `TextToSpeech.speak` is called
 * with, which is exactly the desktop `nodeList` granularity: desktop builds
 * `splitSentences(...)` output, flattens it, and caches one audio file per entry
 * (`textToSpeech/component.tsx` → `handleGetText`, `TTSUtil.cacheAudio`).
 */
object TtsSentenceSplitter {

    /** Desktop: `lang === "en" ? 150 : 50`. */
    const val MAX_LENGTH_EN = 150

    /** Desktop: `lang === "en" ? 150 : 50`. */
    const val MAX_LENGTH_CJK = 50

    /** Characters that end a sentence for the segmentation pass. */
    private const val TERMINATORS = ".!?。！？…\n\r"

    /** Closing punctuation / quotes that stay glued to the sentence they close. */
    private const val TRAILING_CLOSERS = "\"'”’」』）)】》〉»"

    /** Desktop: `sentence.split(/(?<=[,，;；:：、…])/)`. */
    private val SOFT_BREAK = Regex("(?<=[,，;；:：、…])")

    /** Desktop: `/[\p{L}\p{N}]/u` — the chunk must carry at least one letter or digit. */
    private val HAS_LETTER_OR_DIGIT = Regex("[\\p{L}\\p{N}]")

    /**
     * Desktop default chunk length for [text]'s detected language.
     *
     * `maxLength` in the desktop signature overrides this; on Android the same
     * override comes from [TtsConfig.chunkLength] (`0` = auto).
     */
    fun defaultMaxLength(language: String): Int =
        if (language == "en") MAX_LENGTH_EN else MAX_LENGTH_CJK

    /**
     * Split [text] into speakable chunks.
     *
     * @param maxLength `null` → [defaultMaxLength] for the detected language.
     * @return non-empty, trimmed chunks, each containing at least one letter or
     *   digit — the same post-conditions as the desktop function.
     */
    fun split(text: String, maxLength: Int? = null): List<String> {
        if (text.isBlank()) return emptyList()
        val resolvedMax = maxLength?.takeIf { it > 0 } ?: defaultMaxLength(TtsLanguageDetector.detect(text))
        return segment(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { splitLongSentence(it, resolvedMax) }
            .filter { HAS_LETTER_OR_DIGIT.containsMatchIn(it) }
    }

    /** Split [text] into [TtsUtterance]s carrying their offsets in [text]. */
    fun splitWithOffsets(text: String, maxLength: Int? = null): List<TtsUtterance> {
        val chunks = split(text, maxLength)
        if (chunks.isEmpty()) return emptyList()
        val out = ArrayList<TtsUtterance>(chunks.size)
        var cursor = 0
        for ((index, chunk) in chunks.withIndex()) {
            val start = text.indexOf(chunk, cursor).let { if (it >= 0) it else cursor }
            val end = start + chunk.length
            out += TtsUtterance(index = index, text = chunk, charStart = start, charEnd = end)
            cursor = end
        }
        return out
    }

    /**
     * Terminator-based sentence segmentation (the `Intl.Segmenter` stand-in).
     *
     * Guards that keep common non-breaks intact:
     *  - `.` followed by a digit ("3.14") or another `.` ("...") does not split,
     *  - closing quotes/brackets after a terminator stay with the finished sentence.
     */
    private fun segment(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            current.append(c)
            i++
            if (TERMINATORS.indexOf(c) < 0) continue
            if (c == '.') {
                val next = text.getOrNull(i)
                // Ellipsis ("...") never ends a sentence: neither when more dots follow
                // nor when this dot closes a run of them.
                if (next != null && next == '.') continue
                if (i >= 2 && text[i - 2] == '.') continue
                // Decimal number ("3.14"): keep going.
                if (next != null && next.isDigit()) continue
            }
            // Absorb closers and any run of terminators ("?!", "。”").
            while (i < text.length && (TRAILING_CLOSERS.indexOf(text[i]) >= 0 || TERMINATORS.indexOf(text[i]) >= 0)) {
                current.append(text[i])
                i++
            }
            out += current.toString()
            current.setLength(0)
        }
        if (current.isNotBlank()) out += current.toString()
        return out
    }

    /** Desktop `splitLongSentence`: greedy merge of soft-break parts, or the sentence as-is. */
    private fun splitLongSentence(sentence: String, maxLength: Int): List<String> {
        if (sentence.length <= maxLength) return listOf(sentence)
        val parts = SOFT_BREAK.split(sentence)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size <= 1) return listOf(sentence)

        val result = ArrayList<String>(parts.size)
        var current = ""
        for (part in parts) {
            val candidate = if (current.isEmpty()) part else current + part
            if (candidate.length <= maxLength) {
                current = candidate
            } else {
                if (current.isNotEmpty()) result += current
                // A single part longer than the limit is kept whole (desktop parity).
                current = part
            }
        }
        if (current.isNotEmpty()) result += current
        return result
    }
}
