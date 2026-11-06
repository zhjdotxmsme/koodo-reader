package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.TextBlock
import com.koodoreader.engine.layout.TextMeasurers
import com.koodoreader.engine.layout.reader.ChapterDescriptor
import com.koodoreader.engine.layout.reader.ChapterLoader
import com.koodoreader.engine.layout.reader.TextLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Regression: [TextReaderViewModel.open] must not close a shared [ChapterLoader]
 * (EPUB zip stays open for the whole reading screen).
 */
class TextReaderViewModelLoaderLifecycleTest {

    @Test
    fun `reopen after viewport change does not close chapter loader`() {
        val loader = TrackingLoader()
        val vm = TextReaderViewModel(
            loader,
            NoOpAnnotationStore,
            TextMeasurers.mono(),
            viewportWidthPx = 400f,
            viewportHeightPx = 700f,
        )
        vm.open(TextLocation("c1", 0))
        assertEquals(0, loader.closeCount)
        vm.setViewport(500f, 800f)
        assertEquals(0, loader.closeCount)
        assertFalse(loader.closed)
        vm.close()
        assertEquals(0, loader.closeCount)
    }

    private class TrackingLoader : ChapterLoader {
        var closeCount = 0
        var closed = false

        override fun descriptors(): List<ChapterDescriptor> =
            listOf(ChapterDescriptor("c1", "One", 1, 5))

        override fun load(id: String): SpineItem =
            SpineItem(index = 0, href = "c1.xhtml", blocks = listOf(TextBlock.of("hello", elementIndex = 0)))

        override fun close() {
            closeCount++
            closed = true
        }
    }
}
