package com.koodoreader.reader.shell

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderSelectionUiPolicyTest {
    @Test
    fun `menu and handles are mutually exclusive`() {
        assertTrue(ReaderSelectionUiPolicy.showMenu(menuOpen = true, adjusting = false))
        assertFalse(ReaderSelectionUiPolicy.showMenu(menuOpen = true, adjusting = true))
        assertTrue(ReaderSelectionUiPolicy.showHandles(true, adjusting = true, useFoliate = true))
        assertFalse(ReaderSelectionUiPolicy.showHandles(true, adjusting = false, useFoliate = true))
    }

    @Test
    fun `closed or native selection never shows handles`() {
        assertFalse(ReaderSelectionUiPolicy.showHandles(false, adjusting = true, useFoliate = true))
        assertFalse(ReaderSelectionUiPolicy.showHandles(true, adjusting = true, useFoliate = false))
    }
}
