package com.koodoreader.reader.shell

/**
 * Pure gesture + chrome layout for the native reader screens.
 *
 * Kept off Compose so JVM tests can pin the contract: long-press (and
 * double-tap) open the selection menu; the TOC button is a primary chrome
 * action so it cannot overflow off a phone-sized TopAppBar.
 */
enum class ReaderPageGesture {
    PREV_PAGE,
    NEXT_PAGE,
    TOGGLE_CHROME,
    SELECT,
}

enum class ReaderChromeAction {
    TOC,
    SEARCH,
    BOOKMARK,
    FONT,
    TTS,
    ;

    companion object {
        /** Always visible on the reading TopAppBar. */
        val PRIMARY: List<ReaderChromeAction> = listOf(TOC, SEARCH)

        /** Overflow ("more") menu — must not hide [PRIMARY]. */
        val OVERFLOW: List<ReaderChromeAction> = listOf(FONT, BOOKMARK, TTS)
    }
}

object ReaderPageGestures {

    fun onTap(x: Float, widthPx: Float, edgeFraction: Float = 1f / 3f): ReaderPageGesture {
        if (widthPx <= 0f) return ReaderPageGesture.TOGGLE_CHROME
        val edge = (widthPx * edgeFraction).coerceAtLeast(1f)
        return when {
            x < edge -> ReaderPageGesture.PREV_PAGE
            x > widthPx - edge -> ReaderPageGesture.NEXT_PAGE
            else -> ReaderPageGesture.TOGGLE_CHROME
        }
    }

    fun onLongPress(): ReaderPageGesture = ReaderPageGesture.SELECT

    fun onDoubleTap(): ReaderPageGesture = ReaderPageGesture.SELECT
}
