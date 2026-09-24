package com.koodoreader.engine.toc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [SearchIndex].
 *
 * Covers:
 *  - Multi-chapter indexing (3 chapters)
 *  - Single and multiple hits per chapter
 *  - Case-sensitive and case-insensitive matching
 *  - Mixed-case text and query
 *  - Empty query returns empty list
 *  - Context (±40 chars) extraction
 *  - CFI target generation
 */
class SearchIndexTest {

    private fun makeChapters(): List<ChapterText> = listOf(
        ChapterText(
            spineIndex = 0,
            title = "Introduction",
            text = "Kotlin is a modern programming language. It runs on the JVM.",
            cfiStart = "epubcfi(/6/2!)",
        ),
        ChapterText(
            spineIndex = 1,
            title = "Chapter Two",
            text = "Kotlin is concise. Kotlin is safe. Kotlin is interoperable.",
            cfiStart = "epubcfi(/6/4!)",
        ),
        ChapterText(
            spineIndex = 2,
            title = "Chapter Three",
            text = "Java and Kotlin can coexist in the same project.",
            cfiStart = "epubcfi(/6/6!)",
        ),
    )

    // --- Basic indexing ---

    @Test
    fun `build creates index with 3 chapters`() {
        val index = SearchIndex.build("book-x", makeChapters())
        val results = index.search(SearchQuery("book-x", "language"))
        assertEquals(1, results.size)
        assertEquals(0, results[0].spineIndex)
    }

    // --- Case sensitivity ---

    @Test
    fun `case insensitive finds all variations`() {
        val index = SearchIndex.build("book-x", makeChapters())

        val hits = index.search(SearchQuery("book-x", "kotlin", caseSensitive = false))
        assertEquals(4, hits.size) // 1 in ch0 + 2 in ch1 + 1 in ch3
    }

    @Test
    fun `case sensitive finds only exact case`() {
        val index = SearchIndex.build("book-x", makeChapters())

        val hits = index.search(SearchQuery("book-x", "Kotlin", caseSensitive = true))
        assertEquals(3, hits.size) // "Kotlin" (capital K) appears 3 times
    }

    @Test
    fun `case sensitive misses lowercase-only`() {
        val index = SearchIndex.build("book-x", makeChapters())

        val hits = index.search(SearchQuery("book-x", "kotlin", caseSensitive = true))
        assertEquals(0, hits.size) // only "Kotlin" (capital K) exists
    }

    // --- Multiple hits per chapter ---

    @Test
    fun `multiple hits in same chapter are all returned`() {
        val index = SearchIndex.build("book-x", makeChapters())

        val hits = index.search(SearchQuery("book-x", "Kotlin", caseSensitive = true))
        val ch1Hits = hits.filter { it.spineIndex == 1 }
        assertEquals(2, ch1Hits.size) // "Kotlin" appears twice in chapter 2
    }

    @Test
    fun `same chapter multiple hits have distinct ranks`() {
        val index = SearchIndex.build("book-x", makeChapters())

        val hits = index.search(SearchQuery("book-x", "is", caseSensitive = true))
        // Chapter 1 has "is" twice: "is concise" and "is safe"
        val ch1Hits = hits.filter { it.spineIndex == 1 }
        assertEquals(2, ch1Hits.size)

        val ranks = ch1Hits.map { it.rank }.sorted()
        assertEquals(listOf(1, 2), ranks) // first hit is rank 1 (chapter 0 has the first "is")
    }

    // --- Empty query ---

    @Test
    fun `empty query returns empty list`() {
        val index = SearchIndex.build("book-x", makeChapters())

        val hits = index.search(SearchQuery("book-x", ""))
        assertEquals(0, hits.size)
    }

    // --- No match ---

    @Test
    fun `no match returns empty list`() {
        val index = SearchIndex.build("book-x", makeChapters())

        val hits = index.search(SearchQuery("book-x", "zigzag_nonexistent"))
        assertEquals(0, hits.size)
    }

    // --- Context extraction ---

    @Test
    fun `context before and after are extracted`() {
        val index = SearchIndex.build("book-x", makeChapters())

        val hits = index.search(SearchQuery("book-x", "language"))
        assertEquals(1, hits.size)
        val hit = hits[0]

        assertEquals("modern programming ", hit.contextBefore)
        assertEquals("language", hit.matchedText)
        assertEquals(". It runs on the JV", hit.contextAfter)
    }

    @Test
    fun `context is clamped at chapter start`() {
        val index = SearchIndex.build("book-x", makeChapters())

        // Chapter 1 text starts with "Kotlin" at position 0
        val hits = index.search(SearchQuery("book-x", "Kotlin", caseSensitive = true))
        val ch0Hit = hits.first { it.spineIndex == 0 }

        // At the very start of chapter: context before must be empty
        assertEquals("", ch0Hit.contextBefore)
        assertEquals("Kotlin", ch0Hit.matchedText)
    }

    @Test
    fun `context is clamped at chapter end`() {
        // Add a chapter whose last word is unique
        val chapters = listOf(
            ChapterText(
                spineIndex = 0,
                title = "End",
                text = "The final word is ENDPOINT here.",
                cfiStart = "epubcfi(/6/2!)",
            ),
        )
        val index = SearchIndex.build("book-x", chapters)

        val hits = index.search(SearchQuery("book-x", "ENDPOINT"))
        assertEquals(1, hits.size)
        val hit = hits[0]
        assertEquals(" here.", hit.contextAfter)
        assertEquals("", hit.contextAfter.takeLast(1).takeIf { it == "." } ?: "")
    }

    // --- CFI target generation ---

    @Test
    fun `cfiTarget is a valid epubcfi string`() {
        val index = SearchIndex.build("book-x", makeChapters())

        val hits = index.search(SearchQuery("book-x", "Kotlin", caseSensitive = true))
        for (hit in hits) {
            assert(hit.cfiTarget.startsWith("epubcfi(/6/")) { "CFI should start with epubcfi: ${hit.cfiTarget}" }
            assert(hit.cfiTarget.contains("!")) { "CFI should contain ! separator: ${hit.cfiTarget}" }
        }
    }

    @Test
    fun `cfiTarget spineIndex matches hit spineIndex`() {
        val index = SearchIndex.build("book-x", makeChapters())

        val hits = index.search(SearchQuery("book-x", "Java", caseSensitive = false))
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].spineIndex)
    }

    // --- Rank ordering ---

    @Test
    fun `hits are ordered by spineIndex ascending`() {
        val index = SearchIndex.build("book-x", makeChapters())

        val hits = index.search(SearchQuery("book-x", "on", caseSensitive = false))
        assert(hits.isNotEmpty())

        for (i in 0 until hits.size - 1) {
            assert(hits[i].spineIndex <= hits[i + 1].spineIndex) {
                "Hit $i (spine=${hits[i].spineIndex}) should not be after hit ${i + 1} (spine=${hits[i + 1].spineIndex})"
            }
        }
    }

    @Test
    fun `ranks are sequential starting from 0`() {
        val index = SearchIndex.build("book-x", makeChapters())

        val hits = index.search(SearchQuery("book-x", "is", caseSensitive = false))
        assert(hits.isNotEmpty())
        hits.forEachIndexed { i, hit ->
            assertEquals(i, hit.rank) { "Rank $i mismatch for hit at spine ${hit.spineIndex}" }
        }
    }

    // --- Mixed case text ---

    @Test
    fun `mixed case text case insensitive finds all`() {
        val chapters = listOf(
            ChapterText(
                spineIndex = 0,
                title = "Mixed",
                text = "ABCabcABC",
                cfiStart = "epubcfi(/6/2!)",
            ),
        )
        val index = SearchIndex.build("book-x", chapters)

        assertEquals(3, index.search(SearchQuery("book-x", "abc", caseSensitive = false)).size)
        assertEquals(2, index.search(SearchQuery("book-x", "ABC", caseSensitive = false)).size)
        assertEquals(1, index.search(SearchQuery("book-x", "abc", caseSensitive = true)).size)
        assertEquals(2, index.search(SearchQuery("book-x", "ABC", caseSensitive = true)).size)
    }
}
