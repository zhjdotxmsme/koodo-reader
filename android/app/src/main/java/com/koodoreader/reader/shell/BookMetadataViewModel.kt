package com.koodoreader.reader.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koodoreader.core.data.KoodoDatabaseProvider
import com.koodoreader.core.data.dao.BookDao
import com.koodoreader.core.data.entity.BookEntity
import com.koodoreader.core.importer.BookMetadata
import com.koodoreader.reader.metadata.CoverDownload
import com.koodoreader.reader.metadata.FetchedBookMetadata
import com.koodoreader.reader.metadata.PublicMetadataSearch
import com.koodoreader.reader.translate.AppHttpTransport
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlinx.coroutines.withContext

sealed interface BookMetadataSearchUiState {
    data object Idle : BookMetadataSearchUiState
    data object Loading : BookMetadataSearchUiState
    data class Results(val items: List<FetchedBookMetadata>) : BookMetadataSearchUiState
    data object Empty : BookMetadataSearchUiState
    data object Failed : BookMetadataSearchUiState
}

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

    private val _searchState = MutableStateFlow<BookMetadataSearchUiState>(BookMetadataSearchUiState.Idle)
    val searchState: StateFlow<BookMetadataSearchUiState> = _searchState

    fun save(bookKey: String, draft: BookMetadata, coverUrl: String? = null) {
        if (_state.value is BookMetadataUiState.Saving) return
        _state.value = BookMetadataUiState.Saving
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val booksDir = File(app.filesDir, "books")
                    saveBookMetadata(bookDao, bookKey, draft, booksDir)
                    val url = coverUrl?.trim().orEmpty()
                    if (url.isNotEmpty()) {
                        val cover = CoverDownload.fetchBookCover(url)
                        if (cover != null) {
                            CoverStore(app).save(bookKey, cover)
                        }
                    }
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
        _searchState.value = BookMetadataSearchUiState.Idle
    }

    /** 多公开源链式检索；有 ISBN 时优先按 ISBN 查。 */
    fun searchOnline(name: String, author: String, isbn: String) {
        if (_searchState.value is BookMetadataSearchUiState.Loading) return
        _searchState.value = BookMetadataSearchUiState.Loading
        viewModelScope.launch {
            val transport = AppHttpTransport()
            val cacheDir = File(getApplication<Application>().cacheDir, "book-metadata")
            val result = withContext(Dispatchers.IO) {
                runCatching { PublicMetadataSearch.search(transport, name, author, isbn, cacheDir) }
            }
            _searchState.value = result.fold(
                onSuccess = { items ->
                    if (items.isEmpty()) BookMetadataSearchUiState.Empty
                    else BookMetadataSearchUiState.Results(items)
                },
                onFailure = { BookMetadataSearchUiState.Failed },
            )
        }
    }

    fun clearSearch() {
        _searchState.value = BookMetadataSearchUiState.Idle
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