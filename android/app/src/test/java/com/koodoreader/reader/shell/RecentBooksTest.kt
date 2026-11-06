package com.koodoreader.reader.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class RecentBooksTest {
    @Test
    fun `recent books are newest first and deduplicated`() {
        val store = RecentBooks(InMemoryPrefs())
        store.markOpened("a", 10)
        store.markOpened("b", 20)
        store.markOpened("a", 30)
        assertEquals(listOf("a", "b"), store.recentKeys())
    }

    @Test
    fun `purging a book clears its recent entry`() {
        val prefs = InMemoryPrefs().apply { write(mapOf("a" to 30L)) }
        val store = RecentBooks(prefs)
        store.remove("a")
        assertFalse(store.recentKeys().contains("a"))
    }
}

class InMemoryPrefs : RecentPrefs {
    private var map: Map<String, Long> = emptyMap()

    override fun all(): Map<String, Long> = map

    override fun write(entries: Map<String, Long>) {
        map = entries.toMap()
    }
}
