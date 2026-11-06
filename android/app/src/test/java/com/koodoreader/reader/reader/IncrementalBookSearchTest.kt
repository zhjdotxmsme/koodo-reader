package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.TextBlock
import com.koodoreader.engine.layout.reader.ChapterDescriptor
import com.koodoreader.engine.layout.reader.ChapterLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IncrementalBookSearchTest {

    @Test
    fun `search stops loading chapters after cancellation`() = runTest {
        val loader = CountingLoader(300)
        val job: Job = launch {
            IncrementalBookSearch.search("needle", loader).collect { }
        }
        job.cancel()
        job.join()
        assertTrue(loader.loaded.size < 300)
    }

    private class CountingLoader(chapters: Int) : ChapterLoader {
        val loaded = mutableListOf<String>()
        private val descriptors = List(chapters) { index ->
            ChapterDescriptor("chapter-${index + 1}", "Chapter ${index + 1}", 1, 5)
        }

        override fun descriptors(): List<ChapterDescriptor> = descriptors

        override fun load(id: String): SpineItem {
            loaded += id
            val index = descriptors.indexOfFirst { it.id == id }
            require(index >= 0)
            return SpineItem(
                index = index,
                href = "$id.txt",
                blocks = listOf(TextBlock.of("hello", elementIndex = 0)),
            )
        }

        override fun close() = Unit
    }
}
