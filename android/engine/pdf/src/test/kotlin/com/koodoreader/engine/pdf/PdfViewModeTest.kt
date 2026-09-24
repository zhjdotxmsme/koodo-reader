package com.koodoreader.engine.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfViewModeTest {

    @Test
    fun desktopRoundTrip() {
        assertEquals(PdfViewMode.PAGE_TURN, PdfViewMode.fromDesktop("single"))
        assertEquals(PdfViewMode.DOUBLE_PAGE, PdfViewMode.fromDesktop("double"))
        assertEquals(PdfViewMode.SCROLL, PdfViewMode.fromDesktop("scroll"))
    }

    @Test
    fun unknownValueFallsBackToPageTurn() {
        assertEquals(PdfViewMode.PAGE_TURN, PdfViewMode.fromDesktop(null))
        assertEquals(PdfViewMode.PAGE_TURN, PdfViewMode.fromDesktop(""))
        assertEquals(PdfViewMode.PAGE_TURN, PdfViewMode.fromDesktop("wide"))
    }

    @Test
    fun desktopValueMirrorsEnum() {
        assertEquals("single", PdfViewMode.PAGE_TURN.desktopValue)
        assertEquals("double", PdfViewMode.DOUBLE_PAGE.desktopValue)
        assertEquals("scroll", PdfViewMode.SCROLL.desktopValue)
    }

    @Test
    fun zoomClamping() {
        assertEquals(PdfZoom.DEFAULT, PdfZoom.of(PdfZoom.MIN - 1).percent)
        assertEquals(PdfZoom.DEFAULT, PdfZoom.of(PdfZoom.MAX + 1).percent)
    }

    @Test
    fun zoomRoundTripsDesktopStrings() {
        assertEquals(100, PdfZoom.fromDesktop("1").percent)
        assertEquals(125, PdfZoom.fromDesktop("1.25").percent)
        assertEquals(150, PdfZoom.fromDesktop("1.5").percent)
        assertEquals(PdfZoom.DEFAULT, PdfZoom.fromDesktop("").percent)
        assertEquals(PdfZoom.DEFAULT, PdfZoom.fromDesktop(null).percent)
        // Out of range falls back to DEFAULT (preserves the desktop
        // slider's hard-clamp behaviour).
        assertEquals(PdfZoom.DEFAULT, PdfZoom.fromDesktop("9.9").percent)
    }
}