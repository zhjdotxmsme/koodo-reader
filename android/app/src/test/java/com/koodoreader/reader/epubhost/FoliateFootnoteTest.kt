package com.koodoreader.reader.epubhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoliateFootnoteTest {

    @Test
    fun `non-blank excerpt opens the compose sheet`() {
        val decision = FoliateFootnote.decide(
            excerpt = "See chapter 3, note 1.",
            href = "#fn1",
        )
        assertEquals(FoliateFootnote.Decision.Show("See chapter 3, note 1."), decision)
        assertTrue(FoliateFootnote.showSheet("See chapter 3, note 1."))
    }

    @Test
    fun `blank excerpt with href navigates instead of showing a sheet`() {
        val decision = FoliateFootnote.decide(excerpt = "  ", href = "notes.xhtml#fn2")
        assertEquals(FoliateFootnote.Decision.Navigate("notes.xhtml#fn2"), decision)
        assertFalse(FoliateFootnote.showSheet(null))
    }

    @Test
    fun `blank excerpt and blank href is ignored`() {
        assertEquals(FoliateFootnote.Decision.Ignore, FoliateFootnote.decide("", ""))
        assertEquals(FoliateFootnote.Decision.Ignore, FoliateFootnote.decide("   ", "  "))
    }
}
