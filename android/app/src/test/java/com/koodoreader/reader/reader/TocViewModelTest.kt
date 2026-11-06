package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.TextBlock
import com.koodoreader.engine.layout.reader.ChapterDescriptor
import com.koodoreader.engine.layout.reader.ChapterLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TocViewModelTest {

    private fun loader() = object : ChapterLoader {
        override fun descriptors(): List<ChapterDescriptor> = listOf(
            ChapterDescriptor("c0", "序章", 1, 10),
            ChapterDescriptor("c1", "第一章 开始", 1, 10),
            ChapterDescriptor("c2", "第二章 后续", 1, 10),
        )

        override fun load(id: String): SpineItem =
            SpineItem(0, "$id.xhtml", blocks = listOf(TextBlock.of("x", elementIndex = 0)))

        override fun close() = Unit
    }

    @Test
    fun `empty query lists every chapter`() {
        assertEquals(3, TocViewModel(loader()).filtered().size)
    }

    @Test
    fun `query filters titles case insensitively`() {
        val vm = TocViewModel(loader())
        vm.setQuery("第二")
        assertEquals(listOf("c2"), vm.filtered().map { it.id })
    }

    @Test
    fun `current chapter is highlighted and follows navigation`() {
        val vm = TocViewModel(loader(), currentChapterId = "c1")
        assertTrue(vm.isCurrent("c1"))
        vm.setCurrentChapter("c2")
        assertTrue(vm.isCurrent("c2"))
        assertEquals(false, vm.isCurrent("c1"))
    }
}
