package com.koodoreader.core.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderContractsTest {
    @Test
    fun `parsed book carries chapter descriptors`() {
        val parsed = ParsedBook(
            title = "T",
            author = "A",
            chapters = listOf(ChapterDescriptor("c1", "One", 1)),
        )
        assertEquals("c1", parsed.chapters.single().id)
    }
}
