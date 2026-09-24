package com.koodoreader.engine.toc

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
        require(spineIndex in spine.indices) { "spineIndex $spineIndex out of range [0,${spine.lastIndex}]" }

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
     * A chapter-local CFI looks like `/4/2/2:10` (the `:10` is the offset).
     * When the CFI has no offset component, returns 0.
     *
     * Delegates to [com.koodoreader.engine.cfi] for parsing.
     */
    private fun extractCharOffset(cfi: String): Int {
        // Delegate to the CFI engine: parse the local CFI and extract offset.
        // A null return means no offset was present; treat as 0.
        return try {
            val parsed = com.koodoreader.engine.cfi.parseOrNull(cfi)
                ?: return 0
            val point = when (parsed) {
                is com.koodoreader.engine.cfi.Cfi.Point -> parsed
                is com.koodoreader.engine.cfi.Cfi.Range -> parsed.start.lastOrNull()
            }
            val lastStep = (point as? com.koodoreader.engine.cfi.Cfi.Point)
                ?.documents?.lastOrNull()
                ?.lastOrNull { it.offset != null }
                ?: return 0
            lastStep.offset ?: 0
        } catch (_: Exception) {
            0
        }
    }

    companion object {
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
