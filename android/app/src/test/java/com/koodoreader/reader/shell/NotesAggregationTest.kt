package com.koodoreader.reader.shell

import com.koodoreader.engine.annotate.AnnotationKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Notes tab's list rules, tested without Room, Compose or coroutines.
 *
 * The load-bearing assertion here is RECONCILIATION: the rows the list shows must
 * add up to the rows the DAOs produced. A filter or grouping bug that quietly
 * drops annotations would still look like a tidy empty list, so "the counts
 * agree" is asserted rather than eyeballed.
 */
class NotesAggregationTest {

    private fun highlight(key: String, book: String, text: String) = NoteListItem(
        key = key,
        bookKey = book,
        kind = AnnotationKind.HIGHLIGHT,
        selectedText = text,
        noteText = "",
        chapter = "Ch 1",
        percentage = "10",
        cfi = "epubcfi(/6/2!/4/2)",
    )

    private fun note(key: String, book: String, text: String, body: String) = highlight(key, book, text)
        .copy(kind = AnnotationKind.NOTE, noteText = body)

    private fun bookmark(key: String, book: String, label: String) = NoteListItem(
        key = key,
        bookKey = book,
        kind = AnnotationKind.BOOKMARK,
        selectedText = "",
        noteText = "",
        chapter = label,
        percentage = "20",
        cfi = "epubcfi(/6/4!/2/1)",
    )

    private val mixed = listOf(
        highlight("h1", "bookB", "highlight in B"),
        note("n1", "bookA", "highlighted text", "my note"),
        bookmark("m1", "bookA", "Bookmark 1"),
        highlight("h2", "bookA", "another highlight"),
        bookmark("m2", "bookB", "Bookmark 2"),
    )

    @Test
    fun `ALL keeps every item`() {
        assertEquals(5, NotesAggregation.filter(mixed, NotesFilter.ALL).size)
        assertEquals(mixed, NotesAggregation.filter(mixed, NotesFilter.ALL))
    }

    @Test
    fun `HIGHLIGHTS keeps both highlights and notes but not bookmarks`() {
        val kept = NotesAggregation.filter(mixed, NotesFilter.HIGHLIGHTS)
        assertEquals(3, kept.size)
        assertTrue(kept.any { it.key == "h1" && it.kind == AnnotationKind.HIGHLIGHT })
        assertTrue(kept.any { it.key == "n1" && it.kind == AnnotationKind.NOTE })
        assertFalse(kept.any { it.kind == AnnotationKind.BOOKMARK })
    }

    @Test
    fun `BOOKMARKS keeps only bookmarks`() {
        val kept = NotesAggregation.filter(mixed, NotesFilter.BOOKMARKS)
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.kind == AnnotationKind.BOOKMARK })
    }

    /**
     * The reconciliation check: per-filter counts must partition the input, so
     * nothing is dropped and nothing is double-counted.
     */
    @Test
    fun `filter counts partition the full list`() {
        val highlights = NotesAggregation.countOf(mixed, NotesFilter.HIGHLIGHTS)
        val bookmarks = NotesAggregation.countOf(mixed, NotesFilter.BOOKMARKS)
        val all = NotesAggregation.countOf(mixed, NotesFilter.ALL)
        assertEquals(all, highlights + bookmarks)
        assertEquals(mixed.size, all)
    }

    @Test
    fun `grouping preserves every item and every book`() {
        val titles = mapOf("bookA" to "Alpha", "bookB" to "Beta")
        val sections = NotesAggregation.group(mixed, titles)
        assertEquals(2, sections.size)
        assertEquals(mixed.size, NotesAggregation.totalCount(sections))
        assertEquals(mixed.map { it.key }.toSet(), sections.flatMap { it.items }.map { it.key }.toSet())
    }

    @Test
    fun `sections are ordered by title case-insensitively`() {
        val items = listOf(highlight("a", "b2", "x"), highlight("b", "b1", "y"))
        val sections = NotesAggregation.group(items, mapOf("b1" to "beta", "b2" to "Alpha"))
        assertEquals(listOf("Alpha", "beta"), sections.map { it.bookTitle })
    }

    @Test
    fun `items keep their incoming order inside a section`() {
        val sections = NotesAggregation.group(mixed, mapOf("bookA" to "Alpha", "bookB" to "Beta"))
        val alpha = sections.first { it.bookKey == "bookA" }
        assertEquals(listOf("n1", "m1", "h2"), alpha.items.map { it.key })
    }

    @Test
    fun `a missing title falls back to the book key rather than a blank header`() {
        val sections = NotesAggregation.group(listOf(highlight("x", "ghost", "t")), emptyMap())
        assertEquals("ghost", sections.single().bookTitle)
    }

    @Test
    fun `a blank title also falls back to the book key`() {
        val sections = NotesAggregation.group(listOf(highlight("x", "ghost", "t")), mapOf("ghost" to "   "))
        assertEquals("ghost", sections.single().bookTitle)
    }

    @Test
    fun `an empty filter result produces no sections`() {
        // Empty sections would render as a header with nothing under it, which
        // reads as "this book is empty" rather than "the filter excludes it".
        assertEquals(emptyList<NoteBookSection>(), NotesAggregation.group(emptyList(), emptyMap()))
        assertEquals(
            emptyList<NoteBookSection>(),
            NotesAggregation.group(
                NotesAggregation.filter(
                    listOf(highlight("h", "b", "t")),
                    NotesFilter.BOOKMARKS,
                ),
                mapOf("b" to "B"),
            ),
        )
    }

    @Test
    fun `displayText prefers the highlight then the note then the chapter`() {
        assertEquals("highlighted", highlight("a", "b", "highlighted").displayText)
        assertEquals("body", highlight("a", "b", "").copy(noteText = "body").displayText)
        assertEquals("Ch 1", highlight("a", "b", "").copy(chapter = "Ch 1").displayText)
    }

    @Test
    fun `hasNote distinguishes a plain highlight from an annotated one`() {
        assertFalse(highlight("a", "b", "t").hasNote)
        assertTrue(note("a", "b", "t", "body").hasNote)
    }

    @Test
    fun `a row with no bookKey gets a neutral header rather than a blank one`() {
        val sections = NotesAggregation.group(
            listOf(highlight("x", "", "text"), highlight("y", "b", "text")),
            mapOf("b" to "Beta"),
        )
        assertEquals(2, sections.size)
        assertTrue(sections.none { it.bookTitle.isBlank() }, "a blank header was rendered")
        assertEquals("\u2014", sections.first { it.bookKey == "" }.bookTitle)
    }

    // ─── Row → item mapping (the other half of the reconciliation) ──────────

    private fun noteRow(
        key: String,
        book: String?,
        text: String?,
        notes: String?,
    ) = com.koodoreader.core.data.entity.NoteEntity(
        key = key,
        bookKey = book,
        text = text,
        notes = notes,
        cfi = "epubcfi(/6/2!/4/2)",
        chapter = "Ch 1",
    )

    private fun bookmarkRow(key: String, book: String?) =
        com.koodoreader.core.data.entity.BookmarkEntity(
            key = key,
            bookKey = book,
            cfi = "epubcfi(/6/4!/2/1)",
            label = "Mark",
        )

    /**
     * N note rows + M bookmark rows must produce exactly N + M items. This is the
     * assertion that ties the list to the DAOs: if the mapping ever skipped a row
     * (a null key, an unrecognised kind), the list would look fine and be missing
     * annotations.
     */
    @Test
    fun `note and bookmark rows map one-to-one`() {
        val notes = listOf(
            noteRow("1", "bookA", "highlighted", null),
            noteRow("2", "bookA", "highlighted", "with a note"),
            noteRow("3", "bookB", "third", ""),
        )
        val bookmarks = listOf(bookmarkRow("10", "bookB"), bookmarkRow("11", "bookA"))

        val items = noteRowsToItems(notes, bookmarks)
        assertEquals(notes.size + bookmarks.size, items.size)
        assertEquals(5, items.map { it.key }.toSet().size, "a key was duplicated or lost")
    }

    /**
     * The three-state rule lives in `engine:annotate`'s codec, not here — this
     * test pins that the aggregation actually goes through it.
     */
    @Test
    fun `a notes row is a HIGHLIGHT when the notes column is blank and a NOTE otherwise`() {
        val items = noteRowsToItems(
            notes = listOf(
                noteRow("1", "b", "text", null),
                noteRow("2", "b", "text", ""),
                noteRow("3", "b", "text", "   "),
                noteRow("4", "b", "text", "a real note"),
            ),
            bookmarks = emptyList(),
        )
        assertEquals(
            listOf(
                AnnotationKind.HIGHLIGHT,
                AnnotationKind.HIGHLIGHT,
                AnnotationKind.HIGHLIGHT,
                AnnotationKind.NOTE,
            ),
            items.map { it.kind },
        )
    }

    @Test
    fun `bookmark rows map to BOOKMARK and keep their label as the row text`() {
        val item = noteRowsToItems(emptyList(), listOf(bookmarkRow("10", "b"))).single()
        assertEquals(AnnotationKind.BOOKMARK, item.kind)
        // The codec puts the bookmark name in `label`, NOT in `chapter` — assuming
        // otherwise renders every bookmark as a blank row (this test caught it).
        assertEquals("Mark", item.label)
        assertEquals("Mark", item.displayText)
        assertTrue(item.cfi != null, "a valid bookmark CFI should survive")
    }

    @Test
    fun `rows with a null bookKey map to an empty bookKey instead of crashing`() {
        val items = noteRowsToItems(listOf(noteRow("1", null, "text", null)), emptyList())
        assertEquals("", items.single().bookKey)
        // ...and the aggregation still shows them rather than dropping them.
        assertEquals(1, NotesAggregation.totalCount(NotesAggregation.group(items, emptyMap())))
    }
}
