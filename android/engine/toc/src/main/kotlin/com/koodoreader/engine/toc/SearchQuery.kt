package com.koodoreader.engine.toc

/**
 * A full-text search query.
 *
 * @property bookKey stable book key (mirrors [ReadingPosition.bookKey]).
 * @property query the search term (may be multi-word; the implementation does
 *   a literal substring match, not a query-language parse).
 * @property caseSensitive `true` for a case-sensitive match, `false` for
 *   case-insensitive (the default and the web engine's behaviour).
 */
data class SearchQuery(
    val bookKey: String,
    val query: String,
    val caseSensitive: Boolean = false,
)
