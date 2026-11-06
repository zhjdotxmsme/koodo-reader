package com.koodoreader.reader.shell

/** Multi-select state for the library shelf. Keys are Room book keys. */
data class LibrarySelection(val selected: Set<String> = emptySet()) {
    val active: Boolean get() = selected.isNotEmpty()

    fun toggle(key: String): LibrarySelection = copy(
        selected = selected.toMutableSet().apply { if (!add(key)) remove(key) },
    )

    fun selectAll(keys: Collection<String>): LibrarySelection = copy(selected = keys.toSet())

    fun clear() = LibrarySelection()
}
