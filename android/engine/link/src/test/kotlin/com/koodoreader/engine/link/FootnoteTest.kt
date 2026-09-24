package com.koodoreader.engine.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Coverage of the chapter footnote model's two-way addressing
 * (正文↔脚注双向查询) and of [FootnoteExtractor]'s number resolution.
 */
class FootnoteTest {

    private val chapter = ChapterFootnotes(
        chapterKey = "ch1",
        refs = listOf(
            FootnoteRef(number = 1, anchor = "fnref1", refCfi = "epubcfi(/4/2/2)"),
            FootnoteRef(number = 2, anchor = "fnref2", refCfi = "epubcfi(/4/4/2)"),
            FootnoteRef(number = 1, anchor = "fnref1b", refCfi = "epubcfi(/4/6/2)"),
            FootnoteRef(number = 0, anchor = "broken", refCfi = "epubcfi(/4/8/2)"),
        ),
        footnotes = listOf(
            Footnote(chapterKey = "ch1", number = 1, text = "first note", cfiTarget = "epubcfi(/2/2)"),
            Footnote(chapterKey = "ch1", number = 2, text = "second note", cfiTarget = "epubcfi(/2/4)"),
        ),
    )

    @Nested
    @DisplayName("forward direction — body ref → footnote")
    inner class Forward {

        @Test
        @DisplayName("byNumber hit")
        fun byNumberHit() {
            assertEquals("first note", chapter.byNumber(1)?.text)
            assertEquals("second note", chapter.byNumber(2)?.text)
        }

        @Test
        @DisplayName("byNumber miss (unknown number / 0 unresolved)")
        fun byNumberMiss() {
            assertNull(chapter.byNumber(3))
            assertNull(chapter.byNumber(0))
        }

        @Test
        @DisplayName("byRefCfi hit: resolves the ref's number to the definition")
        fun byRefCfiHit() {
            assertEquals("second note", chapter.byRefCfi("epubcfi(/4/4/2)")?.text)
        }

        @Test
        @DisplayName("byRefCfi miss: unknown ref CFI")
        fun byRefCfiMiss() {
            assertNull(chapter.byRefCfi("epubcfi(/9/9/9)"))
        }

        @Test
        @DisplayName("byRefCfi miss: a present but unresolved ref (number 0) links to nothing")
        fun byRefCfiUnresolved() {
            assertNull(chapter.byRefCfi("epubcfi(/4/8/2)"))
        }

        @Test
        @DisplayName("forRef from a FootnoteRef value")
        fun forRef() {
            assertEquals(
                "first note",
                chapter.forRef(FootnoteRef(number = 1, refCfi = "epubcfi(/4/2/2)"))?.text,
            )
        }
    }

    @Nested
    @DisplayName("reverse direction — footnote → body refs")
    inner class Reverse {

        @Test
        @DisplayName("refsFor returns every superscript of that footnote (1 → two refs)")
        fun refsForNumber() {
            val refs = chapter.refsFor(1)
            assertEquals(2, refs.size)
            assertEquals(listOf("fnref1", "fnref1b"), refs.map { it.anchor })
        }

        @Test
        @DisplayName("refsFor miss returns an empty list")
        fun refsForMiss() {
            assertTrue(chapter.refsFor(9).isEmpty())
        }

        @Test
        @DisplayName("refsForCfiTarget hit: definition CFI → its body refs")
        fun refsForCfiTargetHit() {
            val refs = chapter.refsForCfiTarget("epubcfi(/2/2)")
            assertEquals(2, refs.size)
        }

        @Test
        @DisplayName("refsForCfiTarget miss: unknown target")
        fun refsForCfiTargetMiss() {
            assertTrue(chapter.refsForCfiTarget("epubcfi(/2/99)").isEmpty())
        }

        @Test
        @DisplayName("refsForCfiTarget is empty-safe (a blank target never matches)")
        fun refsForCfiTargetEmpty() {
            assertTrue(chapter.refsForCfiTarget("").isEmpty())
        }
    }

    @Test
    @DisplayName("ordering: refs keep body order; footnotes are ascending by number")
    fun ordering() {
        val built = FootnoteExtractor.extract(
            chapterKey = "ch",
            refs = listOf(
                RefEntry(refCfi = "c2", number = 2),
                RefEntry(refCfi = "c1", number = 1),
            ),
            definitions = listOf(
                DefinitionEntry(cfiTarget = "d2", number = 2, text = "two"),
                DefinitionEntry(cfiTarget = "d1", number = 1, text = "one"),
            ),
        ).chapter

        assertEquals(listOf("c2", "c1"), built.refs.map { it.refCfi })
        assertEquals(listOf(1, 2), built.footnotes.map { it.number })
    }

    @Nested
    @DisplayName("FootnoteExtractor — number resolution")
    inner class Extractor {

        @Test
        @DisplayName("numbers are derived from anchors when not explicit")
        fun anchorDerivedNumbers() {
            val result = FootnoteExtractor.extract(
                chapterKey = "ch",
                refs = listOf(RefEntry(refCfi = "r", anchor = "footnote-ref-3")),
                definitions = listOf(
                    DefinitionEntry(cfiTarget = "d", anchor = "fn-3", text = "three"),
                ),
            )
            assertEquals(3, result.chapter.byRefCfi("r")?.number)
            assertEquals("three", result.chapter.byNumber(3)?.text)
            assertTrue(result.unlinkedRefs.isEmpty())
            assertTrue(result.orphanDefinitions.isEmpty())
        }

        @Test
        @DisplayName("an explicit number wins over the anchor's integer run")
        fun explicitNumberWins() {
            val result = FootnoteExtractor.extract(
                chapterKey = "ch",
                refs = listOf(RefEntry(refCfi = "r", anchor = "fn-9", number = 1)),
                definitions = listOf(
                    DefinitionEntry(cfiTarget = "d", anchor = "fn-9", number = 1, text = "one"),
                ),
            )
            assertEquals(1, result.chapter.byRefCfi("r")?.number)
        }

        @Test
        @DisplayName("a digitless ref cross-matches an explicitly-numbered definition by anchor")
        fun anchorCrossMatch() {
            val result = FootnoteExtractor.extract(
                chapterKey = "ch",
                refs = listOf(RefEntry(refCfi = "r", anchor = "backref")),
                definitions = listOf(
                    DefinitionEntry(cfiTarget = "d", anchor = "backref", number = 7, text = "seven"),
                ),
            )
            assertEquals(7, result.chapter.byRefCfi("r")?.number)
        }

        @Test
        @DisplayName("unresolvable refs/definitions are reported, not dropped")
        fun unlinkedReported() {
            val result = FootnoteExtractor.extract(
                chapterKey = "ch",
                refs = listOf(RefEntry(refCfi = "r", anchor = "no-number")),
                definitions = listOf(
                    DefinitionEntry(cfiTarget = "d", anchor = "also-no-number", text = "orphan"),
                ),
            )
            assertEquals(1, result.unlinkedRefs.size)
            assertEquals(0, result.unlinkedRefs.first().number)
            assertEquals(1, result.orphanDefinitions.size)
            assertEquals(0, result.orphanDefinitions.first().number)
            assertTrue(result.chapter.isEmpty)
        }

        @Test
        @DisplayName("numberFromAnchor reads the first integer run")
        fun numberFromAnchor() {
            assertEquals(12, FootnoteExtractor.numberFromAnchor("footnote-12"))
            assertEquals(7, FootnoteExtractor.numberFromAnchor("#note_7"))
            assertNull(FootnoteExtractor.numberFromAnchor("no digits"))
        }
    }

    @Test
    @DisplayName("empty chapter has nothing to resolve in either direction")
    fun emptyChapter() {
        val empty = ChapterFootnotes.empty("ch")
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.size)
        assertNull(empty.byNumber(1))
        assertTrue(empty.refsFor(1).isEmpty())
    }
}
