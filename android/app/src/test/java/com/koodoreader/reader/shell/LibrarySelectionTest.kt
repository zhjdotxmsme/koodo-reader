package com.koodoreader.reader.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibrarySelectionTest {

    @Test
    fun `multi delete only affects selected books`() {
        val state = LibrarySelection().toggle("a").toggle("b")
        assertEquals(setOf("a", "b"), state.selected)
    }

    @Test
    fun `toggle twice removes the key`() {
        val state = LibrarySelection().toggle("a").toggle("a")
        assertFalse(state.active)
        assertEquals(emptySet<String>(), state.selected)
    }

    @Test
    fun `selectAll and clear`() {
        val state = LibrarySelection().selectAll(listOf("a", "b", "c"))
        assertEquals(setOf("a", "b", "c"), state.selected)
        assertTrue(state.active)
        assertEquals(LibrarySelection(), state.clear())
    }
}
