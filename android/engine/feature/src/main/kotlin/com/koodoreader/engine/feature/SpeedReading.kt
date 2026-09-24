package com.koodoreader.engine.feature

/**
 * One RSVP frame: the text to flash, the index of its optimal recognition point
 * and how long it stays on screen.
 *
 * @param text       the token to display (whitespace already removed).
 * @param orpIndex   0-based index of the optimal recognition point inside [text];
 *                   the renderer aligns this character with the fixed pivot.
 * @param durationMs dwell time in milliseconds.
 */
data class SpeedChunk(val text: String, val orpIndex: Int, val durationMs: Int)

/**
 * RSVP / "speed reading" chunker.
 *
 * `text → [SpeedChunk]`, mirroring the desktop `speedReadingUtil` settings:
 *  - `speedReadingSpeed` — words per minute, slider range 100…900, default 300
 *    (`src/components/readerSettings/settingSwitch/component.tsx`).
 *  - base dwell = `60_000 / wpm` ms per word.
 *  - punctuation adds a pause (sentence end ×2, clause ×1.5) and long words get
 *    extra dwell, so the reader is not rushed through the hard parts.
 *
 * Reference: kookit `speedReadingUtil` (docs/android-native-migration.md, P6).
 *
 * @param wpm          target reading speed, clamped to [MIN_WPM]…[MAX_WPM].
 * @param maxChunkChars longest token handed to the renderer; longer tokens (a
 *   CJK sentence without spaces, a URL, …) are split so no frame overflows.
 */
class SpeedReading(
    wpm: Int = DEFAULT_WPM,
    private val maxChunkChars: Int = DEFAULT_MAX_CHUNK_CHARS,
) {

    /** Effective speed after clamping to the desktop slider range. */
    val wpm: Int = wpm.coerceIn(MIN_WPM, MAX_WPM)

    /** Base dwell time of one word, before punctuation / length adjustments. */
    val baseDurationMs: Double get() = 60_000.0 / wpm

    /** Split [text] into RSVP frames. Blank input yields an empty list. */
    fun chunks(text: String): List<SpeedChunk> {
        if (text.isBlank()) return emptyList()
        return tokenize(text).map { chunkFor(it) }
    }

    /** Total playback time of [chunks] in milliseconds. */
    fun totalDurationMs(chunks: List<SpeedChunk>): Int = chunks.sumOf { it.durationMs }

    /**
     * Which frame is on screen after [elapsedMs] of playback.
     *
     * @return the frame index, or `-1` for an empty chunk list. Times past the
     *   end clamp to the last frame.
     */
    fun chunkAt(chunks: List<SpeedChunk>, elapsedMs: Int): Int {
        if (chunks.isEmpty()) return -1
        var acc = 0
        for ((index, chunk) in chunks.withIndex()) {
            acc += chunk.durationMs
            if (elapsedMs < acc) return index
        }
        return chunks.size - 1
    }

    /** Effective dwell time for [token], exposed for tests and the settings preview. */
    fun durationFor(token: String): Int =
        (baseDurationMs * durationMultiplier(token)).toInt().coerceAtLeast(1)

    // ---------------------------------------------------------------- internals

    private fun chunkFor(token: String): SpeedChunk =
        SpeedChunk(token, orpIndex(token), durationFor(token))

    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (isBreakSpace(ch)) {
                if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())

        val limit = maxChunkChars.coerceAtLeast(1)
        if (out.all { it.length <= limit }) return out

        val capped = ArrayList<String>(out.size)
        for (token in out) {
            if (token.length <= limit) capped.add(token) else capped.addAll(token.chunked(limit))
        }
        return capped
    }

    companion object {
        const val DEFAULT_WPM = 300
        const val MIN_WPM = 100
        const val MAX_WPM = 900
        const val DEFAULT_MAX_CHUNK_CHARS = 12

        /** Words longer than this get extra dwell time. */
        const val LONG_WORD_CHARS = 8

        /**
         * Spritz-style optimal recognition point (0-based), by word length:
         * 1 → 0, 2–5 → 1, 6–9 → 2, 10–13 → 3, ≥14 → 4.
         */
        fun orpIndex(word: String): Int {
            if (word.isEmpty()) return 0
            val pivot = when {
                word.length <= 1 -> 0
                word.length <= 5 -> 1
                word.length <= 9 -> 2
                word.length <= 13 -> 3
                else -> 4
            }
            return pivot.coerceAtMost(word.length - 1)
        }

        /**
         * Dwell-time multiplier: ×2 for a sentence-ending punctuation mark, ×1.5
         * for a clause separator, plus 5% per character beyond
         * [LONG_WORD_CHARS] (capped at +100%).
         */
        fun durationMultiplier(word: String): Double {
            var multiplier = 1.0
            if (word.length > LONG_WORD_CHARS) {
                multiplier += minOf(1.0, (word.length - LONG_WORD_CHARS) * 0.05)
            }
            val last = word.lastOrNull() ?: return multiplier
            val pause = when (last) {
                '.', '!', '?', '\u3002', '\uFF01', '\uFF1F', '\u2026' -> 2.0
                ',', ';', ':', '\uFF0C', '\uFF1B', '\uFF1A', '\u3001' -> 1.5
                else -> 1.0
            }
            return multiplier * pause
        }
    }
}
