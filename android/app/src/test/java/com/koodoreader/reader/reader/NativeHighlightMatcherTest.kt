package com.koodoreader.reader.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeHighlightMatcherTest {
    private val oldPoint = "epubcfi(/6/2!/2/0/2/1:3)"
    private val range = "epubcfi(/6/2!/2/0/2,/1:3,/1:5)"
    private val later = "epubcfi(/6/4!/2/0/2,/1:8,/1:9)"

    @Test
    fun `exact range matches`() {
        assertTrue(NativeHighlightMatcher.matches(range, range))
    }

    @Test
    fun `legacy point matches only the same selection start`() {
        assertTrue(NativeHighlightMatcher.matches(range, oldPoint))
        assertFalse(NativeHighlightMatcher.matches(later, oldPoint))
    }

    @Test
    fun `later chapter and partial overlap are not delete matches`() {
        assertFalse(NativeHighlightMatcher.matches(later, range))
        assertFalse(NativeHighlightMatcher.matches("epubcfi(/6/2!/2/0/2,/1:4,/1:6)", range))
    }

    @Test
    fun `blank and malformed values never match`() {
        assertFalse(NativeHighlightMatcher.matches("", oldPoint))
        assertFalse(NativeHighlightMatcher.matches(range, "bad"))
        assertTrue(NativeHighlightMatcher.startPointOrNull("bad") == null)
        assertTrue(oldPoint == NativeHighlightMatcher.startPointOrNull(range))
    }
}
