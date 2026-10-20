package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderProgressMathTest {

    @Test
    fun `fraction is page over count and clamps empty books to zero`() {
        assertEquals(0.0, ReaderProgressMath.fraction(0, 0), 0.0)
        assertEquals(0.5, ReaderProgressMath.fraction(4, 10), 0.0)
        assertEquals(1.0, ReaderProgressMath.fraction(9, 10), 0.0)
        assertEquals(1.0, ReaderProgressMath.fraction(99, 10), 0.0)
    }

    @Test
    fun `pdf cfi round-trips the 1-based page number`() {
        assertEquals("pdf:12", ReaderProgressMath.pdfCfi(12))
        assertEquals(12, ReaderProgressMath.pageFromPdfCfi("pdf:12"))
        assertNull(ReaderProgressMath.pageFromPdfCfi("epubcfi(/6/4)"))
    }
}
