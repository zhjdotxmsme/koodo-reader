package com.koodoreader.reader.reader

/**
 * Reader progress display model. Total page counts are only known for
 * fixed-layout books (PDF) or the currently laid-out chapter; reflow books
 * stay [PageCountState.Pending] instead of forcing a whole-book pagination.
 */
object ReaderProgressModel {

    sealed interface PageCountState {
        data object Pending : PageCountState
        data class Ready(val value: Int) : PageCountState
    }

    fun pageState(totalPages: Int?): PageCountState =
        if (totalPages == null || totalPages < 0) PageCountState.Pending
        else PageCountState.Ready(totalPages)

    /** Minutes remaining at [charsPerMinute]; null for unknown/invalid rates. */
    fun remainingMinutes(charsRemaining: Int, charsPerMinute: Int): Int? =
        if (charsRemaining < 0 || charsPerMinute <= 0) null
        else (charsRemaining + charsPerMinute - 1) / charsPerMinute
}
