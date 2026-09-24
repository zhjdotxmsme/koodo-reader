package com.koodoreader.engine.toc

/**
 * The reading position for one book.
 *
 * Stored as a JSON string via [PositionCodec] so it can be persisted in Room.
 * This is the **data model only** — the UI layer (progress HUD, position
 * restoration) consumes this class directly; no absolute page numbers are ever
 * produced here (R3 constraint).
 *
 * @property bookKey stable key for the book (same as in [BookEntity]).
 * @property spineIndex the current spine (chapter) index, 0-based.
 * @property cfi the chapter-local CFI string (without the package prefix), e.g.
 *   `/4/2/2:10`. Use [CfiFake.fromIndex] + `!` to form a full CFI.
 * @property chapterPercent position inside the chapter as a fraction 0.0–1.0.
 *   Computed by [ProgressComputer] from the spine item lengths.
 * @property totalPercent position inside the whole book as a fraction 0.0–1.0.
 *   Computed by [ProgressComputer] from all spine item lengths.
 */
data class ReadingPosition(
    val bookKey: String,
    val spineIndex: Int,
    val cfi: String,
    val chapterPercent: Float,
    val totalPercent: Float,
) {
    init {
        require(spineIndex >= 0) { "spineIndex must be >= 0 (was $spineIndex)" }
        require(chapterPercent in 0f..1f) { "chapterPercent must be in [0,1] (was $chapterPercent)" }
        require(totalPercent in 0f..1f) { "totalPercent must be in [0,1] (was $totalPercent)" }
    }
}
