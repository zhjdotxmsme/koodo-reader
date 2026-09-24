package com.koodoreader.engine.annotate

import com.koodoreader.engine.cfi.parseOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Test [CfiAnchor] gateway using engine/cfi parseOrNull as the oracle.
 * Golden vectors cover the six required cases plus additional boundary cases.
 */
class CfiAnchorTest {

    private companion object {
        /**
         * A canonical range CFI: `epubcfi(parent,start,end)` — THREE comma-separated
         * parts. A two-part `epubcfi(start,end)` is not a CFI range: `:engine:cfi`
         * rejects it with `CFI_RANGE_INCOMPLETE` (pinned by `CfiInvariantChecks`),
         * because upstream foliate would otherwise leave `end` undefined.
         */
        const val RANGE_1_TO_2 = "epubcfi(/6/4!/4/2,/1:0,/2:5)"
        const val RANGE_1_TO_3 = "epubcfi(/6/4!/4/2,/1:0,/3:10)"
    }

    // ── isValid / isPoint / isRange ─────────────────────────────────────────

    @Test
    fun `isValid returns true for a valid point CFI`() {
        assertTrue(CfiAnchor.isValid("epubcfi(/6/4!/4/2/1:0)"))
    }

    @Test
    fun `isValid returns true for a valid range CFI`() {
        assertTrue(CfiAnchor.isValid(RANGE_1_TO_2))
    }

    @Test
    fun `isValid returns false for null`() {
        assertFalse(CfiAnchor.isValid(null))
    }

    @Test
    fun `isValid returns false for blank string`() {
        assertFalse(CfiAnchor.isValid("   "))
    }

    @Test
    fun `isValid returns false for malformed CFI`() {
        assertFalse(CfiAnchor.isValid("not-a-cfi"))
        assertFalse(CfiAnchor.isValid("epubcfi(/)"))
        assertFalse(CfiAnchor.isValid(""))
    }

    @Test
    fun `isPoint returns true for point CFI`() {
        assertTrue(CfiAnchor.isPoint("epubcfi(/6/4!/4/2/3:0)"))
    }

    @Test
    fun `isPoint returns false for range CFI`() {
        assertFalse(CfiAnchor.isPoint(RANGE_1_TO_2))
    }

    @Test
    fun `isRange returns true for range CFI`() {
        assertTrue(CfiAnchor.isRange(RANGE_1_TO_2))
    }

    @Test
    fun `isRange returns false for point CFI`() {
        assertFalse(CfiAnchor.isRange("epubcfi(/6/4!/4/2/3:0)"))
    }

    // ── Golden vector 1 — single-point bookmark ───────────────────────────────

    @Test
    fun `golden vector 1 — single-point bookmark canonicalises correctly`() {
        val raw = "epubcfi(/6/4!/4/2/3:0)"
        val canonical = CfiAnchor.requireValid(raw)
        assertEquals(raw, canonical)
        assertTrue(CfiAnchor.isPoint(canonical))
        assertTrue(CfiAnchor.isValid(canonical))
        // parseOrNull agrees
        assertTrue(parseOrNull(raw) != null)
    }

    // ── Golden vector 2 — cross-line highlight (range) ──────────────────────

    @Test
    fun `golden vector 2 — cross-line range canonicalises and builds correctly`() {
        val start = "epubcfi(/6/4!/4/2/1:0)"
        val end = "epubcfi(/6/4!/4/2/3:10)"
        val range = CfiAnchor.rangeCfi(start, end)
        assertTrue(CfiAnchor.isRange(range))
        assertEquals(start, CfiAnchor.startPoint(range))
        assertEquals(end, CfiAnchor.endPoint(range))
    }

    // ── Golden vector 3 — cross-chapter note ────────────────────────────────

    @Test
    fun `golden vector 3 — cross-chapter note uses cross-document range`() {
        val start = "epubcfi(/6/2!/4/2/1:0)"
        val end = "epubcfi(/6/4!/4/2/3:10)"
        val range = CfiAnchor.rangeCfi(start, end)
        assertTrue(CfiAnchor.isRange(range))
        // startPoint / endPoint collapse correctly
        assertEquals(start, CfiAnchor.startPoint(range))
        // CAVEAT (upstream-faithful, see :engine:cfi buildRange): `buildRange` only
        // supports ranges inside ONE document — the non-local prefix is copied from
        // `from` verbatim. So a cross-document range keeps the start's chapter prefix
        // when collapsed to its end. The desktop engine behaves identically, which is
        // what ADR-002 requires; the cross-chapter case must therefore be modelled by
        // the caller (one annotation per chapter), not by a single range CFI.
        assertEquals("epubcfi(/6/2!/4/2/3:10)", CfiAnchor.endPoint(range))
    }

    // ── Golden vector 4 — pure text offset ─────────────────────────────────

    @Test
    fun `golden vector 4 — pure text offset is preserved through canonicalise`() {
        val withOffset = "epubcfi(/6/4!/4/2/1:42)"
        val canonical = CfiAnchor.requireValid(withOffset)
        assertEquals(withOffset, canonical)
        assertTrue(CfiAnchor.isPoint(canonical))
    }

    // ── Golden vector 5 — CFI with ID assertion ─────────────────────────────

    @Test
    fun `golden vector 5 — CFI with ID assertion canonicalises correctly`() {
        val withId = "epubcfi(/6/4[chap01ref]!/4/2/1:0)"
        val canonical = CfiAnchor.requireValid(withId)
        assertEquals(withId, canonical)
        assertTrue(CfiAnchor.isPoint(canonical))
        assertTrue(CfiAnchor.isValid(canonical))
    }

    // ── Golden vector 6 — empty and malformed inputs ────────────────────────

    @Test
    fun `golden vector 6 — null and blank inputs are rejected by isValid`() {
        assertFalse(CfiAnchor.isValid(null))
        assertFalse(CfiAnchor.isValid(""))
        assertFalse(CfiAnchor.isValid("  "))
    }

    @Test
    fun `golden vector 6 — malformed CFI throws from requireValid`() {
        var caught: IllegalArgumentException? = null
        try {
            CfiAnchor.requireValid("epubcfi(bad)")
        } catch (e: IllegalArgumentException) {
            caught = e
        }
        assertTrue(caught != null)
    }

    // ── Additional vectors ─────────────────────────────────────────────────

    @Test
    fun `CFI with temporal offset parses correctly`() {
        val temporal = "epubcfi(/6/4!/4/2/1~0.5)"
        assertTrue(CfiAnchor.isValid(temporal))
        assertTrue(CfiAnchor.isPoint(temporal))
    }

    @Test
    fun `CFI with spatial offset parses correctly`() {
        val spatial = "epubcfi(/6/4!/4/2/1@100:200)"
        assertTrue(CfiAnchor.isValid(spatial))
        assertTrue(CfiAnchor.isPoint(spatial))
    }

    @Test
    fun `versioned epubcfi URI spelling is not an annotation position`() {
        // The `epubcfi:0?` URI spelling is a LINK form, normalized by `:engine:link`
        // (LinkClassifier.stripEpubCfiVersion) before it reaches the CFI core. Stored
        // annotation positions are always the wrapped `epubcfi(...)` form the desktop
        // writes, so the annotation gateway deliberately rejects the URI spelling
        // instead of carrying a second normalization site.
        assertFalse(CfiAnchor.isValid("epubcfi:0?/6/4!/4/2/1:0"))
        assertTrue(CfiAnchor.isValid("epubcfi(/6/4!/4/2/1:0)"))
    }

    // ── requirePoint ────────────────────────────────────────────────────────

    @Test
    fun `requirePoint returns canonical form for valid point`() {
        val raw = "epubcfi(/6/4!/4/2/3:0)"
        assertEquals(raw, CfiAnchor.requirePoint(raw))
    }

    @Test
    fun `requirePoint throws for a range CFI`() {
        var caught: IllegalArgumentException? = null
        try {
            CfiAnchor.requirePoint(RANGE_1_TO_2)
        } catch (e: IllegalArgumentException) {
            caught = e
        }
        assertTrue(caught?.message?.startsWith("expected a point CFI") == true)
    }

    // ── compare ────────────────────────────────────────────────────────────

    @Test
    fun `compare returns 0 for identical CFIs`() {
        val cfi = "epubcfi(/6/4!/4/2/1:0)"
        assertEquals(0, CfiAnchor.compare(cfi, cfi))
    }

    @Test
    fun `compare returns non-zero for different CFIs`() {
        val a = "epubcfi(/6/4!/4/2/1:0)"
        val b = "epubcfi(/6/4!/4/2/2:0)"
        assertNotEquals(0, CfiAnchor.compare(a, b))
    }

    @Test
    fun `compare orders CFIs by document position`() {
        val earlier = "epubcfi(/6/4!/4/2/1:0)"
        val later = "epubcfi(/6/4!/4/2/3:0)"
        assertTrue(CfiAnchor.compare(earlier, later) < 0)
        assertTrue(CfiAnchor.compare(later, earlier) > 0)
    }

    // ── overlaps ───────────────────────────────────────────────────────────

    @Test
    fun `overlaps returns true for overlapping intervals`() {
        // [A-start, A-end] overlaps [B-start, B-end] when not (A-end < B-start or B-end < A-start)
        val aStart = "epubcfi(/6/4!/4/2/1:0)"
        val aEnd = "epubcfi(/6/4!/4/2/5:0)"
        val bStart = "epubcfi(/6/4!/4/2/3:0)"
        val bEnd = "epubcfi(/6/4!/4/2/7:0)"
        assertTrue(CfiAnchor.overlaps(aStart, aEnd, bStart, bEnd))
    }

    @Test
    fun `overlaps returns true for touching endpoints`() {
        val aStart = "epubcfi(/6/4!/4/2/1:0)"
        val aEnd = "epubcfi(/6/4!/4/2/3:0)"
        val bStart = "epubcfi(/6/4!/4/2/3:0)" // shares endpoint
        val bEnd = "epubcfi(/6/4!/4/2/5:0)"
        assertTrue(CfiAnchor.overlaps(aStart, aEnd, bStart, bEnd))
    }

    @Test
    fun `overlaps returns false for disjoint intervals`() {
        val aStart = "epubcfi(/6/4!/4/2/1:0)"
        val aEnd = "epubcfi(/6/4!/4/2/2:0)"
        val bStart = "epubcfi(/6/4!/4/2/4:0)"
        val bEnd = "epubcfi(/6/4!/4/2/5:0)"
        assertFalse(CfiAnchor.overlaps(aStart, aEnd, bStart, bEnd))
    }

    // ── collapse ───────────────────────────────────────────────────────────

    @Test
    fun `startPoint collapses range to its start`() {
        assertEquals("epubcfi(/6/4!/4/2/1:0)", CfiAnchor.startPoint(RANGE_1_TO_3))
    }

    @Test
    fun `endPoint collapses range to its end`() {
        assertEquals("epubcfi(/6/4!/4/2/3:10)", CfiAnchor.endPoint(RANGE_1_TO_3))
    }

    @Test
    fun `startPoint leaves point unchanged`() {
        val point = "epubcfi(/6/4!/4/2/3:5)"
        assertEquals(point, CfiAnchor.startPoint(point))
    }

    @Test
    fun `endPoint leaves point unchanged`() {
        val point = "epubcfi(/6/4!/4/2/3:5)"
        assertEquals(point, CfiAnchor.endPoint(point))
    }
}
