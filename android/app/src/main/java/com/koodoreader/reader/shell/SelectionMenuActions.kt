package com.koodoreader.reader.shell

import android.content.Context

/**
 * The configurable actions of the reader's selection menu (P1-12).
 *
 * The colour row and Delete stay fixed — choosing a colour *is* the highlight,
 * and Delete only exists for an existing one. This list covers the secondary
 * row: what to do with the selected words.
 *
 * Stored as a comma-separated name list so the SharedPreferences layer stays
 * string-only like [ReaderGesturePrefs], and so a future build can add an action
 * without a schema migration: unknown names read back from an older (or newer)
 * build are dropped rather than crashing.
 */
enum class SelectionMenuAction(val titleKey: String) {
    COPY("Copy"),
    SEARCH("Search"),
    DICTIONARY("Dictionary"),
    TRANSLATE("Translate"),
    NOTE("Note"),
    ;

    companion object {
        /** The pre-P1-12 hardcoded row, in its original order. */
        val DEFAULT: List<SelectionMenuAction> = listOf(COPY, SEARCH, DICTIONARY, TRANSLATE, NOTE)

        /**
         * Parses a stored preference.
         *
         * Unknown and repeated entries are dropped; an empty or unreadable
         * preference, or one that resolves to nothing, yields [DEFAULT] — a
         * missing menu would leave the reader with no way to act on a
         * selection at all.
         */
        fun parse(raw: String?): List<SelectionMenuAction> {
            if (raw.isNullOrBlank()) return DEFAULT
            val seen = LinkedHashSet<SelectionMenuAction>()
            for (part in raw.split(',')) {
                val name = part.trim()
                if (name.isEmpty()) continue
                entries.firstOrNull { it.name == name }?.let(seen::add)
            }
            return if (seen.isEmpty()) DEFAULT else seen.toList()
        }

        /** The storage form: names in order, comma separated. */
        fun encode(actions: List<SelectionMenuAction>): String =
            actions.joinToString(",") { it.name }
    }
}

/** SharedPreferences store for [SelectionMenuAction]. */
class SelectionMenuActionPrefs(context: Context) {

    private val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<SelectionMenuAction> = SelectionMenuAction.parse(sp.getString(KEY_ACTIONS, null))

    fun save(actions: List<SelectionMenuAction>) {
        sp.edit().putString(KEY_ACTIONS, SelectionMenuAction.encode(actions)).apply()
    }

    companion object {
        const val PREFS_NAME = "reader_selection_menu"
        const val KEY_ACTIONS = "actions"
    }
}
