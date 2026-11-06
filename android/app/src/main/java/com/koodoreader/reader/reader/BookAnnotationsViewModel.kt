package com.koodoreader.reader.reader

import com.koodoreader.core.data.dao.BookmarkDao
import com.koodoreader.core.data.dao.NoteDao
import com.koodoreader.core.data.entity.BookmarkEntity
import com.koodoreader.core.data.entity.NoteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Per-book annotation CRUD over the existing Room DAOs (no parallel store).
 * Edits copy the entity so the CFI/range link and creation payload survive.
 */
class BookAnnotationsViewModel(
    private val noteDao: NoteDao,
    private val bookmarkDao: BookmarkDao,
    private val bookKey: String,
    scope: CoroutineScope,
) {
    val notes: StateFlow<List<NoteEntity>> = noteDao.observeForBook(bookKey)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val bookmarks: StateFlow<List<BookmarkEntity>> = bookmarkDao.observeForBook(bookKey)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun editNote(noteKey: String, content: String): NoteEntity {
        val existing = noteDao.getByKey(noteKey)
            ?: throw NoSuchElementException("note $noteKey not found")
        val updated = existing.copy(notes = content)
        noteDao.upsert(updated)
        return updated
    }

    suspend fun changeHighlightColor(noteKey: String, color: Long): NoteEntity {
        val existing = noteDao.getByKey(noteKey)
            ?: throw NoSuchElementException("note $noteKey not found")
        val updated = existing.copy(color = color)
        noteDao.upsert(updated)
        return updated
    }

    /** Standalone note, or linked to a highlight position when [cfi] is given. */
    suspend fun addNote(
        content: String,
        excerpt: String = "",
        chapter: String? = null,
        cfi: String? = null,
        color: Long? = null,
    ): NoteEntity {
        val entity = NoteEntity(
            key = System.currentTimeMillis().toString(),
            bookKey = bookKey,
            chapter = chapter,
            text = excerpt,
            cfi = cfi,
            notes = content,
            color = color,
        )
        noteDao.upsert(entity)
        return entity
    }

    suspend fun deleteNote(noteKey: String) = noteDao.deleteByKey(noteKey)

    suspend fun deleteBookmark(key: String) = bookmarkDao.deleteByKey(key)
}
