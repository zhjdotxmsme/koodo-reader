package com.koodoreader.reader.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koodoreader.core.data.KoodoDatabaseProvider
import com.koodoreader.core.data.dao.BookDao
import com.koodoreader.core.data.entity.BookEntity
import com.koodoreader.core.importer.BookMetadata
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlinx.coroutines.withContext

sealed interface BookMetadataUiState {
    data object Idle : BookMetadataUiState
    data object Saving : BookMetadataUiState
    data object Saved : BookMetadataUiState
    data class Error(val message: String) : BookMetadataUiState
}

class BookMetadataViewModel(app: Application) : AndroidViewModel(app) {
    private val bookDao = KoodoDatabaseProvider.get(app).bookDao()
    private val _state = MutableStateFlow<BookMetadataUiState>(BookMetadataUiState.Idle)
    val state: StateFlow<BookMetadataUiState> = _state

    fun save(bookKey: String, draft: BookMetadata) {
        if (_state.value is BookMetadataUiState.Saving) return
        _state.value = BookMetadataUiState.Saving
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val booksDir = File(getApplication<Application>().filesDir, "books")
                    saveBookMetadata(bookDao, bookKey, draft, booksDir)
                }
                _state.value = BookMetadataUiState.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = BookMetadataUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun reset() {
        _state.value = BookMetadataUiState.Idle
    }
}

internal suspend fun saveBookMetadata(
    bookDao: BookDao,
    bookKey: String,
    draft: BookMetadata,
    booksDir: File? = null,
): BookEntity {
    val latest = requireNotNull(bookDao.getByKey(bookKey)) { "Book not found" }
    val edited = BookMetadataEditor.validate(latest, draft)
    val renamed = if (booksDir != null && edited.name != latest.name) {
        BookFileMover.renameFile(edited, edited.name.orEmpty(), booksDir)
    } else {
        edited
    }
    try {
        bookDao.upsert(renamed)
    } catch (t: Throwable) {
        val newPath = renamed.path
        val oldPath = latest.path
        if (newPath != null && oldPath != null && newPath != oldPath) {
            File(newPath).let { dest ->
                val source = File(oldPath)
                if (dest.isFile && !source.exists() && !dest.renameTo(source)) {
                    t.addSuppressed(IOException("Cannot roll back rename to $oldPath"))
                }
            }
        }
        throw t
    }
    return renamed
}
