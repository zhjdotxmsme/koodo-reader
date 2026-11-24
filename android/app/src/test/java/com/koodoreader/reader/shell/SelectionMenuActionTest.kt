package com.koodoreader.reader.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SelectionMenuActionTest {

    @Test
    fun `parse drops unknown and repeated actions`() {
        val parsed = SelectionMenuAction.parse("COPY,UNKNOWN,NOTE,NOTE,DICTIONARY")
        assertEquals(
            listOf(
                SelectionMenuAction.COPY,
                SelectionMenuAction.NOTE,
                SelectionMenuAction.DICTIONARY,
            ),
            parsed,
        )
    }

    @Test
    fun `parse blank returns default`() {
        assertEquals(SelectionMenuAction.DEFAULT, SelectionMenuAction.parse(""))
        assertEquals(SelectionMenuAction.DEFAULT, SelectionMenuAction.parse("   "))
        assertEquals(SelectionMenuAction.DEFAULT, SelectionMenuAction.parse(null))
    }

    @Test
    fun `encode round-trips through parse`() {
        val actions = listOf(
            SelectionMenuAction.COPY,
            SelectionMenuAction.TRANSLATE,
            SelectionMenuAction.NOTE,
        )
        val encoded = SelectionMenuAction.encode(actions)
        assertEquals(actions, SelectionMenuAction.parse(encoded))
    }

    @Test
    fun `default order includes all actions`() {
        assertEquals(
            listOf(
                SelectionMenuAction.COPY,
                SelectionMenuAction.SEARCH,
                SelectionMenuAction.DICTIONARY,
                SelectionMenuAction.TRANSLATE,
                SelectionMenuAction.NOTE,
            ),
            SelectionMenuAction.DEFAULT,
        )
    }
}
