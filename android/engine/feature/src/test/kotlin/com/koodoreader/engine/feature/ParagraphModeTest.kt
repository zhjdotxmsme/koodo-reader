package com.koodoreader.engine.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ParagraphModeTest {

    @Test
    fun `blank lines separate paragraphs`() {
        assertEquals(
            listOf("One", "Two", "Three"),
            ParagraphMode.split("One\n\nTwo\n\n\nThree"),
        )
    }

    @Test
    fun `indented lines start a new paragraph`() {
        assertEquals(
            listOf("First paragraph", "Second paragraph"),
            ParagraphMode.split("First paragraph\n    Second paragraph"),
        )
        assertEquals(
            listOf("A", "B"),
            ParagraphMode.split("A\n\tB"),
        )
        assertEquals(
            listOf("A", "B"),
            ParagraphMode.split("A\n\u3000B"),
        )
        // one leading space does not count at the default threshold
        assertEquals("A B", ParagraphMode.split("A\n B").single())
        assertEquals(
            listOf("A", "B"),
            ParagraphMode.split("A\n B", minIndentSpaces = 1),
        )
    }

    @Test
    fun `wrapped latin lines are rejoined with a space`() {
        assertEquals(listOf("hello world"), ParagraphMode.split("hello\nworld"))
        assertEquals(
            listOf("a b", "c d"),
            ParagraphMode.split("a\nb\n\nc\nd"),
        )
    }

    @Test
    fun `wrapped cjk lines are rejoined without a space`() {
        assertEquals(
            listOf("\u4f60\u597d\u4e16\u754c"),
            ParagraphMode.split("\u4f60\u597d\n\u4e16\u754c"),
        )
    }

    @Test
    fun `windows line endings are normalised`() {
        assertEquals(listOf("a b"), ParagraphMode.split("a\r\nb"))
        assertEquals(listOf("a", "b"), ParagraphMode.split("a\r\n\r\nb"))
    }

    @Test
    fun `blank text yields no paragraphs`() {
        assertTrue(ParagraphMode.split("").isEmpty())
        assertTrue(ParagraphMode.split("   ").isEmpty())
        assertTrue(ParagraphMode.split("  \n\t\n  ").isEmpty())
    }

    @Test
    fun `paragraph text is trimmed`() {
        assertEquals(listOf("body"), ParagraphMode.split("\n\n   body   \n\n"))
    }

    @Test
    fun `navigation walks paragraphs and stops at the bounds`() {
        val mode = ParagraphMode(listOf("a", "b", "c"))
        assertEquals(3, mode.size)
        assertEquals("a", mode.current)
        assertTrue(mode.atStart)
        assertFalse(mode.atEnd)

        assertTrue(mode.next())
        assertEquals("b", mode.current)
        assertTrue(mode.next())
        assertEquals("c", mode.current)
        assertFalse(mode.next(), "cannot advance past the last paragraph")
        assertEquals("c", mode.current)

        assertTrue(mode.prev())
        assertEquals("b", mode.current)
        assertTrue(mode.prev())
        assertEquals("a", mode.current)
        assertFalse(mode.prev(), "cannot go back past the first paragraph")
    }

    @Test
    fun `progress reports the one based position`() {
        val mode = ParagraphMode(listOf("a", "b", "c"))
        assertEquals(1f / 3f, mode.progress, 1e-6f)
        mode.seek(2)
        assertEquals(1f, mode.progress, 1e-6f)
        mode.seek(0)
        assertEquals(1f / 3f, mode.progress, 1e-6f)
    }

    @Test
    fun `seek clamps and reports whether anything changed`() {
        val mode = ParagraphMode(listOf("a", "b", "c"))
        assertTrue(mode.seek(99))
        assertEquals(2, mode.index)
        assertFalse(mode.seek(99))
        assertTrue(mode.seek(-5))
        assertEquals(0, mode.index)
    }

    @Test
    fun `seekToFraction maps a progress fraction onto a paragraph`() {
        val mode = ParagraphMode(listOf("a", "b", "c", "d"))
        assertTrue(mode.seekToFraction(0.5f))
        assertEquals(2, mode.index)
        mode.seekToFraction(0f)
        assertEquals(0, mode.index)
        mode.seekToFraction(2f)
        assertEquals(3, mode.index)
    }

    @Test
    fun `start index is clamped into range`() {
        assertEquals(1, ParagraphMode(listOf("a", "b"), startIndex = 99).index)
        assertEquals(0, ParagraphMode(listOf("a", "b"), startIndex = -3).index)
        assertEquals(0, ParagraphMode(emptyList(), startIndex = 5).index)
    }

    @Test
    fun `mode toggles between scroll and paragraph`() {
        val mode = ParagraphMode(listOf("a", "b"))
        assertEquals(ReadingMode.SCROLL, mode.mode)
        assertEquals(ReadingMode.PARAGRAPH, mode.toggleMode())
        assertEquals(ReadingMode.PARAGRAPH, mode.mode)
        assertEquals(ReadingMode.SCROLL, mode.toggleMode())
        mode.setMode(ReadingMode.PARAGRAPH)
        assertEquals(ReadingMode.PARAGRAPH, mode.mode)
        // switching mode keeps the reading position
        mode.next()
        mode.setMode(ReadingMode.SCROLL)
        assertEquals(1, mode.index)
    }

    @Test
    fun `empty state machine is inert`() {
        val mode = ParagraphMode(emptyList())
        assertTrue(mode.isEmpty)
        assertEquals(0, mode.size)
        assertEquals("", mode.current)
        assertEquals(0f, mode.progress, 0f)
        assertTrue(mode.atStart)
        assertTrue(mode.atEnd)
        assertFalse(mode.next())
        assertFalse(mode.prev())
        assertFalse(mode.seek(3))
        assertFalse(mode.seekToFraction(0.5f))
    }

    @Test
    fun `fromText builds a ready to drive state machine`() {
        val mode = ParagraphMode.fromText(
            "One\n\nTwo",
            initialMode = ReadingMode.PARAGRAPH,
        )
        assertEquals(2, mode.size)
        assertEquals(ReadingMode.PARAGRAPH, mode.mode)
        assertEquals("One", mode.current)
    }
}
