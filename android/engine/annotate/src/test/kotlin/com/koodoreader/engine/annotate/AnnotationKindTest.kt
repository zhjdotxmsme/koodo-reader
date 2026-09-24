package com.koodoreader.engine.annotate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Test [AnnotationKind] three-state transitions and color mapping.
 */
class AnnotationKindTest {

    // ── Enum members ─────────────────────────────────────────────────────────

    @Test
    fun `AnnotationKind has exactly three values`() {
        assertEquals(3, AnnotationKind.entries.size)
    }

    @Test
    fun `all three kinds are present`() {
        assertEquals(AnnotationKind.HIGHLIGHT, AnnotationKind.valueOf("HIGHLIGHT"))
        assertEquals(AnnotationKind.NOTE, AnnotationKind.valueOf("NOTE"))
        assertEquals(AnnotationKind.BOOKMARK, AnnotationKind.valueOf("BOOKMARK"))
    }

    // ── Factory invariants ─────────────────────────────────────────────────

    @Test
    fun `highlight factory creates HIGHLIGHT kind`() {
        val ann = Annotation.highlight(
            key = "1",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
            selectedText = "hello",
        )
        assertEquals(AnnotationKind.HIGHLIGHT, ann.kind)
        assertEquals("", ann.noteText)
    }

    @Test
    fun `note factory creates NOTE kind and rejects blank noteText`() {
        val ann = Annotation.note(
            key = "2",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
            selectedText = "world",
            noteText = "my note",
        )
        assertEquals(AnnotationKind.NOTE, ann.kind)
        assertEquals("my note", ann.noteText)
    }

    @Test
    fun `note factory rejects blank noteText`() {
        var caught: IllegalArgumentException? = null
        try {
            Annotation.note(
                key = "3",
                bookKey = "b",
                cfiStart = "epubcfi(/6/4!/4/2/1:0)",
                cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
                selectedText = "x",
                noteText = "   ",
            )
        } catch (e: IllegalArgumentException) {
            caught = e
        }
        assertEquals("a NOTE needs non-blank noteText (use highlight() otherwise)", caught?.message)
    }

    @Test
    fun `bookmark factory creates BOOKMARK kind with no range`() {
        val ann = Annotation.bookmark(
            key = "4",
            bookKey = "b",
            cfi = "epubcfi(/6/4!/4/2/3:0)",
            label = "my bookmark",
        )
        assertEquals(AnnotationKind.BOOKMARK, ann.kind)
        assertEquals(null, ann.cfiEnd)
        assertEquals("my bookmark", ann.label)
        assertEquals("", ann.selectedText)
    }

    @Test
    fun `bookmark factory enforces point CFI`() {
        var caught: IllegalArgumentException? = null
        try {
            Annotation.bookmark(
                key = "5",
                bookKey = "b",
                // A real range CFI has three comma-separated parts:
                // `epubcfi(parent,start,end)` — a two-part form is rejected by
                // :engine:cfi with CFI_RANGE_INCOMPLETE, which is not the error this
                // test is about.
                cfi = "epubcfi(/6/4!/4/2,/1:0,/2:5)",
            )
        } catch (e: IllegalArgumentException) {
            caught = e
        }
        // The message is "expected a point CFI for a bookmark, got a range: <cfi>"; the
        // first colon sits after ", got a range", so assert on the stable prefix.
        assertTrue(
            caught?.message?.startsWith("expected a point CFI for a bookmark") == true,
            "unexpected message: ${caught?.message}",
        )
    }

    // ── Color mapping ──────────────────────────────────────────────────────

    @Test
    fun `HighlightColor DEFAULT is YELLOW`() {
        assertEquals(HighlightColor.YELLOW, HighlightColor.DEFAULT)
    }

    @Test
    fun `fromCode returns DEFAULT for null`() {
        assertEquals(HighlightColor.DEFAULT, HighlightColor.fromCode(null))
    }

    @Test
    fun `fromCode returns DEFAULT for unknown code`() {
        assertEquals(HighlightColor.DEFAULT, HighlightColor.fromCode(999L))
    }

    @Test
    fun `fromCode returns DEFAULT for zero`() {
        assertEquals(HighlightColor.DEFAULT, HighlightColor.fromCode(0L))
    }

    @Test
    fun `fromCode resolves all eight valid codes`() {
        for (color in HighlightColor.entries) {
            assertEquals(color, HighlightColor.fromCode(color.code.toLong()))
        }
    }

    @Test
    fun `fromHex returns DEFAULT for null`() {
        assertEquals(HighlightColor.DEFAULT, HighlightColor.fromHex(null))
    }

    @Test
    fun `fromHex returns DEFAULT for blank string`() {
        assertEquals(HighlightColor.DEFAULT, HighlightColor.fromHex("   "))
    }

    @Test
    fun `fromHex returns DEFAULT for unknown hex`() {
        assertEquals(HighlightColor.DEFAULT, HighlightColor.fromHex("#DEADBEEF"))
    }

    @Test
    fun `fromHex is case-insensitive`() {
        assertEquals(HighlightColor.YELLOW, HighlightColor.fromHex("#ffe54c"))
        assertEquals(HighlightColor.YELLOW, HighlightColor.fromHex("#FFE54C"))
    }

    @Test
    fun `fromHex tolerates missing leading hash`() {
        assertEquals(HighlightColor.YELLOW, HighlightColor.fromHex("FFE54C"))
    }

    @Test
    fun `fromHex resolves all eight valid hex values`() {
        for (color in HighlightColor.entries) {
            assertEquals(color, HighlightColor.fromHex(color.hex))
        }
    }

    // ── HighlightColor codes are 1-based and sequential ─────────────────────

    @Test
    fun `all HighlightColor codes are 1 through 8`() {
        val codes = HighlightColor.entries.map { it.code }.sorted()
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), codes)
    }

    @Test
    fun `all HighlightColor hex values start with hash`() {
        for (color in HighlightColor.entries) {
            assertEquals('#', color.hex[0])
        }
    }

    // ── isRange helper ──────────────────────────────────────────────────────

    @Test
    fun `isRange is true when cfiEnd is set`() {
        val ann = Annotation.highlight(
            key = "1",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
            selectedText = "x",
        )
        assertEquals(true, ann.isRange)
    }

    @Test
    fun `isRange is false when cfiEnd is null`() {
        val ann = Annotation.bookmark(
            key = "1",
            bookKey = "b",
            cfi = "epubcfi(/6/4!/4/2/3:0)",
        )
        assertEquals(false, ann.isRange)
    }
}
