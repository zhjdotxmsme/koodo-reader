package com.koodoreader.engine.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfViewportMathTest {

    @Test
    fun letterAspectIsKept() {
        val m = PdfViewportMath(360f, 640f, 2.0f)
        m.setPageSize(612f, 792f)
        val zoom = PdfZoom(100)
        val w = m.pageWidthPx(zoom)
        val h = m.pageHeightPx(zoom)
        // Letter is 612 x 792; rendered size = (natural / 72) * 320 * (zoom/100).
        // Aspect ratio (width/height) must match the natural one.
        assertEquals(612f / 792f, w / h, 1e-3f)
    }

    @Test
    fun fitWidthSnapsPageToViewport() {
        val m = PdfViewportMath(360f, 640f, 2.0f)
        val zoom = PdfZoom(PdfZoom.FIT_WIDTH)
        assertEquals(360f, m.pageWidthPx(zoom))
    }

    @Test
    fun scrollContentHeightScalesWithPageCount() {
        val m = PdfViewportMath(360f, 640f, 2.0f)
        val zoom = PdfZoom(100)
        val per = m.pageHeightPx(zoom) + m.pageGapPx
        val h100 = m.contentHeightPx(PdfViewMode.SCROLL, zoom, 100)
        assertEquals(per * 100, h100, 1e-2f)
    }

    @Test
    fun pageAtOffsetIsOneBased() {
        val m = PdfViewportMath(360f, 640f, 2.0f)
        val zoom = PdfZoom(100)
        val per = m.pageHeightPx(zoom) + m.pageGapPx
        assertEquals(1, m.pageAtOffset(PdfViewMode.SCROLL, zoom, 0f))
        assertEquals(2, m.pageAtOffset(PdfViewMode.SCROLL, zoom, per + 1f))
        assertEquals(3, m.pageAtOffset(PdfViewMode.SCROLL, zoom, per * 2 + 1f))
    }

    @Test
    fun offsetForPageRoundTripsWithPageAtOffset() {
        val m = PdfViewportMath(360f, 640f, 2.0f)
        val zoom = PdfZoom(100)
        for (p in 1..10) {
            val off = m.offsetForPage(PdfViewMode.SCROLL, zoom, p)
            assertEquals(p, m.pageAtOffset(PdfViewMode.SCROLL, zoom, off))
        }
    }

    @Test
    fun renderWindowClampsToBook() {
        val m = PdfViewportMath(360f, 640f, 2.0f)
        assertEquals(1..3, m.renderWindow(1, 200))
        assertEquals(96..103, m.renderWindow(100, 200))
        assertEquals(197..200, m.renderWindow(199, 200))
        assertEquals(1..3, m.renderWindow(0, 3))
    }

    @Test
    fun pagedContentHeightIsOnePage() {
        val m = PdfViewportMath(360f, 640f, 2.0f)
        val zoom = PdfZoom(100)
        assertEquals(m.pageHeightPx(zoom), m.contentHeightPx(PdfViewMode.PAGE_TURN, zoom, 1000))
        assertEquals(m.pageHeightPx(zoom), m.contentHeightPx(PdfViewMode.DOUBLE_PAGE, zoom, 1000))
    }
}