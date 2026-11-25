package com.koodoreader.reader.reader

import com.koodoreader.core.data.dao.NoteDao
import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.engine.layout.reader.ChapterLoader
import com.koodoreader.engine.layout.reader.TextLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

    /**
     * Whether the selection is an exact match for an existing highlight of
     * this book.
     *
     * Highlights are stored as range CFIs (the selected span); bookmarks keep
     * the legacy point CFI. [NativeHighlightMatcher.matches] accepts a
     * selection whose range CFI equals the stored one, or whose start point
     * equals a stored point — legacy rows stay deletable without migrating
     * data. Partial overlap and other-chapter positions deliberately do not
     * match, so deleting only ever removes what is selected.
     */
    override fun isHighlighted(bookKey: String, location: TextLocation): Boolean {
        if (bookKey != this.bookKey) return false
        val selectionCfi = ChapterLoaderProgress.cfiRangeForLocation(loader, location) ?: return false
        val highlights = runBlocking {
            runCatching { noteDao.getHighlightsForBook(bookKey) }.getOrNull()
        } ?: return false
        return highlights.any { entity ->
            entity.cfi?.let { stored -> NativeHighlightMatcher.matches(selectionCfi, stored) } == true
        }
    }

    private fun persist(
        location: TextLocation,
        excerpt: String,
        notes: String,
        color: Long?,
    ) {
        val cfi = if (color == null) {
            ChapterLoaderProgress.cfiForLocation(loader, location)
        } else {
            ChapterLoaderProgress.cfiRangeForLocation(loader, location)
        } ?: return
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
