package com.koodoreader.engine.toc

/**
 * One full-text search result hit.
 *
 * Produced by [SearchIndex.search]. The caller uses [cfiTarget] to navigate
 * to the hit via [com.koodoreader.engine.cfi] + [com.koodoreader.engine.layout.CfiAddressing].
 *
 * @property bookKey stable book key (mirrors [ReadingPosition.bookKey]).
 * @property spineIndex 0-based chapter index where the hit occurs.
 * @property cfiTarget an absolute epubcfi: CFI pointing to the hit's text node.
 * @property contextBefore up to 40 characters immediately before the match, or
 *   empty string when the match is at the start of the chapter.
 * @property matchedText the exact matched substring (case matches the query
 *   when [SearchQuery.caseSensitive] is `true`).
 * @property contextAfter up to 40 characters immediately after the match, or
 *   empty string when the match is at the end of the chapter.
 * @property rank 0-based ordinal of this hit within the full result set returned
 *   by [SearchIndex.search].
 */
data class SearchHit(
    val bookKey: String,
    val spineIndex: Int,
    val cfiTarget: String,
    val contextBefore: String,
    val matchedText: String,
    val contextAfter: String,
    val rank: Int,
)
