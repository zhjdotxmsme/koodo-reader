package com.koodoreader.engine.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfAnnotationsTest {

    @Test
    fun buildAndSerialize() {
        val rng = CfiPdfMapper.PdfRange(
            fingerprint = "ff", page = 3, x = 1f, y = 2f, width = 30f, height = 18f,
        )
        val cfi = CfiPdfMapper.serialise(rng)
        val ann = PdfAnnotation.build(
            bookKey = "bk",
            cfi = cfi,
            text = "fox",
            note = "user note",
            color = "#FF00FF",
            style = PdfAnnotation.Style.UNDERLINE,
        )
        assertNotNull(ann)
        assertEquals("fox", ann!!.text)
        assertEquals(PdfAnnotation.Style.UNDERLINE, ann.style)
        assertEquals(cfi, ann.toCfiJson())
    }

    @Test
    fun fromRow() {
        val rng = CfiPdfMapper.PdfRange(fingerprint = "ff", page = 3)
        val row = mapOf(
            "bookKey" to "bk",
            "cfi" to CfiPdfMapper.serialise(rng),
            "text" to "fox",
            "note" to "note",
            "color" to "#00FF00",
            "style" to "highlight",
        )
        val ann = PdfAnnotation.fromRow(row)!!
        assertEquals("bk", ann.bookKey)
        assertEquals("fox", ann.text)
        assertEquals("#00FF00", ann.color)
        assertEquals(PdfAnnotation.Style.HIGHLIGHT, ann.style)
    }

    @Test
    fun fromRowReturnsNullOnMissingFields() {
        assertEquals(null, PdfAnnotation.fromRow(emptyMap()))
        assertEquals(null, PdfAnnotation.fromRow(mapOf("bookKey" to "x")))
        assertEquals(null, PdfAnnotation.fromRow(mapOf("bookKey" to "x", "cfi" to "garbage")))
    }

    @Test
    fun styleDesktopRoundTrip() {
        for (s in PdfAnnotation.Style.entries) {
            assertEquals(s, PdfAnnotation.Style.fromDesktop(s.desktopValue))
        }
        assertEquals(PdfAnnotation.Style.HIGHLIGHT, PdfAnnotation.Style.fromDesktop("???"))
    }

    @Test
    fun annotationLayerRoundTrip() {
        val rects = listOf(
            CfiPdfMapper.PdfRange(fingerprint = "ff", page = 2, x = 1f, y = 2f, width = 3f, height = 4f),
            CfiPdfMapper.PdfRange(fingerprint = "ff", page = 5, x = 6f, y = 7f, width = 8f, height = 9f),
        )
        val s = PdfLayerAnnotation.serialiseRects(rects)
        val parsed = PdfLayerAnnotation.parseRects(s)
        assertEquals(2, parsed.size)
        assertEquals(2, parsed[0].page)
        assertEquals(5, parsed[1].page)
        assertEquals(8f, parsed[1].width)
    }

    @Test
    fun annotationLayerEmpty() {
        assertTrue(PdfLayerAnnotation.parseRects(null).isEmpty())
        assertTrue(PdfLayerAnnotation.parseRects("").isEmpty())
        assertTrue(PdfLayerAnnotation.parseRects("garbage").isEmpty())
    }

    @Test
    fun parseRectsSkipsEntriesWithZeroSize() {
        val s = """[{"page":1,"rect":{"x":1,"y":1,"width":0,"height":5}}]"""
        assertFalse(PdfLayerAnnotation.parseRects(s).isEmpty())
        val s2 = """[{"page":1,"rect":{"x":1,"y":1,"width":5,"height":0}}]"""
        assertFalse(PdfLayerAnnotation.parseRects(s2).isEmpty())
    }
}