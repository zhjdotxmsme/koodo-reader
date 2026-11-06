package com.koodoreader.reader.shell

import com.koodoreader.engine.pdf.CfiPdfMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PdfHighlightOverlayTest {

    @Test
    fun `maps pdf points bbox into top left overlay space`() {
        val range = CfiPdfMapper.PdfRange(
            fingerprint = "ff",
            page = 1,
            x = 100f,
            y = 700f,
            width = 200f,
            height = 50f,
        )
        val rect = PdfHighlightOverlay.toOverlayRect(range, 612f, 792f, 612f, 792f)
        assertNotNull(rect)
        assertEquals(100f, rect!!.left, 0.01f)
        assertEquals(42f, rect.top, 0.01f)
        assertEquals(200f, rect.width, 0.01f)
        assertEquals(50f, rect.height, 0.01f)
    }
}
