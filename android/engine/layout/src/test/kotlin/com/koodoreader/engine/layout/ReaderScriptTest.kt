package com.koodoreader.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ReaderScript]'s per-block widow/orphan classification.
 *
 * A block whose text is CJK-dominant typesets fine with a single trailing
 * (or leading) line — Chinese books do it on every page — so the language-
 * aware paginator relaxes the Latin default of 2 to 1 for those blocks.
 */
class ReaderScriptTest {
    @Test
    fun `cjk and balanced mixed text use one line`() {
        assertEquals(1, ReaderScript.widowsOrphans("中文段落", 2))
        assertEquals(1, ReaderScript.widowsOrphans("中文 ab", 2))
        assertEquals(1, ReaderScript.widowsOrphans("かな 한글", 2))
    }

    @Test
    fun `latin dominant and non letters keep fallback`() {
        assertEquals(2, ReaderScript.widowsOrphans("English with 中", 2))
        assertEquals(2, ReaderScript.widowsOrphans("1234", 2))
        assertEquals(2, ReaderScript.widowsOrphans("", 2))
    }
}
