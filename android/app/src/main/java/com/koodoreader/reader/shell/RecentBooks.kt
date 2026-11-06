package com.koodoreader.reader.shell

/**
 * Bounded recent-reading index (bookKey → last-open millis). Stored outside Room
 * so schema.lock tables stay clean, same rationale as [ReaderProgressPrefs].
 */
interface RecentPrefs {
    fun all(): Map<String, Long>

    fun write(entries: Map<String, Long>)
}

class SharedRecentPrefs(context: android.content.Context) : RecentPrefs {
    private val sp = context.getSharedPreferences("recent_books", android.content.Context.MODE_PRIVATE)

    override fun all(): Map<String, Long> =
        sp.all.mapNotNull { (k, v) ->
            val millis = (v as? Long) ?: (v as? Int)?.toLong() ?: return@mapNotNull null
            k to millis
        }.toMap()

    override fun write(entries: Map<String, Long>) {
        sp.edit().apply {
            clear()
            entries.forEach { (key, millis) -> putLong(key, millis) }
        }.apply()
    }
}

class RecentBooks(private val prefs: RecentPrefs) {
    fun markOpened(bookKey: String, now: Long) {
        if (bookKey.isBlank()) return
        val next = prefs.all().toMutableMap()
        next[bookKey] = now
        val bounded = next.entries
            .sortedByDescending { it.value }
            .take(MAX_RECENT)
            .associate { it.key to it.value }
        prefs.write(bounded)
    }

    fun recentKeys(): List<String> =
        prefs.all().entries.sortedByDescending { it.value }.map { it.key }

    fun recentMillis(): Map<String, Long> = prefs.all()

    fun remove(bookKey: String) {
        if (bookKey.isBlank()) return
        prefs.write(prefs.all() - bookKey)
    }

    private companion object {
        const val MAX_RECENT = 200
    }
}
