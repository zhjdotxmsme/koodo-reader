package com.koodoreader.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CfiAddressing].
 *
 * The addressing bridge is the contract with the desktop app: a [LayoutPosition]
 * must round-trip through a CFI string byte-for-byte, and the chapter-level form
 * (no element) must produce a valid chapter CFI.
 */
class CfiAddressingTest {

    // ── toCfi ───────────────────────────────────────────────────────────────

    @Test
    fun `element position produces point CFI with offset step`() {
        val pos = LayoutPosition(spineIndex = 3, elementIndex = 2, charOffset = 15)
        val cfi = CfiAddressing.toCfi(pos)
        assertEquals("epubcfi(/6/3!/2/0/2/1:15)", cfi)
    }

    @Test
    fun `chapter start produces chapter CFI without offset`() {
        val pos = LayoutPosition(spineIndex = 1, elementIndex = -1, charOffset = 0)
        val cfi = CfiAddressing.toCfi(pos)
        assertEquals("epubcfi(/6/1!/2/0)", cfi)
    }

    @Test
    fun `zero position produces minimal CFI`() {
        val pos = LayoutPosition(spineIndex = 0, elementIndex = 0, charOffset = 0)
        val cfi = CfiAddressing.toCfi(pos)
        assertEquals("epubcfi(/6/0!/2/0/0/1:0)", cfi)
    }

    @Test
    fun `large spine index is preserved`() {
        val pos = LayoutPosition(spineIndex = 42, elementIndex = 7, charOffset = 100)
        val cfi = CfiAddressing.toCfi(pos)
        assertTrue(cfi.contains("/6/42!/2/0/7/1:100"))
    }

    // ── fromCfi ─────────────────────────────────────────────────────────────

    @Test
    fun `fromCfi parses element position`() {
        val cfi = "epubcfi(/6/3!/2/0/2/1:15)"
        val pos = CfiAddressing.fromCfi(cfi)
        assertNotNull(pos)
        assertEquals(3, pos!!.spineIndex)
        assertEquals(2, pos.elementIndex)
        assertEquals(15, pos.charOffset)
    }

    @Test
    fun `fromCfi parses chapter start`() {
        val cfi = "epubcfi(/6/1!/2/0)"
        val pos = CfiAddressing.fromCfi(cfi)
        assertNotNull(pos)
        assertEquals(1, pos!!.spineIndex)
        assertEquals(-1, pos.elementIndex)
        assertEquals(0, pos.charOffset)
        assertTrue(pos.isChapterStart)
    }

    @Test
    fun `fromCfi returns null for malformed input`() {
        assertNull(CfiAddressing.fromCfi("not-a-cfi"))
        assertNull(CfiAddressing.fromCfi("epubcfi(/6/3)"))       // missing document 2
        assertNull(CfiAddressing.fromCfi("epubcfi(/6/3!/9/9)"))   // wrong element chain
    }

    @Test
    fun `fromCfi returns null for range CFI`() {
        // Range CFIs have a comma; the parser rejects them for point addressing.
        assertNull(CfiAddressing.fromCfi("epubcfi(/6/3!/2/0/2/1:15,/6/3!/2/0/2/1:20)"))
    }

    // ── round-trip ──────────────────────────────────────────────────────────

    @Test
    fun `round-trip preserves position`() {
        val original = LayoutPosition(spineIndex = 5, elementIndex = 3, charOffset = 42)
        val cfi = CfiAddressing.toCfi(original)
        val parsed = CfiAddressing.fromCfi(cfi)
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun `round-trip preserves chapter start`() {
        val original = LayoutPosition(spineIndex = 2, elementIndex = -1, charOffset = 0)
        val cfi = CfiAddressing.toCfi(original)
        val parsed = CfiAddressing.fromCfi(cfi)
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun `round-trip is stable under canonicalize`() {
        val pos = LayoutPosition(spineIndex = 3, elementIndex = 2, charOffset = 15)
        val canonical = CfiAddressing.canonicalize(pos)
        val cfi = CfiAddressing.toCfi(pos)
        assertEquals(cfi, canonical)
    }

    // ── custom mapping ──────────────────────────────────────────────────────

    @Test
    fun `custom mapping changes package index`() {
        val mapping = CfiAddressing.Mapping(packageIndex = 4)
        val pos = LayoutPosition(spineIndex = 0, elementIndex = 0, charOffset = 0)
        val cfi = CfiAddressing.toCfi(pos, mapping)
        assertEquals("epubcfi(/4/0!/2/0/0/1:0)", cfi)
        val parsed = CfiAddressing.fromCfi(cfi, mapping)
        assertNotNull(parsed)
        assertEquals(pos, parsed)
    }

    @Test
    fun `custom mapping changes element chain`() {
        val mapping = CfiAddressing.Mapping(elementChain = listOf(4, 2))
        val pos = LayoutPosition(spineIndex = 0, elementIndex = 0, charOffset = 0)
        val cfi = CfiAddressing.toCfi(pos, mapping)
        assertEquals("epubcfi(/6/0!/4/2/0/1:0)", cfi)
    }

    @Test
    fun `custom mapping with wrong chain is rejected by fromCfi`() {
        val mapping = CfiAddressing.Mapping(elementChain = listOf(4, 2))
        val cfi = "epubcfi(/6/0!/2/0/0/1:0)" // default chain
        assertNull(CfiAddressing.fromCfi(cfi, mapping))
    }

    // ── LayoutResult integration ────────────────────────────────────────────

    @Test
    fun `fromCfi with result validates position`() {
        val measurer = TextMeasurers.mono()
        val tokens = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            fontSizePx = 10f,
            lineHeightMultiple = 1.5f,
        )
        val doc = EpubDocument(
            listOf(
                SpineItem(0, "ch1.xhtml", blocks = listOf(TextBlock.of("hello", elementIndex = 0))),
            ),
        )
        val result = LayoutEngine(measurer).layout(doc, tokens)

        // Valid position resolves.
        val cfi = CfiAddressing.toCfi(LayoutPosition(0, 0, 2))
        val pos = CfiAddressing.fromCfi(cfi, result)
        assertNotNull(pos)

        // Invalid spine index is rejected.
        val badCfi = CfiAddressing.toCfi(LayoutPosition(99, 0, 0))
        assertNull(CfiAddressing.fromCfi(badCfi, result))
    }

    companion object {
        private const val EPS = 0.01f
    }
}
