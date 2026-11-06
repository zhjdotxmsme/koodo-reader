package com.koodoreader.engine.layout.reader

import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.TextBlock
import com.koodoreader.engine.layout.TextMeasurers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T1 verification for [PageRenderer]:
 *  - [ReaderView] is a thin slice of a [ReaderFrame]; both modes carry the
 *    same [ReaderFrame.layout] so hit-testing is mode-independent (spec §6.3).
 *  - [PageRenderer.positionAt] maps a tap to the *chapter-relative*
 *    [TextLocation] the annotation layer consumes.
 *
 * The tests intentionally assert invariants, not specific page numbers —
 * page/line splitting depends on viewport/margins/line-height and is already
 * covered by the [NativeTextSessionTest] baselines.
 */
class PageRendererTest {

    private fun session(text: String = "hello world", fontPx: Float = 10f, width: Float = 40f, height: Float = 50f) =
        NativeTextSession(TextLoader(text), TextMeasurers.mono(), PaginatorOptions(fontSizePx = fontPx, lineHeightMultiple = 1.2f)).apply {
            openAt(TextLocation("ch-1", 0), width, height)
        }

    @Test
    fun `paged mode returns a valid page within the chapter pagination`() {
        val s = session()
        val view = PageRenderer.render(s.current, PageRenderMode.PAGED)

        assertTrue(view.page in 0 until view.totalPages, "page=${view.page} total=${view.totalPages}")
        assertTrue(view.totalPages >= 1)
        assertEquals(view.layout.pageCount, view.totalPages)
    }

    @Test
    fun `scroll mode reports the anchor y as page-relative px and the chapter height`() {
        val s = session()
        val view = PageRenderer.render(s.current, PageRenderMode.SCROLL)

        // Anchor at char 0 is the first line, which must start at y == 0
        // (the engine places the first line at the top of the first column).
        assertEquals(0f, view.scrollTopPx, 1e-4f)
        // Every line has positive height, so the chapter height is positive.
        assertTrue(view.scrollHeightPx > 0f, "scrollHeightPx=$view.scrollHeightPx")
    }

    @Test
    fun `both modes derive from the same layout so tap-to-select is mode-independent`() {
        val s = session()
        val paged = PageRenderer.render(s.current, PageRenderMode.PAGED)
        val scroll = PageRenderer.render(s.current, PageRenderMode.SCROLL)

        // ReaderFrame carries a single layout object; render() must not replace it.
        assertEquals(paged.layout, scroll.layout)
        assertEquals(paged.location, scroll.location)
    }

    @Test
    fun `both modes agree on the anchor for a location mid-chapter`() {
        val s = session()
        // Pick a location inside the laid-out chapter (char 2 of "hello world").
        val frame = s.current.copy(location = TextLocation("ch-1", 2, 2))
        val paged = PageRenderer.render(frame, PageRenderMode.PAGED)
        val scroll = PageRenderer.render(frame, PageRenderMode.SCROLL)

        assertEquals(paged.location, scroll.location)
        assertEquals(2, paged.location.start)
    }

    @Test
    fun `positionAt resolves a tap on the first line to a character of the first line`() {
        val s = session()
        // Tap near the top-left of the page: y=0 lands on the first line's
        // band [y, y+height) and x=0..10 is inside a 10px-wide first line.
        val pos = checkNotNull(PageRenderer.positionAt(s.current, 5f, 0f)) {
            "tap over the first line must resolve"
        }
        // The first line holds chars 0..4 of "hello world", so the resolved
        // character is within the first 5 characters of the chapter.
        assertTrue(pos.start in 0..4, "resolved char=${pos.start} should be in [0, 4]")
    }

    @Test
    fun `positionAt over empty space below the text returns null`() {
        val s = session()
        // 10_000px below the page bottom: no line band covers it.
        assertNull(PageRenderer.positionAt(s.current, 5f, 10_000f))
    }

    private class TextLoader(private val text: String) : ChapterLoader {
        override fun descriptors(): List<ChapterDescriptor> =
            listOf(ChapterDescriptor(id = "ch-1", title = null, level = 1, charCount = text.length))

        override fun load(id: String): SpineItem =
            SpineItem(index = 0, href = "ch-1.xhtml", blocks = listOf(TextBlock.of(text, elementIndex = 0)))

        override fun close() = Unit
    }
}
