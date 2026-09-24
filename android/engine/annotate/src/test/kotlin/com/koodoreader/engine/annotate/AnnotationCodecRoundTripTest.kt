package com.koodoreader.engine.annotate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Round-trip test: Annotation → [AnnotationCodec.encode] → [AnnotationCodec.decode]
 * must produce a value equal to the original.
 */
class AnnotationCodecRoundTripTest {

    // ── Highlight round-trip ────────────────────────────────────────────────

    @Test
    fun `highlight round-trip with all fields set`() {
        val original = Annotation.highlight(
            key = "1700000000000",
            bookKey = "book-abc",
            cfiStart = "epubcfi(/6/4[chap01ref]!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4[chap01ref]!/4/2/2:10)",
            selectedText = "selected passage",
            chapter = "Chapter 1",
            chapterIndex = 3,
            percentage = "0.42",
            color = HighlightColor.GREEN,
            range = "{\"start\":{\"offset\":0},\"end\":{\"offset\":10}}",
            tags = listOf("important", "quote"),
            createdAt = 1700000000000L,
        )

        val json = AnnotationCodec.encode(original)
        val decoded = AnnotationCodec.decode(json)

        assertEquals(original.key, decoded.key)
        assertEquals(original.bookKey, decoded.bookKey)
        assertEquals(AnnotationKind.HIGHLIGHT, decoded.kind)
        assertEquals(original.cfiStart, decoded.cfiStart)
        assertEquals(original.cfiEnd, decoded.cfiEnd)
        assertEquals(original.color, decoded.color)
        assertEquals(original.selectedText, decoded.selectedText)
        assertEquals(original.noteText, decoded.noteText)
        assertEquals(original.chapter, decoded.chapter)
        assertEquals(original.chapterIndex, decoded.chapterIndex)
        assertEquals(original.percentage, decoded.percentage)
        assertEquals(original.range, decoded.range)
        assertEquals(original.tags, decoded.tags)
    }

    @Test
    fun `highlight round-trip with minimal fields`() {
        val original = Annotation.highlight(
            key = "1700000000001",
            bookKey = "book-xyz",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
            selectedText = "hi",
        )

        val json = AnnotationCodec.encode(original)
        val decoded = AnnotationCodec.decode(json)

        assertEquals(original.key, decoded.key)
        assertEquals(AnnotationKind.HIGHLIGHT, decoded.kind)
        assertEquals(original.selectedText, decoded.selectedText)
        assertEquals(original.noteText, decoded.noteText)
        assertEquals(original.color, decoded.color)
    }

    // ── Note round-trip ─────────────────────────────────────────────────────

    @Test
    fun `note round-trip preserves noteText`() {
        val original = Annotation.note(
            key = "1700000000002",
            bookKey = "book-def",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:7)",
            selectedText = "marked text",
            noteText = "This is my note",
            chapter = "Chapter 2",
            chapterIndex = 5,
            color = HighlightColor.PINK,
            tags = listOf("comment"),
            createdAt = 1700000000002L,
        )

        val json = AnnotationCodec.encode(original)
        val decoded = AnnotationCodec.decode(json)

        assertEquals(AnnotationKind.NOTE, decoded.kind)
        assertEquals(original.noteText, decoded.noteText)
        assertEquals(original.selectedText, decoded.selectedText)
        assertEquals(original.color, decoded.color)
        assertEquals(original.tags, decoded.tags)
    }

    // ── Bookmark round-trip ─────────────────────────────────────────────────

    @Test
    fun `bookmark round-trip preserves label`() {
        val original = Annotation.bookmark(
            key = "1700000000003",
            bookKey = "book-ghi",
            cfi = "epubcfi(/6/4!/4/2/3:0)",
            label = "Important passage",
            chapter = "Chapter 3",
            chapterIndex = 7,
            percentage = "0.75",
            createdAt = 1700000000003L,
        )

        val json = AnnotationCodec.encode(original)
        val decoded = AnnotationCodec.decode(json)

        assertEquals(AnnotationKind.BOOKMARK, decoded.kind)
        assertEquals(original.key, decoded.key)
        assertEquals(original.bookKey, decoded.bookKey)
        assertEquals(original.cfiStart, decoded.cfiStart)
        assertEquals(original.label, decoded.label)
        assertEquals(original.chapter, decoded.chapter)
        assertEquals(original.percentage, decoded.percentage)
        assertEquals(null, decoded.cfiEnd)
    }

    // ── Different keys produce different JSON ────────────────────────────────

    @Test
    fun `different keys produce different encoded strings`() {
        val a = Annotation.highlight(
            key = "1700000000004",
            bookKey = "book-a",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
            selectedText = "text",
        )
        val b = a.copy(key = "1700000000005")

        assertNotEquals(AnnotationCodec.encode(a), AnnotationCodec.encode(b))
    }

    // ── Color boundary values ──────────────────────────────────────────────

    @Test
    fun `all eight highlight colors round-trip correctly`() {
        val cfiStart = "epubcfi(/6/4!/4/2/1:0)"
        val cfiEnd = "epubcfi(/6/4!/4/2/2:5)"
        for (color in HighlightColor.entries) {
            val ann = Annotation.highlight(
                key = "key-${color.code}",
                bookKey = "book",
                cfiStart = cfiStart,
                cfiEnd = cfiEnd,
                selectedText = "txt",
                color = color,
            )
            val decoded = AnnotationCodec.decode(AnnotationCodec.encode(ann))
            assertEquals(color, decoded.color, "color ${color.name} should round-trip")
        }
    }

    // ── Date ────────────────────────────────────────────────────────────────

    @Test
    fun `date parts round-trip correctly`() {
        val original = Annotation.highlight(
            key = "1700000000006",
            bookKey = "book",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:3)",
            selectedText = "date test",
            createdAt = 1704067200000L, // 2024-01-01 00:00:00 UTC
        )

        val decoded = AnnotationCodec.decode(AnnotationCodec.encode(original))
        assertEquals(original.dateParts(), decoded.dateParts())
    }

    // ── Tag boundary ───────────────────────────────────────────────────────

    @Test
    fun `empty tag list round-trips correctly`() {
        val original = Annotation.highlight(
            key = "1700000000007",
            bookKey = "book",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:4)",
            selectedText = "no tags",
            tags = emptyList(),
        )
        val decoded = AnnotationCodec.decode(AnnotationCodec.encode(original))
        assertEquals(emptyList<String>(), decoded.tags)
    }

    @Test
    fun `unicode tags round-trip correctly`() {
        val tags = listOf("日本語", "emoji 🎵", " spaces ")
        val original = Annotation.highlight(
            key = "1700000000008",
            bookKey = "book",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:4)",
            selectedText = "unicode tags",
            tags = tags,
        )
        val decoded = AnnotationCodec.decode(AnnotationCodec.encode(original))
        assertEquals(tags, decoded.tags)
    }

    // ── Percentage boundary ─────────────────────────────────────────────────

    @Test
    fun `percentage zero round-trips correctly`() {
        val original = Annotation.highlight(
            key = "1700000000009",
            bookKey = "book",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:4)",
            selectedText = "zero pct",
            percentage = "0",
        )
        val decoded = AnnotationCodec.decode(AnnotationCodec.encode(original))
        assertEquals("0", decoded.percentage)
    }

    @Test
    fun `percentage decimal round-trips correctly`() {
        val original = Annotation.highlight(
            key = "1700000000010",
            bookKey = "book",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:4)",
            selectedText = "decimal pct",
            percentage = "0.999",
        )
        val decoded = AnnotationCodec.decode(AnnotationCodec.encode(original))
        assertEquals("0.999", decoded.percentage)
    }
}
