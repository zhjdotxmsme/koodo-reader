package com.koodoreader.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShelfLogicTest {

    private val empty: Map<String, List<String>> = emptyMap()

    @Test
    fun `create adds a shelf once and refuses blanks or duplicates`() {
        val a = ShelfLogic.create(empty, " 科幻 ")
        assertEquals(listOf("科幻"), a.shelves.keys.toList())
        assertEquals(1, a.changed)
        // duplicate title (after trim) is a no-op
        val b = ShelfLogic.create(a.shelves, "科幻")
        assertEquals(0, b.changed)
        assertTrue(b.shelves === a.shelves)
        // blank title refused
        assertEquals(0, ShelfLogic.create(empty, "   ").changed)
        assertTrue(ShelfLogic.create(empty, "").shelves.isEmpty())
    }

    @Test
    fun `addBooks mirrors desktop addBooksToShelf counting new memberships only`() {
        val shelves = mapOf("科幻" to listOf("k1", "k2"))
        val r = ShelfLogic.addBooks(shelves, "科幻", listOf("k2", "k3", "k3", "", "k4"))
        assertEquals(listOf("k1", "k2", "k3", "k4"), r.shelves["科幻"])
        assertEquals(2, r.changed) // k3 counted once, k2 duplicate, blank ignored
        // second pass adds nothing
        assertEquals(0, ShelfLogic.addBooks(r.shelves, "科幻", listOf("k1", "k3")).changed)
    }

    @Test
    fun `addBooks preserves shelf insertion order of titles`() {
        var shelves = ShelfLogic.create(empty, "B").shelves
        shelves = ShelfLogic.create(shelves, "A").shelves
        shelves = ShelfLogic.addBooks(shelves, "B", listOf("k1")).shelves
        shelves = ShelfLogic.addBooks(shelves, "A", listOf("k2")).shelves
        assertEquals(listOf("B", "A"), shelves.keys.toList())
    }

    @Test
    fun `removeBooks only affects the named shelf`() {
        val shelves = mapOf("A" to listOf("k1", "k2", "k3"), "B" to listOf("k1"))
        val r = ShelfLogic.removeBooks(shelves, "A", listOf("k2", "kx"))
        assertEquals(listOf("k1", "k3"), r.shelves["A"])
        assertEquals(listOf("k1"), r.shelves["B"])
        assertEquals(1, r.changed)
        assertEquals(0, ShelfLogic.removeBooks(shelves, "missing", listOf("k1")).changed)
    }

    @Test
    fun `rename keeps book order and refuses collisions`() {
        val shelves = mapOf("A" to listOf("k2", "k1"), "B" to listOf("k9"))
        val r = ShelfLogic.rename(shelves, "A", "C")
        assertEquals(listOf("C", "B"), r.shelves.keys.toList())
        assertEquals(listOf("k2", "k1"), r.shelves["C"])
        assertEquals(0, ShelfLogic.rename(shelves, "A", "B").changed) // collision
        assertEquals(0, ShelfLogic.rename(shelves, "A", "  ").changed)
        assertEquals(0, ShelfLogic.rename(shelves, "nope", "X").changed)
    }

    @Test
    fun `delete removes the shelf only`() {
        val shelves = mapOf("A" to listOf("k1"), "B" to listOf("k2"))
        val r = ShelfLogic.delete(shelves, "A")
        assertEquals(listOf("B"), r.shelves.keys.toList())
        assertEquals(1, r.changed)
        assertEquals(0, ShelfLogic.delete(shelves, "A2").changed)
    }

    @Test
    fun `pruneMissing drops deleted books from every shelf`() {
        val shelves = mapOf("A" to listOf("k1", "k2"), "B" to listOf("k3"))
        val r = ShelfLogic.pruneMissing(shelves, existingKeys = setOf("k1"))
        assertEquals(listOf("k1"), r.shelves["A"])
        assertTrue(r.shelves["B"]!!.isEmpty())
        assertEquals(2, r.changed)
        // nothing to do -> same instance
        val none = ShelfLogic.pruneMissing(mapOf("A" to listOf("k1")), setOf("k1"))
        assertEquals(0, none.changed)
    }

    @Test
    fun `pruneMissing can drop emptied shelves`() {
        val shelves = mapOf("A" to listOf("k1"), "B" to listOf("k2"))
        val r = ShelfLogic.pruneMissing(shelves, setOf("k2"), dropEmptyShelves = true)
        assertEquals(listOf("B"), r.shelves.keys.toList())
    }

    @Test
    fun `shelvesOf lists memberships in shelf order`() {
        val shelves = mapOf("A" to listOf("k1"), "B" to listOf("k1", "k2"), "C" to listOf("k3"))
        assertEquals(listOf("A", "B"), ShelfLogic.shelvesOf(shelves, "k1"))
        assertTrue(ShelfLogic.shelvesOf(shelves, "kx").isEmpty())
    }

    @Test
    fun `normalize trims titles and dedupes keys`() {
        val messy = mapOf(" A " to listOf("k1", "k1", "", "k2"), "  " to listOf("k9"))
        val n = ShelfLogic.normalize(messy)
        assertEquals(listOf("A"), n.keys.toList())
        assertEquals(listOf("k1", "k2"), n["A"])
    }

    @Test
    fun `config key matches the desktop shelfList key`() {
        assertEquals("shelfList", ShelfLogic.SHELF_LIST_KEY)
    }
}
