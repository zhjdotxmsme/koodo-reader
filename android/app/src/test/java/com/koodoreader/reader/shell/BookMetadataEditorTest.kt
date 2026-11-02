package com.koodoreader.reader.shell

import com.koodoreader.core.data.dao.BookDao
import com.koodoreader.core.data.entity.BookEntity
import com.koodoreader.core.importer.BookMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BookMetadataEditorTest {

    @Test
    fun `save changes metadata but preserves identity and file fields`() {
        val original = book().copy(md5 = "abc", path = "/books/1.epub", name = "Old")

        val edited = BookMetadataEditor.validate(original, BookMetadata("New", "A", "D", "P"))

        assertEquals("New", edited.name)
        assertEquals(original.key, edited.key)
        assertEquals(original.md5, edited.md5)
        assertEquals(original.path, edited.path)
    }

    @Test
    fun `blank title is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BookMetadataEditor.validate(book(), BookMetadata(" ", null, null, null))
        }
    }

    @Test
    fun `room save reads latest row and preserves its file fields`() = runBlocking {
        val latest = book().copy(
            name = "Latest",
            md5 = "latest-md5",
            path = "/books/latest.epub",
            size = 123L,
            page = 45L,
        )
        val dao = FakeBookDao(latest)

        saveBookMetadata(dao, latest.key, BookMetadata("Edited", " Author ", " Description ", " Publisher "))

        val saved = dao.saved
        assertEquals(latest.key, saved?.key)
        assertEquals("Edited", saved?.name)
        assertEquals("Author", saved?.author)
        assertEquals("Description", saved?.description)
        assertEquals("Publisher", saved?.publisher)
        assertEquals(latest.md5, saved?.md5)
        assertEquals(latest.path, saved?.path)
        assertEquals(latest.size, saved?.size)
        assertEquals(latest.page, saved?.page)
    }

    private fun book() = BookEntity(key = "book-1")

    private class FakeBookDao(private val book: BookEntity) : BookDao {
        var saved: BookEntity? = null

        override suspend fun upsert(book: BookEntity) {
            saved = book
        }

        override suspend fun upsertAll(books: List<BookEntity>) = Unit
        override fun observeAll(): Flow<List<BookEntity>> = emptyFlow()
        override suspend fun getByKey(key: String): BookEntity = book
        override suspend fun getByMd5(md5: String): List<BookEntity> = emptyList()
        override suspend fun deleteByKey(key: String) = Unit
        override suspend fun count(): Long = 1L
    }
}
