package com.koodoreader.reader.shell

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Bookshelf UI-state persistence — the native counterpart of the desktop
 * config JSONs (`favoriteBooks` / `deletedBooks` / `bookSortCode` / view
 * mode). Deliberately NOT Room: the five frozen `.db` tables stay
 * schema.lock-clean, exactly like the desktop stores these lists outside
 * its databases. Cleared only when the user deletes data.
 *
 * Manual (drag) order is a native extension; the desktop sorts by config
 * codes and drags books onto shelves instead.
 */
class LibraryPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)

    // --------------------------------------------------------- sort/view

    var sortField: SortField
        get() = runCatching {
            SortField.valueOf(sp.getString(KEY_SORT_FIELD, SortField.ADDED.name)!!)
        }.getOrDefault(SortField.ADDED)
        set(value) = sp.edit().putString(KEY_SORT_FIELD, value.name).apply()

    var sortAscending: Boolean
        get() = sp.getBoolean(KEY_SORT_ASC, false)
        set(value) = sp.edit().putBoolean(KEY_SORT_ASC, value).apply()

    var viewGrid: Boolean
        get() = sp.getBoolean(KEY_VIEW_GRID, true)
        set(value) = sp.edit().putBoolean(KEY_VIEW_GRID, value).apply()

    /** UI language: I18nState.SYSTEM or a locale code (e.g. "en", "zh-CN"). */
    var language: String
        get() = sp.getString(KEY_LANGUAGE, I18nState.SYSTEM) ?: I18nState.SYSTEM
        set(value) = sp.edit().putString(KEY_LANGUAGE, value).apply()

    // ------------------------------------------------- favorites / trash

    fun favorites(): Set<String> = stringSet(KEY_FAVORITES)
    fun setFavorites(keys: Set<String>) = sp.edit().putStringSet(KEY_FAVORITES, keys).apply()

    fun trashed(): Set<String> = stringSet(KEY_TRASHED)
    fun setTrashed(keys: Set<String>) = sp.edit().putStringSet(KEY_TRASHED, keys).apply()

    // ------------------------------------------------------ manual order

    fun manualOrder(): List<String> {
        val raw = sp.getString(KEY_MANUAL_ORDER, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun setManualOrder(keys: List<String>) {
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        sp.edit().putString(KEY_MANUAL_ORDER, arr.toString()).apply()
    }

    private fun stringSet(key: String): Set<String> =
        sp.getStringSet(key, emptySet()).orEmpty().toSet()

    private companion object {
        const val KEY_SORT_FIELD = "sortField"
        const val KEY_SORT_ASC = "sortAscending"
        const val KEY_VIEW_GRID = "viewGrid"
        const val KEY_LANGUAGE = "language"
        const val KEY_FAVORITES = "favoriteBooks" // desktop config key parity
        const val KEY_TRASHED = "deletedBooks" // desktop config key parity
        const val KEY_MANUAL_ORDER = "manualOrder"
    }
}
