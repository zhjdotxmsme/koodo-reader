package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {

    private fun b(key: String, name: String? = null, author: String? = null, format: String? = null) =
        SortableBook(key, name, author, format)

    // ------------------------------------------------------------ sorting

    @Test
    fun `added order derives from key timestamp with newest first by default`() {
        val books = listOf(b("1700000002000aaa"), b("1700000001000bbb"), b("1700000003000ccc"))
        val keys = LibraryLogic.sortedKeys(books, SortSpec.DEFAULT)
        assertEquals(listOf("1700000003000ccc", "1700000002000aaa", "1700000001000bbb"), keys)
        val oldestFirst = LibraryLogic.sortedKeys(books, SortSpec(SortField.ADDED, ascending = true))
        assertEquals(listOf("1700000001000bbb", "1700000002000aaa", "1700000003000ccc"), oldestFirst)
    }

    @Test
    fun `name sort is case insensitive with stable key tiebreak`() {
        val books = listOf(
            b("k3", name = "banana"),
            b("k1", name = "Apple"),
            b("k2", name = "apple"),
            b("k4", name = null),
        )
        val asc = LibraryLogic.sortedKeys(books, SortSpec(SortField.NAME, ascending = true))
        assertEquals(listOf("k1", "k2", "k3", "k4"), asc) // nulls last
        val desc = LibraryLogic.sortedKeys(books, SortSpec(SortField.NAME, ascending = false))
        assertEquals(listOf("k3", "k1", "k2", "k4"), desc) // nulls stay last
    }

    @Test
    fun `author and format sort with nulls last both directions`() {
        val books = listOf(
            b("k1", author = "Zed", format = "pdf"),
            b("k2", author = null, format = null),
            b("k3", author = "alice", format = "epub"),
        )
        val byAuthor = LibraryLogic.sortedKeys(books, SortSpec(SortField.AUTHOR, ascending = true))
        assertEquals(listOf("k3", "k1", "k2"), byAuthor)
        val byFormatDesc = LibraryLogic.sortedKeys(books, SortSpec(SortField.FORMAT, ascending = false))
        assertEquals(listOf("k1", "k3", "k2"), byFormatDesc)
    }

    @Test
    fun `manual order appends unknown keys chronologically`() {
        val books = listOf(b("k1"), b("k2"), b("k3"), b("k4"))
        val keys = LibraryLogic.sortedKeys(
            books,
            SortSpec(SortField.MANUAL, ascending = true),
            manualOrder = listOf("k3", "k1"),
        )
        assertEquals(listOf("k3", "k1", "k2", "k4"), keys)
    }

    @Test
    fun `manual move swaps positions of dragged and target key`() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "c", "b", "d"), LibraryLogic.moveInOrder(order, "b", "c"))
        assertEquals(order, LibraryLogic.moveInOrder(order, "b", "b"))
        assertEquals(order, LibraryLogic.moveInOrder(order, "b", "missing"))
    }

    @Test
    fun `applyOrder reorders and preserves leftovers`() {
        val books = listOf(b("k1"), b("k2"), b("k3"), b("k9"))
        val ordered = LibraryLogic.applyOrder(books, listOf("k3", "k1"))
        assertEquals(listOf("k3", "k1", "k2", "k9"), ordered.map { it.key })
    }

    // -------------------------------------------------------- trash flow

    @Test
    fun `soft delete mirrors desktop moveBooksToTrash`() {
        val t = LibraryLogic.softDelete(listOf("k1", "k2", "k1"), trashed = emptySet(), favorites = setOf("k1"))
        assertEquals(setOf("k1", "k2"), t.trashed)
        assertEquals(emptySet<String>(), t.favorites) // k1 unfavorited
        assertEquals(2, t.changed) // idempotent: duplicate k1 counted once
        // second pass changes nothing
        val again = LibraryLogic.softDelete(listOf("k1", "k2"), t.trashed, t.favorites)
        assertEquals(0, again.changed)
    }

    @Test
    fun `favoriting restores from trash like desktop addBooksToFavorite`() {
        val t = LibraryLogic.favorite(listOf("k1"), trashed = setOf("k1", "k9"), favorites = emptySet())
        assertEquals(setOf("k1"), t.favorites)
        assertFalse("k1" in t.trashed)
        assertTrue("k9" in t.trashed) // untouched
        assertEquals(1, t.changed)
    }

    @Test
    fun `restore removes from trash only`() {
        val t = LibraryLogic.restore(listOf("k1", "kx"), trashed = setOf("k1", "k2"))
        assertEquals(setOf("k2"), t.trashed)
        assertEquals(1, t.changed)
    }

    @Test
    fun `purge only targets trashed books`() {
        assertEquals(listOf("k1"), LibraryLogic.purgeTargets(listOf("k1", "k2"), trashed = setOf("k1")))
        assertTrue(LibraryLogic.purgeTargets(listOf("k2"), trashed = setOf("k1")).isEmpty())
    }

    @Test
    fun `visible keys exclude trashed books`() {
        val books = listOf(b("k1"), b("k2"), b("k3"))
        assertEquals(listOf("k1", "k3"), LibraryLogic.visibleKeys(books, setOf("k2")).map { it.key })
    }
}
