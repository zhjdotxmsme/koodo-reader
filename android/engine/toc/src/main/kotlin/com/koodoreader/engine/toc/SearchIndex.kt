package com.koodoreader.engine.toc

import com.koodoreader.engine.cfi.CfiFake

/**
 * Full-text search index over the chapters of one book.
 *
 * ## Design
 *
 * The index is built from a list of [ChapterText] by precomputing the global
 * character offset of each chapter's start. Search is a linear scan over all
 * chapters (O(total_chars) per query — acceptable for book-length text on mobile),
 * with per-chapter substring search via [String.indexOf].
 *
 * CFI targets are constructed as:
 * ```
 * CfiFake.fromIndex(spineIndex) + "!/2/0" + ":" + localOffset
 * ```
 * where `localOffset` is the character position inside the chapter's text.
 *
 * Context (±40 characters) is sliced directly from the source text.
 *
 * ## Thread safety
 *
 * Instances are immutable after construction and are safe to share across threads.
 *
 * ## Comparison with the desktop engine
 *
 * The web engine (`kookit` `navigationUtil.ts` `getSearchResult`) uses DOM
 * `getBlockElement` and builds 100-character excerpts. This implementation
 * matches its semantics but produces ±40 characters (a tighter window that
 * maps better to small mobile screens).
 */
class SearchIndex private constructor(
    private val bookKey: String,
    private val chapters: List<IndexedChapterText>,
) {
    /** [ChapterText] paired with its precomputed global text offset. */
    private data class IndexedChapterText(
        val spineIndex: Int,
        val title: String?,
        val text: String,
        val cfiStart: String,
        val startOffset: Int, // global character offset of chapter start
    )

    companion object {
        private const val CONTEXT_LEN = 40

        /**
         * Build a [SearchIndex] for one book.
         *
         * @param bookKey stable book key.
         * @param chapters each spine item's plain text in reading order; [ChapterText.text]
         *   must be the raw, untruncated chapter text (the index stores it once).
         */
        fun build(bookKey: String, chapters: List<ChapterText>): SearchIndex {
            var offset = 0
            val indexed = chapters.map { ch ->
                val ic = IndexedChapterText(
                    spineIndex = ch.spineIndex,
                    title = ch.title,
                    text = ch.text,
                    cfiStart = ch.cfiStart,
                    startOffset = offset,
                )
                offset += ch.text.length
                ic
            }
            return SearchIndex(bookKey, indexed)
        }
    }

    /**
     * Search the index for [query].
     *
     * @param query the search query (empty query returns an empty list).
     * @return hits in pre-order (spineIndex ascending), each with a unique
     *   [SearchHit.rank] from 0.
     */
    fun search(query: SearchQuery): List<SearchHit> {
        if (query.query.isEmpty()) return emptyList()

        val hits = mutableListOf<SearchHit>()
        var rank = 0

        for (ch in chapters) {
            val text = ch.text
            val searchIn = if (query.caseSensitive) text else text.lowercase()
            val searchFor = if (query.caseSensitive) query.query else query.query.lowercase()

            var pos = 0
            while (true) {
                pos = searchIn.indexOf(searchFor, pos)
                if (pos == -1) break

                val matchStart = pos
                val matchEnd = pos + query.query.length

                // Extract context before (up to CONTEXT_LEN chars, clamped to start)
                val beforeStart = (matchStart - CONTEXT_LEN).coerceAtLeast(0)
                val contextBefore = text.substring(beforeStart, matchStart)

                // Extract matched text (exact case from original text)
                val matchedText = text.substring(matchStart, matchEnd)

                // Extract context after (up to CONTEXT_LEN chars, clamped to end)
                val afterEnd = (matchEnd + CONTEXT_LEN).coerceAtMost(text.length)
                val contextAfter = text.substring(matchEnd, afterEnd)

                // Build absolute CFI: spine-level CFI + chapter-local offset
                val localCfi = "/2/0:${matchStart}"
                val cfiTarget = CfiFake.fromIndex(ch.spineIndex) + "!" + localCfi

                hits.add(
                    SearchHit(
                        bookKey = bookKey,
                        spineIndex = ch.spineIndex,
                        cfiTarget = cfiTarget,
                        contextBefore = contextBefore,
                        matchedText = matchedText,
                        contextAfter = contextAfter,
                        rank = rank++,
                    ),
                )

                // Move past this match to find the next one in the same chapter
                pos = matchEnd
            }
        }

        return hits
    }
}
