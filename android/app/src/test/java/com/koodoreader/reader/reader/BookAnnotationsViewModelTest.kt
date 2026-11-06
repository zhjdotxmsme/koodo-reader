package com.koodoreader.reader.reader

import com.koodoreader.core.data.dao.BookmarkDao
import com.koodoreader.core.data.dao.NoteDao
import com.koodoreader.core.data.entity.BookmarkEntity
import com.koodoreader.core.data.entity.NoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BookAnnotationsViewModelTest {

    private class FakeNoteDao : NoteDao {
        val map = LinkedHashMap<String, NoteEntity>()
        private val flow = MutableStateFlow<List<NoteEntity>>(emptyList())

        private fun publish() {
            flow.value = map.values.toList()
        }

        override suspend fun upsert(note: NoteEntity) {
            map[note.key] = note
            publish()
        }

        override suspend fun upsertAll(notes: List<NoteEntity>) = notes.forEach { upsert(it) }
        override fun observeForBook(bookKey: String): Flow<List<NoteEntity>> = flow
        override fun observeAll(): Flow<List<NoteEntity>> = flow
        override suspend fun getByKey(key: String): NoteEntity? = map[key]

        override suspend fun deleteByKey(key: String) {
            map.remove(key)
            publish()
        }

        override suspend fun deleteForBook(bookKey: String) {
            map.keys.removeAll { map[it]?.bookKey == bookKey }
            publish()
        }

        override suspend fun count(): Long = map.size.toLong()
        override fun observeCountForBook(bookKey: String): Flow<Long> = flow.map { it.size.toLong() }
    }

    private class FakeBookmarkDao : BookmarkDao {
        val map = LinkedHashMap<String, BookmarkEntity>()
        private val flow = MutableStateFlow<List<BookmarkEntity>>(emptyList())

        override suspend fun upsert(bookmark: BookmarkEntity) {
            map[bookmark.key] = bookmark
            flow.value = map.values.toList()
        }

        override suspend fun upsertAll(bookmarks: List<BookmarkEntity>) = bookmarks.forEach { upsert(it) }
        override fun observeForBook(bookKey: String): Flow<List<BookmarkEntity>> = flow
        override fun observeAll(): Flow<List<BookmarkEntity>> = flow

        override suspend fun deleteByKey(key: String) {
            map.remove(key)
            flow.value = map.values.toList()
        }

        override suspend fun count(): Long = map.size.toLong()
        override fun observeCountForBook(bookKey: String): Flow<Long> = flow.map { it.size.toLong() }
    }

    @Test
    fun `note edit preserves highlight link and created time`() = runTest {
        val notes = FakeNoteDao()
        notes.upsert(
            NoteEntity(
                key = "n",
                bookKey = "b",
                cfi = "cfi-1",
                date = "created-payload",
                notes = "old",
                text = "原文",
            ),
        )
        val vm = BookAnnotationsViewModel(notes, FakeBookmarkDao(), "b", backgroundScope)

        val updated = vm.editNote("n", "updated")

        assertEquals("cfi-1", updated.cfi)
        assertEquals("created-payload", updated.date)
        assertEquals("updated", updated.notes)
        assertEquals("updated", notes.getByKey("n")?.notes)
    }

    @Test
    fun `recolor keeps text and position`() = runTest {
        val notes = FakeNoteDao()
        notes.upsert(NoteEntity(key = "h", bookKey = "b", cfi = "cfi-9", text = "原文", color = 1L))
        val vm = BookAnnotationsViewModel(notes, FakeBookmarkDao(), "b", backgroundScope)

        val updated = vm.changeHighlightColor("h", 0xFF00FF00)

        assertEquals(0xFF00FF00, updated.color)
        assertEquals("cfi-9", updated.cfi)
        assertEquals("原文", updated.text)
    }

    @Test
    fun `standalone note is stored for the book`() = runTest {
        val notes = FakeNoteDao()
        val vm = BookAnnotationsViewModel(notes, FakeBookmarkDao(), "b", backgroundScope)

        val added = vm.addNote(content = "感想", excerpt = "")

        assertEquals("b", added.bookKey)
        assertNull(added.cfi)
        assertEquals("感想", notes.getByKey(added.key)?.notes)
    }

    @Test
    fun `delete removes notes and bookmarks`() = runTest {
        val notes = FakeNoteDao()
        val bookmarks = FakeBookmarkDao()
        notes.upsert(NoteEntity(key = "n", bookKey = "b"))
        bookmarks.upsert(BookmarkEntity(key = "bm", bookKey = "b"))
        val vm = BookAnnotationsViewModel(notes, FakeBookmarkDao(), "b", backgroundScope)

        vm.deleteNote("n")
        assertNull(notes.getByKey("n"))

        val vmBookmarks = BookAnnotationsViewModel(FakeNoteDao(), bookmarks, "b", backgroundScope)
        vmBookmarks.deleteBookmark("bm")
        assertNull(bookmarks.map["bm"])
    }
}
