package com.koodoreader.core.reader

import com.koodoreader.core.importer.BookRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderMappersTest {

    @Test
    fun `book mapper preserves desktop identity and edited metadata`() {
        val record = BookRecord(
            key = "native-1",
            name = "Title",
            author = "Author",
            description = null,
            md5 = "md5",
            cover = null,
            format = "EPUB",
            publisher = null,
            size = 1L,
            page = null,
            path = "/books/native-1.epub",
            charset = null,
        )
        val book = ReaderMappers.toBook(record)
        assertEquals("native-1", book.bookKey)
        assertEquals("Title", ReaderMappers.toRecord(book).name)
        assertEquals("md5", ReaderMappers.toRecord(book).md5)
    }
}
