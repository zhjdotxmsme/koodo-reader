package com.koodoreader.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReaderOverflowCatalogTest {

    @Test
    fun pdf_overflow_order() {
        assertEquals(
            listOf(
                ReaderOverflowActionId.ZOOM_OUT,
                ReaderOverflowActionId.ZOOM_IN,
                ReaderOverflowActionId.OCR,
                ReaderOverflowActionId.SHARE,
                ReaderOverflowActionId.READING_OPTIONS,
                ReaderOverflowActionId.NOTES,
            ),
            ReaderOverflowCatalog.pdfIds(),
        )
    }

    @Test
    fun epub_foliate_skips_canvas_enhancements() {
        val ids = ReaderOverflowCatalog.epubIds(useFoliate = true)
        assertFalse(ids.contains(ReaderOverflowActionId.BIONIC))
        assertFalse(ids.contains(ReaderOverflowActionId.TWO_COLUMN))
        assertEquals(ReaderOverflowActionId.READING_OPTIONS, ids.first())
        assertEquals(ReaderOverflowActionId.ASK_AI, ids.last())
    }

    @Test
    fun epub_canvas_includes_enhancements_before_ai() {
        val ids = ReaderOverflowCatalog.epubIds(useFoliate = false)
        assertEquals(
            listOf(
                ReaderOverflowActionId.BIONIC,
                ReaderOverflowActionId.RULER,
                ReaderOverflowActionId.PARAGRAPH_MODE,
                ReaderOverflowActionId.SPEED_READING,
                ReaderOverflowActionId.TWO_COLUMN,
            ),
            ids.subList(4, 9),
        )
    }

}