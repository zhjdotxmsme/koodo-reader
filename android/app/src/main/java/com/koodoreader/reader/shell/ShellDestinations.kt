package com.koodoreader.reader.shell

import android.net.Uri

/**
 * Top-level navigation model of the native shell: four peer tabs plus the
 * full-screen reader.
 *
 * Everything here is data plus PURE functions, so the two decisions that matter
 * — which tab owns a route, and whether the bottom bar is visible — are
 * unit-testable on the JVM without Compose. The Compose layer ([ShellScaffold])
 * only renders what these return; no route string is compared inline in UI code.
 * Same pattern as [LibraryLogic] and `IntentRoutePolicy`.
 *
 * Layout (design doc §5):
 *   Tab 1 书库   library   — own top bar: title · view menu · import
 *   Tab 2 笔记   notes     — cross-book highlights / notes / bookmarks
 *   Tab 3 统计   stats     — reuses feature:stats
 *   Tab 4 设置   settings  — grouped; owns the back up / trash / dictionary leaves
 *   reader/{bookKey}       — full screen, OUTSIDE the tab shell
 */
enum class ShellTab(val route: String, val labelKey: String) {
    LIBRARY("library", "Library"),
    NOTES("notes", "Notes"),
    STATS("stats", "Reading Stats"),
    SETTINGS("settings", "Settings"),
    ;

    companion object {
        /** The tab the shell opens on. */
        val START: ShellTab = LIBRARY

        /** Tabs in bottom-bar order (declaration order). */
        fun barOrder(): List<ShellTab> = entries.toList()

        fun fromRoute(route: String?): ShellTab? =
            route?.let { r -> entries.firstOrNull { it.route == r } }
    }
}

/**
 * Route constants and route predicates for the shell.
 *
 * Leaf routes deliberately KEEP the bottom bar: they are drills into a tab, and
 * hiding the bar there would force a back-press just to switch tabs (standard
 * Android behaviour).
 */
object ShellNav {

    // ── Top-level tab routes (kept in sync with [ShellTab.route]) ────────────
    const val LIBRARY = "library"
    const val NOTES = "notes"
    const val STATS = "stats"
    const val SETTINGS = "settings"

    // ── Leaves under SETTINGS (data management / content sources) ────────────
    const val BACKUP = "backup"
    const val TRASH = "trash"
    const val DICTIONARY = "dictionary"

    // ── Full-screen reader ───────────────────────────────────────────────────
    /** NavHost pattern; also the route string reported by NavDestination. */
    const val READER_PATTERN = "reader/{bookKey}"
    private const val READER_PREFIX = "reader/"

    /** Concrete route for a book. Book keys can contain '/', so encode. */
    fun reader(bookKey: String): String = "$READER_PREFIX${Uri.encode(bookKey)}"

    /** True for both the pattern and any concrete `reader/...` route. */
    fun isReader(route: String?): Boolean =
        route != null && (route == READER_PATTERN || route.startsWith(READER_PREFIX))

    /**
     * The bottom bar is hidden ONLY in the reader.
     *
     * The reader is an immersive surface: a permanent bar would cost vertical
     * space and attention for an action the reader screen already offers. Every
     * other destination — including the settings leaves — keeps the bar.
     */
    fun hidesBottomBar(route: String?): Boolean = isReader(route)

    /** True when [route] is one of the four tabs. */
    fun isTopLevel(route: String?): Boolean = ShellTab.fromRoute(route) != null

    private val LEAF_OWNERS: Map<String, ShellTab> = mapOf(
        BACKUP to ShellTab.SETTINGS,
        TRASH to ShellTab.SETTINGS,
        DICTIONARY to ShellTab.SETTINGS,
    )

    /**
     * Which tab owns [route], so the bar keeps the right item highlighted while
     * the user drills into a leaf. Returns null for the reader (no tab owns it —
     * that is exactly why the bar is hidden there) and for unknown routes.
     */
    fun topLevelFor(route: String?): ShellTab? = when {
        route == null -> null
        isReader(route) -> null
        else -> ShellTab.fromRoute(route) ?: LEAF_OWNERS[route]
    }

    /**
     * Every route the shell can mount. Exposed so a test can assert uniqueness
     * and prefix-freedom — `reader/...` must not shadow a tab route, or a book
     * whose key collides with a tab name would open the wrong screen.
     */
    fun allRoutes(): List<String> = listOf(
        LIBRARY, NOTES, STATS, SETTINGS, BACKUP, TRASH, DICTIONARY, READER_PATTERN,
    )

    /** Routes whose bottom bar is hidden; used by the tests as a single source. */
    fun routesHidingBottomBar(): List<String> = listOf(READER_PATTERN)
}
