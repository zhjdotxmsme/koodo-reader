package com.koodoreader.core.common

/**
 * Shelf grouping (书架分组) — desktop parity with
 * `src/utils/reader/bookDrag.ts` (`addBooksToShelf` over the `shelfList`
 * config map: shelf title -> ordered book keys).
 *
 * Pure JVM domain logic: the Android layer persists the map (LibraryPrefs)
 * and renders it; keeping the rules here makes them unit-testable and keeps
 * the desktop key/semantics identical for the eventual config migration.
 */
object ShelfLogic {

    /** Desktop config key (ConfigService map). */
    const val SHELF_LIST_KEY = "shelfList"

    data class Change(
        val shelves: Map<String, List<String>>,
        /** Books actually added/removed (desktop returns the same counter). */
        val changed: Int,
    )

    /** Create a shelf; existing titles are untouched (`changed = 0`). */
    fun create(shelves: Map<String, List<String>>, title: String): Change {
        val clean = title.trim()
        if (clean.isEmpty()) return Change(shelves, 0)
        if (shelves.containsKey(clean)) return Change(shelves, 0)
        val next = LinkedHashMap(shelves)
        next[clean] = emptyList()
        return Change(next, 1)
    }

    /** Rename a shelf, keeping its book order; collisions are refused. */
    fun rename(
        shelves: Map<String, List<String>>,
        from: String,
        to: String,
    ): Change {
        val clean = to.trim()
        if (clean.isEmpty() || from == clean) return Change(shelves, 0)
        val keys = shelves[from] ?: return Change(shelves, 0)
        if (shelves.containsKey(clean)) return Change(shelves, 0)
        val next = LinkedHashMap<String, List<String>>()
        shelves.forEach { (k, v) -> if (k == from) next[clean] = v else next[k] = v }
        return Change(next, 1)
    }

    /** Delete a shelf (books themselves are never touched). */
    fun delete(shelves: Map<String, List<String>>, title: String): Change {
        if (!shelves.containsKey(title)) return Change(shelves, 0)
        val next = LinkedHashMap(shelves)
        next.remove(title)
        return Change(next, 1)
    }

    /**
     * Add books to a shelf — desktop `addBooksToShelf` parity: duplicates are
     * skipped, the reported count is the number of NEW memberships.
     */
    fun addBooks(
        shelves: Map<String, List<String>>,
        title: String,
        bookKeys: List<String>,
    ): Change {
        val clean = title.trim()
        if (clean.isEmpty()) return Change(shelves, 0)
        val existing = shelves[clean].orEmpty()
        val seen = existing.toMutableSet()
        var added = 0
        val merged = ArrayList<String>(existing.size + bookKeys.size)
        merged.addAll(existing)
        for (key in bookKeys) {
            if (key.isBlank() || !seen.add(key)) continue
            merged.add(key)
            added++
        }
        if (added == 0) return Change(shelves, 0)
        val next = LinkedHashMap(shelves)
        next[clean] = merged
        return Change(next, added)
    }

    /** Remove books from a shelf (other shelves and the books themselves stay). */
    fun removeBooks(
        shelves: Map<String, List<String>>,
        title: String,
        bookKeys: List<String>,
    ): Change {
        val existing = shelves[title] ?: return Change(shelves, 0)
        val drop = bookKeys.toSet()
        val kept = existing.filterNot { it in drop }
        val removed = existing.size - kept.size
        if (removed == 0) return Change(shelves, 0)
        val next = LinkedHashMap(shelves)
        next[title] = kept
        return Change(next, removed)
    }

    /**
     * Drop keys that no longer exist (book permanently deleted / purged) from
     * every shelf, and remove shelves that become empty only when
     * [dropEmptyShelves] is set.
     */
    fun pruneMissing(
        shelves: Map<String, List<String>>,
        existingKeys: Set<String>,
        dropEmptyShelves: Boolean = false,
    ): Change {
        var changed = 0
        val next = LinkedHashMap<String, List<String>>()
        shelves.forEach { (title, keys) ->
            val kept = keys.filter { it in existingKeys }
            changed += keys.size - kept.size
            if (kept.isNotEmpty() || !dropEmptyShelves) next[title] = kept
            else changed++
        }
        return if (changed == 0) Change(shelves, 0) else Change(next, changed)
    }

    /** Titles containing [bookKey], in shelf order. */
    fun shelvesOf(shelves: Map<String, List<String>>, bookKey: String): List<String> =
        shelves.filterValues { bookKey in it }.keys.toList()

    /** Normalized view for persistence (blank titles/keys dropped). */
    fun normalize(shelves: Map<String, List<String>>): Map<String, List<String>> {
        val next = LinkedHashMap<String, List<String>>()
        shelves.forEach { (title, keys) ->
            val clean = title.trim()
            if (clean.isEmpty()) return@forEach
            val seen = LinkedHashSet<String>()
            keys.forEach { k -> if (k.isNotBlank()) seen.add(k) }
            next[clean] = seen.toList()
        }
        return next
    }
}
