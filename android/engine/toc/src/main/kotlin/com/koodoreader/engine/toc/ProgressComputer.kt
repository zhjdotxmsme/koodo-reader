package com.koodoreader.engine.toc

import com.koodoreader.engine.cfi.Cfi
import com.koodoreader.engine.cfi.parseOrNull

/**
 * Chapter descriptor used by [ProgressComputer].
 *
 * @property lengthChars the chapter's character count (used for progress calculation;
 *   pass 1 for equal-weight chapters when character counts are not yet known).
 * @property cfiStart the chapter's starting CFI (e.g. from [CfiFake.fromIndex]).
 */
data class SpineChapter(
    val lengthChars: Int,
    val cfiStart: String,
)

/**
 * Computes reading-progress fractions from a spine index and chapter-local CFI.
 *
 * No absolute page numbers are produced — this is intentional (R3 constraint:
 * the browser and native pagination produce different page counts, but character
 * counts are stable across both).
 *
 * @param spine ordered list of spine chapters; [ProgressComputer] sums their
 *   lengths to compute documentPercent.
 */
class ProgressComputer(private val spine: List<SpineChapter>) {

    init {
        require(spine.isNotEmpty()) { "Spine must not be empty" }
    }

    /**
     * Compute chapter and document percentages for a position at [spineIndex]
     * with a [cfi] inside that chapter.
     *
     * @param spineIndex the 0-based chapter index.
     * @param cfi the chapter-local CFI (without package prefix). Used to
     *   extract the character offset when the CFI carries one.
     * @return a pair of `(chapterPercent, totalPercent)`, both in 0.0–1.0.
     * @throws IndexOutOfBoundsException when [spineIndex] is out of range.
     */
    fun compute(spineIndex: Int, cfi: String): Pair<Float, Float> {
        // The KDoc and every caller expect IndexOutOfBoundsException; `require` would
        // have produced IllegalArgumentException instead.
        if (spineIndex !in spine.indices) {
            throw IndexOutOfBoundsException(
                "spineIndex $spineIndex out of range [0,${spine.lastIndex}]",
            )
        }

        val charOffset = extractCharOffset(cfi)
        val chapterChars = spine[spineIndex].lengthChars

        // Chapter percent: position inside this chapter.
        val chapterPercent = if (chapterChars > 0) {
            (charOffset.toFloat() / chapterChars).coerceIn(0f, 1f)
        } else {
            0f
        }

        // Total percent: sum of all preceding chapters + position in current.
        val totalBefore = spine.take(spineIndex).sumOf { it.lengthChars }
        val totalChars = spine.sumOf { it.lengthChars }
        val totalPercent = if (totalChars > 0) {
            ((totalBefore + charOffset).toFloat() / totalChars).coerceIn(0f, 1f)
        } else {
            0f
        }

        return chapterPercent to totalPercent
    }

    /**
     * Extract the character offset from a chapter-local CFI.
     *
     * Two shapes are accepted, because the reader layer produces both:
     *
     *  - the module's **shorthand**: a lone `/n` IS the character offset (`/0` = start
     *    of the chapter, `/1000` = 1000 characters in). This is the form
     *    [firstChapterPercent] / [lastTotalPercent] build, and the form the unit tests
     *    and [TocSelfCheck] drive the engine with.
     *  - a **real CFI** that carries an explicit character offset — `/4/2/2:1234` or
     *    `/6/4!/4/2:10` → the `:offset` of the last step that has one.
     *
     * Anything else (a multi-step CFI with no explicit offset, or unparseable input)
     * yields 0.
     */
    private fun extractCharOffset(cfi: String): Int {
        val trimmed = cfi.trim()
        if (trimmed.isEmpty()) return 0

        // Shorthand first: `parseOrNull("/1000")` would read 1000 as a STEP index.
        BARE_OFFSET.matchEntire(trimmed)?.let { match ->
            return match.groupValues[1].toIntOrNull() ?: 0
        }

        return try {
            val documents = when (val parsed = parseOrNull(trimmed)) {
                null -> return 0
                is Cfi.Point -> parsed.documents
                is Cfi.Range -> parsed.start
            }
            documents.asSequence().flatten().lastOrNull { it.offset != null }?.offset ?: 0
        } catch (_: Exception) {
            0
        }
    }

    companion object {
        /** The bare-offset shorthand: `/` followed by digits, nothing else. */
        private val BARE_OFFSET = Regex("^/(\\d+)$")

        /**
         * Convenience: compute progress for the first character of the first
         * chapter (should be 0.0, 0.0).
         */
        fun firstChapterPercent(spine: List<SpineChapter>): Float {
            require(spine.isNotEmpty())
            val computer = ProgressComputer(spine)
            return computer.compute(0, "/0").first
        }

        /**
         * Convenience: compute progress for the last character of the last
         * chapter (should be ≈ 1.0, ≈ 1.0; allow tiny float epsilon).
         */
        fun lastTotalPercent(spine: List<SpineChapter>): Float {
            require(spine.isNotEmpty())
            val computer = ProgressComputer(spine)
            val lastIdx = spine.lastIndex
            val lastChapter = spine[lastIdx]
            // Use the chapter length as the offset to point to the last character.
            return computer.compute(lastIdx, "/${lastChapter.lengthChars}").second
        }
    }
}
