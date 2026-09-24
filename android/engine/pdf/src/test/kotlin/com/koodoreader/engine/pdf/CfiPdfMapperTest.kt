package com.koodoreader.engine.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CfiPdfMapperTest {

    @Test
    fun roundTripWithBbox() {
        val r = CfiPdfMapper.PdfRange(
            fingerprint = "deadbeef", page = 12,
            x = 34.5f, y = 678f, width = 120f, height = 18f,
        )
        val json = CfiPdfMapper.serialise(r)
        val parsed = CfiPdfMapper.parse(json)
        assertNotNull(parsed)
        assertEquals(r.page, parsed!!.page)
        assertEquals(r.fingerprint, parsed.fingerprint)
        assertEquals(r.x, parsed.x)
        assertEquals(r.width, parsed.width)
        assertEquals(r.height, parsed.height)
    }

    @Test
    fun pageLevelSerialisationStripsBboxKeys() {
        val r = CfiPdfMapper.PdfRange(fingerprint = "ff", page = 7)
        val json = CfiPdfMapper.serialise(r)
        assertFalse("\"x\":" in json, "got $json")
        assertFalse("\"width\":" in json, "got $json")
        assertFalse("\"height\":" in json, "got $json")
        val parsed = CfiPdfMapper.parse(json)!!
        assertTrue(parsed.isPageLevel)
        assertEquals(7, parsed.page)
    }

    @Test
    fun chapterDocIndexIsOneBased() {
        val r = CfiPdfMapper.PdfRange(fingerprint = "ff", page = 1)
        val json = CfiPdfMapper.serialise(r)
        assertTrue("\"chapterDocIndex\":0" in json, "got $json")
        assertTrue("\"chapterHref\":\"title0\"" in json, "got $json")
    }

    @Test
    fun legacyChapterDocIndexFallback() {
        // Older desktop notes used `chapterDocIndex` (0-based) + no `page`.
        val json = """{"chapterDocIndex":11,"x":1,"y":2,"width":3,"height":4}"""
        val parsed = CfiPdfMapper.parse(json)!!
        assertEquals(12, parsed.page)
        assertEquals(3f, parsed.width)
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(CfiPdfMapper.parse(null))
        assertNull(CfiPdfMapper.parse(""))
        assertNull(CfiPdfMapper.parse("not json"))
    }

    @Test
    fun pageStepCfiFormat() {
        val r = CfiPdfMapper.PdfRange(
            fingerprint = "ff", page = 12, x = 1f, y = 2f, width = 3f, height = 4f,
        )
        val cfi = CfiPdfMapper.pageStepCfi(r)
        assertEquals("/page(12)/t(1.0,2.0,3.0,4.0)", cfi)
    }

    @Test
    fun pageLevelStepCfiHasNoTComponent() {
        val r = CfiPdfMapper.PdfRange(fingerprint = "ff", page = 5)
        assertEquals("/page(5)", CfiPdfMapper.pageStepCfi(r))
    }
}