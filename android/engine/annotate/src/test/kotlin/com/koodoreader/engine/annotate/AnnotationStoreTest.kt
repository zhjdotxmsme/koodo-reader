package com.koodoreader.engine.annotate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Test annotation CRUD, filtering, and overlap detection.
 * Uses a simple in-memory [AnnotationStore] backed by a mutable list.
 */
class AnnotationStoreTest {

    // ── In-memory store helper ─────────────────────────────────────────────

    private fun makeStore(vararg annotations: Annotation): AnnotationStore {
        return AnnotationStore(annotations.toMutableList())
    }

    // ── Add ────────────────────────────────────────────────────────────────

    @Test
    fun `store starts empty`() {
        val store = AnnotationStore()
        assertEquals(0, store.all().size)
    }

    @Test
    fun `add inserts an annotation`() {
        val store = AnnotationStore()
        val ann = Annotation.highlight(
            key = "1",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
            selectedText = "hello",
        )
        store.add(ann)
        assertEquals(1, store.all().size)
        assertEquals(ann, store.all()[0])
    }

    @Test
    fun `add rejects duplicate key`() {
        val store = makeStore(
            Annotation.highlight(
                key = "1",
                bookKey = "b",
                cfiStart = "epubcfi(/6/4!/4/2/1:0)",
                cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
                selectedText = "first",
            ),
        )
        var caught: IllegalArgumentException? = null
        try {
            store.add(
                Annotation.highlight(
                    key = "1",
                    bookKey = "b",
                    cfiStart = "epubcfi(/6/4!/4/2/1:0)",
                    cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
                    selectedText = "duplicate",
                ),
            )
        } catch (e: IllegalArgumentException) {
            caught = e
        }
        assertEquals("duplicate key", caught?.message)
    }

    // ── Get ────────────────────────────────────────────────────────────────

    @Test
    fun `get returns annotation by key`() {
        val ann = Annotation.highlight(
            key = "2",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
            selectedText = "find me",
        )
        val store = makeStore(ann)
        assertEquals(ann, store.get("2"))
    }

    @Test
    fun `get returns null for unknown key`() {
        val store = makeStore(
            Annotation.highlight(
                key = "3",
                bookKey = "b",
                cfiStart = "epubcfi(/6/4!/4/2/1:0)",
                cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
                selectedText = "x",
            ),
        )
        assertEquals(null, store.get("unknown"))
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Test
    fun `update replaces existing annotation`() {
        val original = Annotation.highlight(
            key = "4",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
            selectedText = "original",
        )
        val store = makeStore(original)

        val updated = original.copy(selectedText = "updated")
        store.update(updated)

        assertEquals("updated", store.get("4")?.selectedText)
    }

    @Test
    fun `update throws for unknown key`() {
        val store = makeStore()
        var caught: IllegalArgumentException? = null
        try {
            store.update(
                Annotation.highlight(
                    key = "no-such-key",
                    bookKey = "b",
                    cfiStart = "epubcfi(/6/4!/4/2/1:0)",
                    cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
                    selectedText = "orphan",
                ),
            )
        } catch (e: IllegalArgumentException) {
            caught = e
        }
        assertEquals("key not found", caught?.message)
    }

    // ── Remove ─────────────────────────────────────────────────────────────

    @Test
    fun `remove deletes annotation by key`() {
        val ann = Annotation.highlight(
            key = "5",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
            selectedText = "to delete",
        )
        val store = makeStore(ann)
        store.remove("5")
        assertEquals(0, store.all().size)
    }

    @Test
    fun `remove does nothing for unknown key`() {
        val store = makeStore()
        store.remove("no-such-key") // must not throw
        assertEquals(0, store.all().size)
    }

    // ── Filter by bookKey ─────────────────────────────────────────────────

    @Test
    fun `filterByBookKey returns only matching annotations`() {
        val bookA = Annotation.highlight(
            key = "10",
            bookKey = "book-a",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
            selectedText = "a1",
        )
        val bookA2 = Annotation.highlight(
            key = "11",
            bookKey = "book-a",
            cfiStart = "epubcfi(/6/4!/4/2/3:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/4:5)",
            selectedText = "a2",
        )
        val bookB = Annotation.bookmark(
            key = "12",
            bookKey = "book-b",
            cfi = "epubcfi(/6/4!/4/2/5:0)",
        )
        val store = makeStore(bookA, bookA2, bookB)

        val filtered = store.filterByBookKey("book-a")
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.bookKey == "book-a" })
    }

    @Test
    fun `filterByBookKey returns empty list when nothing matches`() {
        val store = makeStore(
            Annotation.highlight(
                key = "13",
                bookKey = "book-x",
                cfiStart = "epubcfi(/6/4!/4/2/1:0)",
                cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
                selectedText = "x",
            ),
        )
        assertTrue(store.filterByBookKey("book-y").isEmpty())
    }

    @Test
    fun `filterByBookKey is case-sensitive`() {
        val ann = Annotation.highlight(
            key = "14",
            bookKey = "Book-A",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
            selectedText = "mixed",
        )
        val store = makeStore(ann)
        assertTrue(store.filterByBookKey("book-a").isEmpty())
        assertEquals(1, store.filterByBookKey("Book-A").size)
    }

    // ── Overlap detection ─────────────────────────────────────────────────

    @Test
    fun `overlappingAnchors returns annotations whose ranges intersect`() {
        val overlapping = Annotation.highlight(
            key = "20",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/5:0)",
            selectedText = "range A",
        )
        val contained = Annotation.highlight(
            key = "21",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/2:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/4:0)",
            selectedText = "range B inside A",
        )
        val disjoint = Annotation.highlight(
            key = "22",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/10:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/12:0)",
            selectedText = "range C disjoint",
        )
        val store = makeStore(overlapping, contained, disjoint)

        val queryStart = "epubcfi(/6/4!/4/2/1:0)"
        val queryEnd = "epubcfi(/6/4!/4/2/6:0)"
        val hits = store.overlappingAnchors(queryStart, queryEnd)

        assertEquals(2, hits.size)
        assertTrue(hits.map { it.key }.containsAll(listOf("20", "21")))
        assertFalse(hits.map { it.key }.contains("22"))
    }

    @Test
    fun `overlappingAnchors includes touching boundaries`() {
        val ann = Annotation.highlight(
            key = "23",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/3:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/5:0)",
            selectedText = "touches at 3",
        )
        val store = makeStore(ann)

        // query ends exactly where annotation starts — should still overlap (touching counts)
        val hits = store.overlappingAnchors(
            "epubcfi(/6/4!/4/2/1:0)",
            "epubcfi(/6/4!/4/2/3:0)",
        )
        assertEquals(1, hits.size)
    }

    @Test
    fun `overlappingAnchors returns empty for disjoint ranges`() {
        val ann = Annotation.highlight(
            key = "24",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/10:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/12:0)",
            selectedText = "far away",
        )
        val store = makeStore(ann)

        val hits = store.overlappingAnchors(
            "epubcfi(/6/4!/4/2/1:0)",
            "epubcfi(/6/4!/4/2/2:0)",
        )
        assertEquals(0, hits.size)
    }

    @Test
    fun `overlappingAnchors ignores bookmarks (point annotations)`() {
        val bookmark = Annotation.bookmark(
            key = "25",
            bookKey = "b",
            cfi = "epubcfi(/6/4!/4/2/3:0)",
        )
        val store = makeStore(bookmark)

        // a query range that would overlap the bookmark point
        val hits = store.overlappingAnchors(
            "epubcfi(/6/4!/4/2/1:0)",
            "epubcfi(/6/4!/4/2/5:0)",
        )
        // overlappingAnchors only considers range annotations
        assertEquals(0, hits.size)
    }

    // ── All annotations ───────────────────────────────────────────────────

    @Test
    fun `all returns every annotation in insertion order`() {
        val a = Annotation.highlight(
            key = "30",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
            selectedText = "first",
        )
        val b = Annotation.note(
            key = "31",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/3:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/4:5)",
            selectedText = "second",
            noteText = "a note",
        )
        val c = Annotation.bookmark(
            key = "32",
            bookKey = "b",
            cfi = "epubcfi(/6/4!/4/2/5:0)",
        )
        val store = makeStore(a, b, c)

        val all = store.all()
        assertEquals(3, all.size)
        assertEquals(listOf("30", "31", "32"), all.map { it.key })
    }

    @Test
    fun `store with mixed kinds reports correct size`() {
        val highlight = Annotation.highlight(
            key = "40",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
            selectedText = "h",
        )
        val note = Annotation.note(
            key = "41",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/3:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/4:5)",
            selectedText = "n",
            noteText = "note",
        )
        val bookmark = Annotation.bookmark(
            key = "42",
            bookKey = "b",
            cfi = "epubcfi(/6/4!/4/2/5:0)",
        )
        val store = makeStore(highlight, note, bookmark)
        assertEquals(3, store.all().size)
    }
}

// ── Minimal in-memory store ──────────────────────────────────────────────────

/**
 * Simple in-memory annotation store for testing.
 * Thread-unsafe; not suitable for production use.
 */
class AnnotationStore(initial: List<Annotation> = emptyList()) {

    private val backing = mutableListOf<Annotation>()

    init {
        backing.addAll(initial)
    }

    fun all(): List<Annotation> = backing.toList()

    fun get(key: String): Annotation? = backing.firstOrNull { it.key == key }

    fun add(ann: Annotation) {
        require(backing.none { it.key == ann.key }) { "duplicate key" }
        backing.add(ann)
    }

    fun update(ann: Annotation) {
        val idx = backing.indexOfFirst { it.key == ann.key }
        require(idx >= 0) { "key not found" }
        backing[idx] = ann
    }

    fun remove(key: String) {
        backing.removeAll { it.key == key }
    }

    fun filterByBookKey(bookKey: String): List<Annotation> =
        backing.filter { it.bookKey == bookKey }

    /**
     * Return all range annotations whose CFI intervals overlap the given
     * [start] / [end] point CFIs (closed-interval semantics: touching endpoints
     * count as overlap).
     */
    fun overlappingAnchors(start: String, end: String): List<Annotation> =
        backing.filter { ann ->
            ann.isRange && CfiAnchor.overlaps(ann.cfiStart, ann.cfiEnd!!, start, end)
        }
}
