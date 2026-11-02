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
                    saveBookMetadata(bookDao, bookKey, draft)
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
): BookEntity {
    val latest = requireNotNull(bookDao.getByKey(bookKey)) { "Book not found" }
    val edited = BookMetadataEditor.validate(latest, draft)
    bookDao.upsert(edited)
    return edited
}
