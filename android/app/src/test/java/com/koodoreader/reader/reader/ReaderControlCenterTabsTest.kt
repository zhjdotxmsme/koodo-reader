package com.koodoreader.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderControlCenterTabsTest {

    @Test
    fun pdf_includesPdfTab() {
        val tabs = ReaderControlCenterTabs.forFormat(ReaderFormatKind.PDF)
        assertTrue(tabs.contains(ReaderControlCenterTab.PDF))
        assertEquals(4, tabs.size)
    }

    @Test
    fun reflow_epub_excludesPdfTab() {
        val tabs = ReaderControlCenterTabs.forFormat(ReaderFormatKind.REFLOW)
        assertFalse(tabs.contains(ReaderControlCenterTab.PDF))
        assertEquals(3, tabs.size)
    }

    @Test
    fun defaultTab_honorsInitialWhenVisible() {
        val tab = ReaderControlCenterTabs.defaultTab(
            ReaderFormatKind.PDF,
            initial = ReaderControlCenterTab.PDF,
        )
        assertEquals(ReaderControlCenterTab.PDF, tab)
    }

    @Test
    fun defaultTab_fallsBackWhenInitialHidden() {
        val tab = ReaderControlCenterTabs.defaultTab(
            ReaderFormatKind.REFLOW,
            initial = ReaderControlCenterTab.PDF,
        )
        assertEquals(ReaderControlCenterTab.APPEARANCE, tab)
    }
}