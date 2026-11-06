package com.koodoreader.engine.layout.reader

import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.TextBlock
import com.koodoreader.engine.layout.TextMeasurers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeTextSessionTest {

    @Test
    fun `opening first chapter does not load the rest`() {
        val loader = CountingLoader(chapters = 300)
        val session = NativeTextSession(loader, TextMeasurers.mono(), PaginatorOptions())

        session.openAt(TextLocation("chapter-1", 0, 0), 400f, 700f)

        assertEquals(listOf("chapter-1"), loader.loaded)
    }

    @Test
    fun `next page keeps the current chapter layout`() {
        val text = "0123456789".repeat(10)
        val loader = CountingLoader(chapters = 2, text = text)
        val session = NativeTextSession(
            loader,
            TextMeasurers.mono(),
            PaginatorOptions(fontSizePx = 10f, lineHeightMultiple = 1f),
        )
        session.openAt(TextLocation("chapter-1", 0), 40f, 50f)
        val firstLayout = session.current.layout

        session.next()

        assertSame(firstLayout, session.current.layout)
        assertEquals(16, session.current.location.start)
        assertEquals(listOf("chapter-1"), loader.loaded)
    }

    @Test
    fun `previous page keeps the current chapter layout`() {
        val text = "0123456789".repeat(10)
        val loader = CountingLoader(chapters = 2, text = text)
        val session = NativeTextSession(
            loader,
            TextMeasurers.mono(),
            PaginatorOptions(fontSizePx = 10f, lineHeightMultiple = 1f),
        )
        session.openAt(TextLocation("chapter-1", 0), 40f, 50f)
        session.next()
        val layout = session.current.layout

        session.previous()

        assertSame(layout, session.current.layout)
        assertEquals(TextLocation("chapter-1", 0, 16), session.current.location)
        assertEquals(listOf("chapter-1"), loader.loaded)
    }

    @Test
    fun `next at chapter end loads only the following chapter`() {
        val loader = CountingLoader(chapters = 300)
        val session = NativeTextSession(loader, TextMeasurers.mono(), PaginatorOptions())
        session.openAt(TextLocation("chapter-1", 0), 400f, 700f)
        val oldFrame = session.current

        session.next()

        assertEquals("chapter-2", session.current.chapterId)
        assertEquals(TextLocation("chapter-2", 0, 5), session.current.location)
        assertEquals(listOf("chapter-1", "chapter-2"), loader.loaded)
        assertEquals(1, session.current.layout.chapters.size)
        assertEquals(1, session.current.layout.chapters.single().spineIndex)
        assertEquals(0, oldFrame.layout.chapters.single().spineIndex)
    }

    @Test
    fun `previous at chapter start loads only the preceding chapter`() {
        val text = "0123456789".repeat(10)
        val loader = CountingLoader(chapters = 3, text = text)
        val session = NativeTextSession(
            loader,
            TextMeasurers.mono(),
            PaginatorOptions(fontSizePx = 10f, lineHeightMultiple = 1f),
        )
        session.openAt(TextLocation("chapter-2", 0), 40f, 50f)

        session.previous()

        assertEquals("chapter-1", session.current.chapterId)
        assertEquals(listOf("chapter-2", "chapter-1"), loader.loaded)
        assertEquals(1, session.current.layout.chapters.size)
        // Lands on the last page of the preceding chapter (engine pagination:
        // the 100-char fixture ends with a 92..100 page).
        assertEquals(92, session.current.location.start)
        assertEquals(100, session.current.location.end)
    }

    @Test
    fun `opening the last chapter loads only that chapter`() {
        val loader = CountingLoader(chapters = 300)
        val session = NativeTextSession(loader, TextMeasurers.mono(), PaginatorOptions())

        session.openAt(TextLocation("chapter-300", 0), 400f, 700f)

        assertEquals(listOf("chapter-300"), loader.loaded)
        assertEquals("chapter-300", session.current.chapterId)
        assertEquals(1, session.current.layout.chapters.size)
    }

    @Test
    fun `openAt lands on the page containing the requested offset`() {
        val text = "0123456789".repeat(10)
        val loader = CountingLoader(chapters = 2, text = text)
        val session = NativeTextSession(
            loader,
            TextMeasurers.mono(),
            PaginatorOptions(fontSizePx = 10f, lineHeightMultiple = 1f),
        )

        session.openAt(TextLocation("chapter-1", 40), 40f, 50f)

        // The requested anchor is preserved verbatim (CFI compatibility)…
        assertEquals(TextLocation("chapter-1", 40), session.current.location)
        // …while the session landed on page 32..48: previous() goes to 16..32.
        session.previous()
        assertEquals(TextLocation("chapter-1", 16, 32), session.current.location)
    }

    @Test
    fun `openAt rejects an unknown chapter without loading anything`() {
        val loader = CountingLoader(chapters = 3)
        val session = NativeTextSession(loader, TextMeasurers.mono(), PaginatorOptions())

        assertThrows(IllegalArgumentException::class.java) {
            session.openAt(TextLocation("missing", 0), 400f, 700f)
        }
        assertEquals(emptyList<String>(), loader.loaded)
    }

    @Test
    fun `openAt rejects negative and inverted offsets`() {
        val loader = CountingLoader(chapters = 1)
        val session = NativeTextSession(loader, TextMeasurers.mono(), PaginatorOptions())

        assertThrows(IllegalArgumentException::class.java) {
            session.openAt(TextLocation("chapter-1", -1), 400f, 700f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            session.openAt(TextLocation("chapter-1", 4, 2), 400f, 700f)
        }
    }

    @Test
    fun `openAt preserves beyond end anchors on the last page`() {
        val loader = CountingLoader(chapters = 1)
        val session = NativeTextSession(loader, TextMeasurers.mono(), PaginatorOptions())

        session.openAt(TextLocation("chapter-1", 99, 120), 400f, 700f)

        assertEquals("chapter-1", session.current.chapterId)
        assertEquals(TextLocation("chapter-1", 99, 120), session.current.location)
        assertEquals(listOf("chapter-1"), loader.loaded)
    }

    private class CountingLoader(chapters: Int, private val text: String = "hello") : ChapterLoader {
        val loaded = mutableListOf<String>()
        private val descriptors = List(chapters) { index ->
            ChapterDescriptor("chapter-${index + 1}", "Chapter ${index + 1}", 1, text.length)
        }

        override fun descriptors(): List<ChapterDescriptor> = descriptors

        override fun load(id: String): SpineItem {
            loaded += id
            val index = descriptors.indexOfFirst { it.id == id }
            require(index >= 0) { "Unknown chapter: $id" }
            return SpineItem(
                index = index,
                href = "$id.xhtml",
                blocks = listOf(TextBlock.of(text, elementIndex = 0)),
            )
        }

        override fun close() = Unit
    }
}
