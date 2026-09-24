package com.koodoreader.engine.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfSearchEngineTest {

    @Test
    fun emptyJsonReturnsNoHits() {
        val eng = PdfSearchEngine()
        assertTrue(eng.parseHits(null, "fox").isEmpty())
        assertTrue(eng.parseHits("", "fox").isEmpty())
    }

    @Test
    fun emptyQueryReturnsNoHits() {
        val eng = PdfSearchEngine()
        val json = """[{"pageNumber":1,"hits":[{"rects":[{"x":1,"y":2,"width":3,"height":4}],"text":"fox"}]}]"""
        assertTrue(eng.parseHits(json, "").isEmpty())
        assertTrue(eng.parseHits(json, "   ").isEmpty())
    }

    @Test
    fun malformedJsonReturnsNoHits() {
        val eng = PdfSearchEngine()
        assertTrue(eng.parseHits("not json", "fox").isEmpty())
    }

    @Test
    fun hitsSortedByPageAndRect() {
        val eng = PdfSearchEngine()
        val json = """
            [
              {"pageNumber":3,"hits":[{"rects":[{"x":10,"y":20,"width":50,"height":18}],"text":"the fox"}]},
              {"pageNumber":1,"hits":[{"rects":[{"x":1,"y":1,"width":2,"height":3}],"text":"a fox"}]}
            ]
        """.trimIndent()
        val hits = eng.parseHits(json, "fox")
        assertEquals(2, hits.size)
        assertEquals(1, hits[0].pageNumber)
        assertEquals(3, hits[1].pageNumber)
    }

    @Test
    fun snippetCentresOnNeedle() {
        val eng = PdfSearchEngine()
        val json = """
            [{"pageNumber":1,"hits":[
              {"rects":[{"x":1,"y":1,"width":2,"height":3}],
               "text":"The quick brown fox jumps over"}
            ]}]
        """.trimIndent()
        val hits = eng.parseHits(json, "fox")
        assertEquals(1, hits.size)
        // "The quick brown fox jumps over" trimmed to ctx around "fox":
        // ...quick brown fox jumps...
        assertTrue("fox" in hits[0].snippet.lowercase(), "got '${hits[0].snippet}'")
    }

    @Test
    fun multiRectHitKeepsAllRects() {
        val eng = PdfSearchEngine()
        val json = """
            [{"pageNumber":1,"hits":[
              {"rects":[
                {"x":1,"y":1,"width":2,"height":3},
                {"x":4,"y":1,"width":2,"height":3}
              ],"text":"fox fox"}
            ]}]
        """.trimIndent()
        val hits = eng.parseHits(json, "fox")
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].rects.size)
    }

    @Test
    fun cycleToWrapsAround() {
        val eng = PdfSearchEngine()
        val hits = listOf(
            PdfSearchEngine.Hit(1, listOf(PdfSearchEngine.Rect(0f, 0f, 1f, 1f)), "a"),
            PdfSearchEngine.Hit(2, listOf(PdfSearchEngine.Rect(0f, 0f, 1f, 1f)), "b"),
            PdfSearchEngine.Hit(3, listOf(PdfSearchEngine.Rect(0f, 0f, 1f, 1f)), "c"),
        )
        assertEquals(1, eng.cycleTo(hits, 0, 1))
        assertEquals(2, eng.cycleTo(hits, 0, -1)) // wraps backward
        assertEquals(-1, eng.cycleTo(emptyList(), 0, 1))
    }

    @Test
    fun legacyPageKeyIsAccepted() {
        val eng = PdfSearchEngine()
        val json = """
            [{"page":2,"hits":[{"rects":[{"x":1,"y":1,"width":2,"height":3}],"text":"fox"}]}]
        """.trimIndent()
        val hits = eng.parseHits(json, "fox")
        assertNotNull(hits)
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].pageNumber)
    }
}