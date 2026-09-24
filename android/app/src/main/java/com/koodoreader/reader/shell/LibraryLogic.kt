package com.koodoreader.reader.shell

/**
 * Pure bookshelf logic (no Android imports — covered by local JVM tests).
 *
 * Semantics mirror the desktop manager:
 *  - trash = the `deletedBooks` config list (SOFT delete; the books row stays
 *    in the DB until 彻底删除/purge), see src/utils/reader/bookDrag.ts;
 *  - favoriting a trashed book restores it (and trashing removes favorite);
 *  - `ADDED` order uses the book key: `buildBookKey` = timestamp + random, so
 *    lexicographic key order == chronological creation order.
 */
data class SortableBook(
    val key: String,
    val name: String?,
    val author: String?,
    val format: String?,
)

/** [labelKey] is the desktop i18n key (react-i18next parity, see en.json). */
enum class SortField(val labelKey: String) {
    MANUAL("Manual"),
    NAME("Name"),
    AUTHOR("Author"),
    FORMAT("Format"),
    ADDED("Sort by Date"),
}

data class SortSpec(val field: SortField, val ascending: Boolean) {
    companion object {
        val DEFAULT = SortSpec(SortField.ADDED, ascending = false)
    }
}

/** Result of a trash/favorite transition (mirrors bookDrag.ts counters). */
data class ShelfTransition(
    val trashed: Set<String>,
    val favorites: Set<String>,
    val changed: Int,
)

object LibraryLogic {

    // ------------------------------------------------------------- sorting

    /**
     * Keys of [books] ordered by [spec]. `MANUAL` follows [manualOrder]
     * (unknown keys appended chronologically); every other field falls back
     * to the book key for stable ties.
     */
    fun sortedKeys(
        books: List<SortableBook>,
        spec: SortSpec,
        manualOrder: List<String> = emptyList(),
    ): List<String> {
        val base = books.asSortingBase()
        return when (spec.field) {
            SortField.MANUAL -> {
                val index = manualOrder.withIndex().associate { (i, k) -> k to i }
                base.sortedWith(
                    compareBy({ index[it.key] ?: Int.MAX_VALUE }, { it.key }),
                ).map { it.key }
            }
            SortField.NAME -> base.sortedWith(textComparator(spec.ascending) { it.name }).map { it.key }
            SortField.AUTHOR -> base.sortedWith(textComparator(spec.ascending) { it.author }).map { it.key }
            SortField.FORMAT -> base.sortedWith(
                textComparator(spec.ascending) { it.format?.lowercase() },
            ).map { it.key }
            SortField.ADDED -> {
                // key starts with the creation timestamp → lexicographic works
                val c = compareBy<SortableBook> { it.key }
                val sorted = if (spec.ascending) base.sortedWith(c) else base.sortedWith(c.reversed())
                sorted.map { b -> b.key }
            }
        }
    }

    /** Ordered [books] according to [orderedKeys] (unknown keys keep list order at the end). */
    fun applyOrder(books: List<SortableBook>, orderedKeys: List<String>): List<SortableBook> {
        val byKey = books.associateBy { it.key }
        val seen = HashSet<String>()
        val out = ArrayList<SortableBook>(books.size)
        for (k in orderedKeys) {
            val b = byKey[k]
            if (b != null && seen.add(k)) out.add(b)
        }
        for (b in books) if (seen.add(b.key)) out.add(b)
        return out
    }

    /**
     * Manual-order list after dragging [key] onto [targetKey]'s slot: the
     * dragged key TAKES the target slot (others shift), which resolves the
     * before/after ambiguity naturally for both drag directions.
     */
    fun moveInOrder(order: List<String>, key: String, targetKey: String): List<String> {
        if (key == targetKey || key !in order) return order
        val to = order.indexOf(targetKey)
        if (to < 0) return order
        val rest = order.filter { it != key }
        return rest.toMutableList().apply { add(to, key) }
    }

    // ---------------------------------------------------------- trash flow

    /** 桌面 `moveBooksToTrash`：加入 deletedBooks，同时移出收藏；幂等。 */
    fun softDelete(keys: List<String>, trashed: Set<String>, favorites: Set<String>): ShelfTransition {
        var changed = 0
        val newTrashed = trashed.toMutableSet()
        val newFav = favorites.toMutableSet()
        for (k in keys) {
            if (newTrashed.add(k)) changed++
            newFav.remove(k)
        }
        return ShelfTransition(newTrashed, newFav, changed)
    }

    /** 桌面 `addBooksToFavorite`：加入收藏并从回收站恢复；幂等。 */
    fun favorite(keys: List<String>, trashed: Set<String>, favorites: Set<String>): ShelfTransition {
        var changed = 0
        val newFav = favorites.toMutableSet()
        val newTrashed = trashed.toMutableSet()
        for (k in keys) {
            if (newFav.add(k)) changed++
            newTrashed.remove(k)
        }
        return ShelfTransition(newTrashed, newFav, changed)
    }

    /** 回收站恢复（移出 deletedBooks，不动收藏）。 */
    fun restore(keys: List<String>, trashed: Set<String>): ShelfTransition {
        var changed = 0
        val newTrashed = trashed.toMutableSet()
        for (k in keys) if (newTrashed.remove(k)) changed++
        return ShelfTransition(newTrashed, emptySet(), changed)
    }

    /** 彻底删除只允许对已在回收站的书执行（防误删）。 */
    fun purgeTargets(keys: List<String>, trashed: Set<String>): List<String> =
        keys.filter { it in trashed }

    /** 书架视图 = 全部书 - 回收站中的书。 */
    fun visibleKeys(books: List<SortableBook>, trashed: Set<String>): List<SortableBook> =
        books.filter { it.key !in trashed }

    // ------------------------------------------------------------ internal

    private fun List<SortableBook>.asSortingBase(): List<SortableBook> =
        sortedBy { it.key } // stable chronological base for every comparator

    /** Text field compare with nulls LAST in both directions; direction inside. */
    private inline fun textComparator(
        ascending: Boolean,
        crossinline selector: (SortableBook) -> String?,
    ): Comparator<SortableBook> = Comparator { a, b ->
        val va = selector(a)
        val vb = selector(b)
        when {
            va == null && vb == null -> 0
            va == null -> 1
            vb == null -> -1
            else -> {
                val c = va.compareTo(vb, ignoreCase = true)
                if (ascending) c else -c
            }
        }
    }
}
