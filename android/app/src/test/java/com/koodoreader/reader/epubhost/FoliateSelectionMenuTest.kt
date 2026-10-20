package com.koodoreader.reader.epubhost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoliateSelectionMenuTest {

    @Test
    fun `blank excerpt does not show highlight dictionary buttons`() {
        assertFalse(FoliateSelectionMenu.visible(null))
        assertFalse(FoliateSelectionMenu.visible(""))
        assertFalse(FoliateSelectionMenu.visible("   "))
    }

    @Test
    fun `non-blank excerpt shows the host selection menu`() {
        assertTrue(FoliateSelectionMenu.visible("陶寺的兴衰"))
    }

    @Test
    fun `saved note with the same cfi counts as already highlighted`() {
        assertTrue(FoliateSelectionMenu.highlighted("epubcfi(/6/4)", listOf("epubcfi(/6/2)", "epubcfi(/6/4)")))
        assertFalse(FoliateSelectionMenu.highlighted("epubcfi(/6/4)", listOf("epubcfi(/6/2)")))
        assertFalse(FoliateSelectionMenu.highlighted("", listOf("epubcfi(/6/4)")))
    }
}
