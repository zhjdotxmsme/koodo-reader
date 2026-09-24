package com.koodoreader.engine.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutlineResolverTest {

    @Test
    fun emptyJsonProducesEmptyTree() {
        val t = OutlineResolver.resolve(null)
        assertTrue(t.isEmpty)
        assertEquals(0, t.totalCount)
    }

    @Test
    fun emptyStringProducesEmptyTree() {
        val t = OutlineResolver.resolve("")
        assertTrue(t.isEmpty)
    }

    @Test
    fun malformedJsonProducesEmptyTree() {
        val t = OutlineResolver.resolve("this is not json")
        assertTrue(t.isEmpty)
    }

    @Test
    fun flatOutline() {
        val json = """
            [{"title":"Chapter 1","pageNumber":1,"children":[]},
             {"title":"Chapter 2","pageNumber":12,"children":[]}]
        """.trimIndent()
        val t = OutlineResolver.resolve(json)
        assertEquals(2, t.entries.size)
        assertEquals("Chapter 1", t.entries[0].title)
        assertEquals(12, t.entries[1].pageNumber)
        assertEquals(2, t.totalCount)
    }

    @Test
    fun nestedOutlineCountsPreorder() {
        val json = """
            [{"title":"Chapter 1","pageNumber":1,"children":[
              {"title":"1.1","pageNumber":2,"children":[
                {"title":"1.1.1","pageNumber":3,"children":[]}
              ]},
              {"title":"1.2","pageNumber":5,"children":[]}
            ]}]
        """.trimIndent()
        val t = OutlineResolver.resolve(json)
        assertEquals(1, t.entries.size)
        assertEquals(4, t.totalCount)
        val ch1 = t.entries[0]
        assertEquals(2, ch1.children.size)
        assertEquals(1, ch1.children[0].children.size)
    }

    @Test
    fun rectIsParsed() {
        val json = """
            [{"title":"x","pageNumber":1,"rect":{"x":1.0,"y":2.0,"width":3.0,"height":4.0},"children":[]}]
        """.trimIndent()
        val t = OutlineResolver.resolve(json)
        val r = t.entries[0].rect
        assertNotNull(r)
        assertEquals(1.0f, r!!.x)
        assertEquals(4.0f, r.height)
    }

    @Test
    fun nearestEntryWalksPreorder() {
        val tree = OutlineResolver.resolve(
            """
            [{"title":"Chapter 1","pageNumber":1,"children":[
              {"title":"1.1","pageNumber":2,"children":[]},
              {"title":"1.2","pageNumber":5,"children":[]}
            ]},
             {"title":"Chapter 2","pageNumber":12,"children":[]}]
            """.trimIndent()
        )
        assertEquals("1.1", OutlineResolver.nearestEntry(tree, 2)?.title)
        assertEquals("1.2", OutlineResolver.nearestEntry(tree, 7)?.title)
        // The LAST entry that starts at or before the page wins: page 11 is still inside
        // 1.2's span (1.2 starts at 5, Chapter 2 starts at 12), so the label is "1.2" —
        // a page after a sub-entry keeps reporting that sub-entry, not its parent.
        assertEquals("1.2", OutlineResolver.nearestEntry(tree, 11)?.title)
        assertEquals("Chapter 2", OutlineResolver.nearestEntry(tree, 13)?.title)
        // Before the first entry: returns null
        assertNull(OutlineResolver.nearestEntry(tree, 0))
    }

    private val nestedTree = """
        [{"title":"Chapter 1","pageNumber":1,"children":[
          {"title":"1.1","pageNumber":2,"children":[
            {"title":"1.1.1","pageNumber":3,"children":[]}
          ]},
          {"title":"1.2","pageNumber":5,"children":[]}
        ]},
         {"title":"Chapter 2","pageNumber":12,"children":[]}]
    """.trimIndent()

    @Test
    fun flattenIsPreorderWithDepths() {
        val rows = OutlineResolver.flatten(OutlineResolver.resolve(nestedTree))
        assertEquals(5, rows.size)
        assertEquals(listOf("Chapter 1", "1.1", "1.1.1", "1.2", "Chapter 2"), rows.map { it.entry.title })
        assertEquals(listOf(0, 1, 2, 1, 0), rows.map { it.depth })
    }

    @Test
    fun flattenOfEmptyTreeIsEmpty() {
        assertTrue(OutlineResolver.flatten(OutlineResolver.resolve(null)).isEmpty())
    }

    @Test
    fun flattenKeepsUnresolvedEntries() {
        val rows = OutlineResolver.flatten(
            OutlineResolver.resolve(
                """[{"title":"Broken","pageNumber":0,"children":[]}]"""
            )
        )
        // The drawer renders these disabled; dropping them would hide a real
        // (if unresolvable) chapter from the reader.
        assertEquals(1, rows.size)
        assertEquals(0, rows[0].entry.pageNumber)
    }

    @Test
    fun flattenMatchesTotalCount() {
        val tree = OutlineResolver.resolve(nestedTree)
        assertEquals(tree.totalCount, OutlineResolver.flatten(tree).size)
    }
}