package com.koodoreader.reader.shell

import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.engine.annotate.HighlightColor
import com.koodoreader.engine.pdf.CfiPdfMapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfReflowHighlightsTest {

    @Test
    fun `json includes reflow bbox on matching page only`() {
        val range = CfiPdfMapper.PdfRange(
            fingerprint = "ab",
            page = 2,
            x = 10f,
            y = 20f,
            width = 100f,
            height = 18f,
            reflowDocument = true,
        )
        val notes = listOf(
            NoteEntity(
                key = "1",
                bookKey = "bk",
                chapter = "PDF · 2",
                text = "hello",
                cfi = CfiPdfMapper.serialise(range),
                color = HighlightColor.YELLOW.code.toLong(),
            ),
            NoteEntity(
                key = "2",
                bookKey = "bk",
                chapter = "PDF · 3",
                text = "other page",
                cfi = CfiPdfMapper.serialise(range.copy(page = 3)),
                color = HighlightColor.BLUE.code.toLong(),
            ),
        )
        val json = PdfReflowHighlights.jsonForPage(notes, page = 2)
        assertTrue(json.contains("\"l\":10"))
        assertTrue(json.contains("\"t\":20"))
        assertFalse(json.contains("\"t\":20") && json.contains("\"l\":10") && json.split("{" ).size > 3)
    }

    @Test
    fun `paint script references overlay marker`() {
        val js = PdfReflowHighlights.paintJavascript("""[{"l":1,"t":2,"w":3,"h":4,"c":"#ffe066"}]""")
        assertTrue(js.contains("data-koodo-reflow-hl"))
        assertTrue(js.contains("document.body.style.position='relative'"))
    }
}
