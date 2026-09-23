package com.koodoreader.reader.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koodoreader.core.data.KoodoDatabase
import com.koodoreader.core.data.KoodoDatabaseProvider
import com.koodoreader.core.data.entity.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** Counts of user annotations attached to one book (notes/bookmarks/words). */
data class AnnotationCounts(val notes: Long, val bookmarks: Long, val words: Long)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val db: KoodoDatabase = KoodoDatabaseProvider.get(app)

    val books: StateFlow<List<BookEntity>> =
        db.bookDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun book(bookKey: String): Flow<BookEntity?> =
        flow { emit(db.bookDao().getByKey(bookKey)) }.flowOn(Dispatchers.IO)

    fun annotationCounts(bookKey: String): Flow<AnnotationCounts> =
        combine(
            db.noteDao().observeCountForBook(bookKey),
            db.bookmarkDao().observeCountForBook(bookKey),
            db.wordDao().observeCountForBook(bookKey),
        ) { notes, bookmarks, words -> AnnotationCounts(notes, bookmarks, words) }
            .flowOn(Dispatchers.IO)
}
