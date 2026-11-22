package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionMenuActionsTest {

    @Test
    fun `blank or missing preference yields the default order`() {
        assertEquals(SelectionMenuAction.DEFAULT, SelectionMenuAction.parse(null))
        assertEquals(SelectionMenuAction.DEFAULT, SelectionMenuAction.parse(""))
        assertEquals(SelectionMenuAction.DEFAULT, SelectionMenuAction.parse("   "))
    }

    @Test
    fun `saved order round trips`() {
        val order = listOf(
            SelectionMenuAction.DICTIONARY,
            SelectionMenuAction.TRANSLATE,
            SelectionMenuAction.COPY,
        )
        assertEquals(
            order,
            SelectionMenuAction.parse(SelectionMenuAction.encode(order)),
        )
    }

    @Test
    fun `unknown entries are dropped and empty result falls back to default`() {
        assertEquals(
            listOf(SelectionMenuAction.COPY),
            SelectionMenuAction.parse("COPY,BOGUS"),
        )
        assertEquals(SelectionMenuAction.DEFAULT, SelectionMenuAction.parse("BOGUS1,BOGUS2"))
    }

    @Test
    fun `repeated entries appear once`() {
        // A doubled name would otherwise render two identical buttons.
        assertEquals(
            listOf(SelectionMenuAction.COPY, SelectionMenuAction.SEARCH),
            SelectionMenuAction.parse("COPY,SEARCH,COPY"),
        )
    }

    @Test
    fun `every action has a translation key that is not its own label`() {
        // The keys are the pre-existing, already-translated desktop strings —
        // a new literal here would ship an untranslated button.
        val known = setOf("Copy", "Search", "Dictionary", "Translate", "Note")
        SelectionMenuAction.entries.forEach { action ->
            assertTrue(
                "${action.name} uses an unknown title key ${action.titleKey}",
                action.titleKey in known,
            )
        }
    }
}
