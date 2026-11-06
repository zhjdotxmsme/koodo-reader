package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.TextBlock
import com.koodoreader.engine.layout.TextMeasurers
import com.koodoreader.engine.layout.reader.ChapterDescriptor
import com.koodoreader.engine.layout.reader.ChapterLoader
import com.koodoreader.engine.layout.reader.TextLocation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextReaderViewModelTest {

    @Test
    fun `font change repaginates only current chapter and restores anchor`() = runTest {
        val loader = CountingLoader(300)
        val vm = TextReaderViewModel(loader, FakeAnnotationStore(), TextMeasurers.mono())
        vm.open(TextLocation("chapter-1", 10))
        vm.setFontScale(1.4f)
        assertEquals(listOf("chapter-1", "chapter-1"), loader.loaded)
        assertEquals(10, vm.currentLocation.start)
        vm.close()
    }

    private class FakeAnnotationStore : AnnotationStore {
        override fun saveHighlight(bookKey: String, location: TextLocation, color: Long) = Unit

        override fun saveNote(bookKey: String, location: TextLocation, text: String, color: Long) = Unit

        override fun addBookmark(bookKey: String, location: TextLocation, label: String) = Unit
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
            require(index >= 0)
            return SpineItem(
                index = index,
                href = "$id.xhtml",
                blocks = listOf(TextBlock.of(text, elementIndex = 0)),
            )
        }

        override fun close() = Unit
    }
}
