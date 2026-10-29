package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReaderSpreadPolicyTest {

    @Test
    fun `double page forces paginated foliate and two columns`() {
        val features = ReaderFeatureState(doublePage = true, scrolled = true, columnsPerPage = 1)
        assertFalse(ReaderSpreadPolicy.foliateScrolled(features))
        assertEquals(2, ReaderSpreadPolicy.foliateMaxColumnCount(features))
    }

    @Test
    fun `lite tier still allows spread columns when double page`() {
        val features = ReaderFeatureState(
            doublePage = true,
            perfTier = ReaderPerformancePolicy.TIER_LITE,
        )
        assertEquals(2, ReaderSpreadPolicy.foliateMaxColumnCount(features))
    }

    @Test
    fun `without spread uses perf and column prefs`() {
        val features = ReaderFeatureState(columnsPerPage = 2, perfTier = ReaderPerformancePolicy.TIER_NORMAL)
        assertEquals(2, ReaderSpreadPolicy.foliateMaxColumnCount(features))
        val lite = features.copy(perfTier = ReaderPerformancePolicy.TIER_LITE)
        assertEquals(1, ReaderSpreadPolicy.foliateMaxColumnCount(lite))
    }
}
