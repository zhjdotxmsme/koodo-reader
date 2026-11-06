package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.TextBlock
import com.koodoreader.engine.layout.reader.ChapterDescriptor
import com.koodoreader.engine.layout.reader.ChapterLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChapterLoaderProgressTest {

    @Test
    fun `stored cfi resolves to chapter id and offset`() {
        val loader = FakeLoader(
            listOf(
                ChapterDescriptor("idref:a", "One", 1, 10),
                ChapterDescriptor("idref:b", "Two", 1, 10),
            ),
        )
        val loc = ChapterLoaderProgress.initialLocation(loader, "epubcfi(/6/2!/2/0/2/1:15)")
        assertEquals("idref:b", loc.chapterId)
        assertEquals(15, loc.start)
        loader.close()
    }

    @Test
    fun `location maps to cfi with spine offset`() {
        val loader = FakeLoader(
            listOf(
                ChapterDescriptor("idref:a", "One", 1, 10),
            ),
        )
        val cfi = ChapterLoaderProgress.cfiForLocation(loader, com.koodoreader.engine.layout.reader.TextLocation("idref:a", 3))
        assertEquals("epubcfi(/6/1!/2/0/0/1:3)", cfi)
        loader.close()
    }

    private class FakeLoader(
        private val descriptors: List<ChapterDescriptor>,
    ) : ChapterLoader {
        override fun descriptors(): List<ChapterDescriptor> = descriptors

        override fun load(id: String): SpineItem =
            SpineItem(index = 0, href = "$id.xhtml", blocks = listOf(TextBlock.of("abcd", 0)))

        override fun close() = Unit
    }
}
