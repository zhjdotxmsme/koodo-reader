package com.koodoreader.engine.toc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Tests for [ProgressComputer].
 */
class ProgressComputerTest {

    // --- Boundary conditions ---

    @Test
    fun `spineIndex 0 offset 0 gives chapterPercent 0_0`() {
        val spine = listOf(
            SpineChapter(1000, "epubcfi(/6/2!)"),
            SpineChapter(2000, "epubcfi(/6/4!)"),
        )
        val pc = ProgressComputer(spine)
        val (cp, tp) = pc.compute(0, "/0")
        assertEquals(0f, cp)
        assertEquals(0f, tp)
    }

    @Test
    fun `last chapter offset at length gives totalPercent near 1_0`() {
        val spine = listOf(
            SpineChapter(1000, "epubcfi(/6/2!)"),
            SpineChapter(2000, "epubcfi(/6/4!)"),
        )
        val pc = ProgressComputer(spine)
        // last chapter (index 1) with offset = 2000 (its length)
        val (_, tp) = pc.compute(1, "/2000")
        // Total chars = 3000, all consumed → 1.0
        assertEquals(1f, tp, 0.001f)
    }

    @Test
    fun `first chapter offset at length gives chapterPercent 1_0`() {
        val spine = listOf(
            SpineChapter(1000, "epubcfi(/6/2!)"),
            SpineChapter(2000, "epubcfi(/6/4!)"),
        )
        val pc = ProgressComputer(spine)
        val (cp, tp) = pc.compute(0, "/1000")
        assertEquals(1f, cp, 0.001f)
        // 1000 of 3000 total
        assertEquals(1000f / 3000f, tp, 0.001f)
    }

    @Test
    fun `middle chapter correct fractions`() {
        val spine = listOf(
            SpineChapter(1000, "epubcfi(/6/2!)"),  // 0..999
            SpineChapter(1000, "epubcfi(/6/4!)"),  // 1000..1999  ← middle
            SpineChapter(1000, "epubcfi(/6/6!)"),  // 2000..2999
        )
        val pc = ProgressComputer(spine)
        // Middle chapter (index 1), offset 500 (middle of that chapter)
        val (cp, tp) = pc.compute(1, "/500")
        // Chapter percent: 500/1000 = 0.5
        assertEquals(0.5f, cp, 0.001f)
        // Total percent: (1000 + 500) / 3000 = 0.5
        assertEquals(0.5f, tp, 0.001f)
    }

    // --- Empty spine ---

    @Test
    fun `empty spine throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProgressComputer(emptyList())
        }
    }

    // --- Out of range spineIndex ---

    @Test
    fun `spineIndex out of range throws`() {
        val spine = listOf(SpineChapter(100, "epubcfi(/6/2!)"))
        val pc = ProgressComputer(spine)
        assertThrows(IndexOutOfBoundsException::class.java) {
            pc.compute(1, "/0")
        }
        assertThrows(IndexOutOfBoundsException::class.java) {
            pc.compute(-1, "/0")
        }
    }

    // --- CFI with no offset (returns 0) ---

    @Test
    fun `CFI without offset treated as offset 0`() {
        val spine = listOf(SpineChapter(1000, "epubcfi(/6/2!)"))
        val pc = ProgressComputer(spine)
        val (cp, tp) = pc.compute(0, "/")  // bare path, no offset
        assertEquals(0f, cp)
        assertEquals(0f, tp)
    }

    // --- Equal-weight chapters (length 1) ---

    @Test
    fun `equal weight chapters produce expected totalPercent`() {
        // 5 chapters, each weight 1
        val spine = List(5) { SpineChapter(1, "epubcfi(/6/${it * 2}!)") }
        val pc = ProgressComputer(spine)

        // After chapter 2 (index 1), offset 1 (at end of chapter 2)
        val (_, tp) = pc.compute(1, "/1")
        // (1 + 1) / 5 = 0.4
        assertEquals(0.4f, tp, 0.001f)

        // After chapter 5 (index 4), offset 1
        val (_, tpLast) = pc.compute(4, "/1")
        assertEquals(1f, tpLast, 0.001f)
    }
}
