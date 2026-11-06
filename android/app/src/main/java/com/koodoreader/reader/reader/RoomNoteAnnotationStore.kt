package com.koodoreader.reader.reader

import com.koodoreader.core.data.dao.NoteDao
import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.engine.layout.reader.ChapterLoader
import com.koodoreader.engine.layout.reader.TextLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Persists text anchors to Room `notes` (desktop-compatible CFI column).
 */
class RoomNoteAnnotationStore(
    private val bookKey: String,
    private val loader: ChapterLoader,
    private val noteDao: NoteDao,
    private val scope: CoroutineScope,
) : AnnotationStore {

    override fun saveHighlight(bookKey: String, location: TextLocation, color: Long) {
        if (bookKey != this.bookKey) return
        persist(location, excerpt = ChapterLoaderProgress.excerptAt(loader, location), notes = "", color = color)
    }

    override fun saveNote(bookKey: String, location: TextLocation, text: String, color: Long) {
        if (bookKey != this.bookKey) return
        persist(
            location,
            excerpt = ChapterLoaderProgress.excerptAt(loader, location),
            notes = text,
            color = color,
        )
    }

    override fun addBookmark(bookKey: String, location: TextLocation, label: String) {
        if (bookKey != this.bookKey) return
        persist(location, excerpt = label, notes = "", color = null)
    }

    private fun persist(
        location: TextLocation,
        excerpt: String,
        notes: String,
        color: Long?,
    ) {
        val cfi = ChapterLoaderProgress.cfiForLocation(loader, location) ?: return
        val chapterIdx = loader.descriptors().indexOfFirst { it.id == location.chapterId }.coerceAtLeast(0)
        val chapterLabel = ChapterLoaderProgress.chapterBarLabel(loader, location.chapterId)
            .substringAfter("· ", "")
            .ifBlank { location.chapterId }
        val entity = NoteEntity(
            key = System.currentTimeMillis().toString(),
            bookKey = bookKey,
            chapter = chapterLabel,
            chapterIndex = chapterIdx.toLong(),
            text = excerpt,
            cfi = cfi,
            notes = notes,
            percentage = ChapterLoaderProgress.progressFraction(loader, location.chapterId).toString(),
            color = color,
        )
        scope.launch { noteDao.upsert(entity) }
    }
}
