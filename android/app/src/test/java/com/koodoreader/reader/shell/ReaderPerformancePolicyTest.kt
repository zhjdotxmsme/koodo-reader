package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPerformancePolicyTest {

    @Test
    fun `normal tier keeps desktop column cap`() {
        val state = ReaderFeatureState(columnsPerPage = 2, perfTier = ReaderPerformancePolicy.TIER_NORMAL)
        assertFalse(ReaderPerformancePolicy.isLite(state))
        assertEquals(2, ReaderPerformancePolicy.effectiveMaxColumns(state))
        assertEquals(720, ReaderPerformancePolicy.foliateMaxInlineSizePx(state))
        assertEquals(1f, ReaderPerformancePolicy.pdfRenderScale(state), 0.001f)
    }

    @Test
    fun `lite tier forces single column and smaller raster`() {
        val state = ReaderFeatureState(columnsPerPage = 2, perfTier = ReaderPerformancePolicy.TIER_LITE)
        assertTrue(ReaderPerformancePolicy.isLite(state))
        assertEquals(1, ReaderPerformancePolicy.effectiveMaxColumns(state))
        assertEquals(560, ReaderPerformancePolicy.foliateMaxInlineSizePx(state))
        assertEquals(0.72f, ReaderPerformancePolicy.pdfRenderScale(state), 0.001f)
    }
}
