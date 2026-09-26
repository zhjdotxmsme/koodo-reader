package com.koodoreader.reader.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shell's routing decisions, exercised without Compose.
 *
 * Why these assertions matter: "is the bottom bar visible?" and "which tab is
 * highlighted?" were previously implicit in a single screen's layout code, so
 * nothing could catch a regression such as the bar leaking into the reader.
 * [ShellNav] makes both answerable here.
 *
 * NOTE: this deliberately does not call [ShellNav.reader]. That helper encodes
 * the book key with `android.net.Uri`, which is an unimplemented stub in plain
 * JVM unit tests (no Robolectric, no `isReturnDefaultValues`), so calling it
 * would fail for reasons unrelated to routing. The reader predicates are
 * exercised against literal route strings instead.
 */
class ShellDestinationsTest {

    @Test
    fun `there are exactly four tabs in bar order`() {
        assertEquals(
            listOf(ShellTab.LIBRARY, ShellTab.NOTES, ShellTab.STATS, ShellTab.SETTINGS),
            ShellTab.barOrder(),
        )
    }

    @Test
    fun `the shell starts on the library tab`() {
        assertEquals(ShellTab.LIBRARY, ShellTab.START)
        assertEquals(ShellNav.LIBRARY, ShellTab.START.route)
    }

    @Test
    fun `every tab route matches its own constant`() {
        assertEquals(ShellNav.LIBRARY, ShellTab.LIBRARY.route)
        assertEquals(ShellNav.NOTES, ShellTab.NOTES.route)
        assertEquals(ShellNav.STATS, ShellTab.STATS.route)
        assertEquals(ShellNav.SETTINGS, ShellTab.SETTINGS.route)
    }

    @Test
    fun `all routes are unique`() {
        val routes = ShellNav.allRoutes()
        assertEquals(routes.size, routes.toSet().size, "duplicate route in $routes")
    }

    @Test
    fun `no tab route is a prefix of another route`() {
        // A tab route that prefixes another would let two destinations match the
        // same string; `reader/` excludes itself from this check because it is
        // the pattern that intentionally owns a whole namespace.
        val tabs = ShellTab.barOrder().map { it.route }
        for (a in tabs) {
            for (b in tabs) {
                if (a != b) assertFalse(b.startsWith(a), "'$b' starts with '$a'")
            }
        }
    }

    @Test
    fun `the bar is hidden in the reader for both pattern and concrete routes`() {
        // NavDestination reports the PATTERN; navigation uses the concrete string.
        assertTrue(ShellNav.hidesBottomBar(ShellNav.READER_PATTERN))
        assertTrue(ShellNav.hidesBottomBar("reader/abc123"))
        assertTrue(ShellNav.hidesBottomBar("reader/urn%3Auuid%3A1234"))
    }

    @Test
    fun `the bar is visible on every tab`() {
        for (tab in ShellTab.barOrder()) {
            assertFalse(ShellNav.hidesBottomBar(tab.route), "bar hidden on ${tab.route}")
            assertTrue(ShellNav.isTopLevel(tab.route))
        }
    }

    @Test
    fun `the bar is visible on the settings leaves`() {
        // Drilling into Backup / Trash / Dictionary must not cost a back-press to
        // switch tabs.
        for (leaf in listOf(ShellNav.BACKUP, ShellNav.TRASH, ShellNav.DICTIONARY)) {
            assertFalse(ShellNav.hidesBottomBar(leaf), "bar hidden on leaf $leaf")
            assertFalse(ShellNav.isTopLevel(leaf), "$leaf should not be a tab")
        }
    }

    @Test
    fun `topLevelFor resolves tabs to themselves`() {
        for (tab in ShellTab.barOrder()) {
            assertEquals(tab, ShellNav.topLevelFor(tab.route))
        }
    }

    @Test
    fun `topLevelFor resolves the settings leaves to the settings tab`() {
        // The bar keeps the Settings item selected while the user is inside a
        // leaf; null here would leave no item highlighted.
        for (leaf in listOf(ShellNav.BACKUP, ShellNav.TRASH, ShellNav.DICTIONARY)) {
            assertEquals(ShellTab.SETTINGS, ShellNav.topLevelFor(leaf))
        }
    }

    @Test
    fun `topLevelFor returns null for the reader so no tab looks selected`() {
        assertNull(ShellNav.topLevelFor(ShellNav.READER_PATTERN))
        assertNull(ShellNav.topLevelFor("reader/abc123"))
    }

    @Test
    fun `topLevelFor tolerates null and unknown routes`() {
        assertNull(ShellNav.topLevelFor(null))
        assertNull(ShellNav.topLevelFor("no/such/route"))
        assertFalse(ShellNav.hidesBottomBar(null))
    }

    @Test
    fun `the reader prefix cannot shadow a tab route`() {
        // A book whose key is literally "library" must still encode under
        // reader/, so the destination graph cannot open a tab by accident.
        val readerRoute = "reader/library"
        assertTrue(ShellNav.isReader(readerRoute))
        assertFalse(ShellNav.isTopLevel(readerRoute))
        assertNull(ShellNav.topLevelFor(readerRoute))
    }

    @Test
    fun `routesHidingBottomBar matches the hidesBottomBar predicate`() {
        // Guards the single-source claim: whatever the helper lists must be
        // exactly what the predicate hides.
        val hiding = ShellNav.routesHidingBottomBar()
        assertTrue(hiding.isNotEmpty())
        for (r in hiding) assertTrue(ShellNav.hidesBottomBar(r), "$r should hide the bar")
        for (r in ShellNav.allRoutes().filterNot { it in hiding }) {
            assertFalse(ShellNav.hidesBottomBar(r), "$r should show the bar")
        }
    }
}
