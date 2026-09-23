package com.koodoreader.reader.shell

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koodoreader.core.data.KoodoDatabase
import com.koodoreader.core.data.KoodoDatabaseProvider
import com.koodoreader.core.data.entity.BookEntity
import com.koodoreader.core.importer.BookRules
import com.koodoreader.core.importer.RawFolderEntry
import com.koodoreader.core.importer.filterBooks
import com.koodoreader.reader.FolderEnumerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Counts of user annotations attached to one book (notes/bookmarks/words). */
data class AnnotationCounts(val notes: Long, val bookmarks: Long, val words: Long)

/** Progress/result of one SAF folder import (P1 acceptance: 1000 books, no OOM). */
sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val done: Int, val total: Int) : ImportState
    data class Finished(val imported: Int, val duplicates: Int, val failed: Int) : ImportState
}

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

    // ------------------------------------------------------------------
    // SAF folder import (P1): enumerate (shared FolderEnumerator) ->
    // filter (BookRules, Kotlin single source of truth) -> streaming-MD5
    // dedupe against the desktop `md5` column -> batched Room upsert.
    // Streaming MD5 + batched inserts keep the 1000-book acceptance case
    // well inside memory limits; rows use desktop-compatible key/name/format.
    // ------------------------------------------------------------------

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState

    fun importFolder(treeUri: Uri) {
        if (_importState.value is ImportState.Running) return
        viewModelScope.launch {
            _importState.value = ImportState.Running(0, 0)
            val totals = withContext(Dispatchers.IO) { importFromTree(treeUri) }
            _importState.value = ImportState.Finished(
                imported = totals.imported,
                duplicates = totals.duplicates,
                failed = totals.failed,
            )
        }
    }

    private data class ImportTotals(val imported: Int, val duplicates: Int, val failed: Int)

    private suspend fun importFromTree(treeUri: Uri): ImportTotals {
        val resolver = getApplication<Application>().contentResolver
        // Persist the read grant so re-imports survive process restarts
        // (same behaviour as the WebView track's folder picker).
        runCatching {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val raw = FolderEnumerator
            .enumerate(resolver, treeUri, BookRules.FOLDER_DEPTH, BookRules.MAX_FILES)
            .map { RawFolderEntry(it.name, it.uri.toString(), it.size, it.mime) }
        val entries = filterBooks(raw)
        val dao = db.bookDao()
        var imported = 0
        var duplicates = 0
        var failed = 0
        val batch = mutableListOf<BookEntity>()

        entries.forEachIndexed { index, entry ->
            try {
                val md5 = md5Of(resolver, Uri.parse(entry.uri))
                when {
                    // Desktop dedupe contract: same content md5 -> skip row.
                    md5 == null -> failed++
                    dao.getByMd5(md5).isNotEmpty() -> duplicates++
                    else -> {
                        // Unique key (desktop formula, retried on collision).
                        var key = BookRules.generateBookKey()
                        while (dao.getByKey(key) != null) key = BookRules.generateBookKey()
                        batch += BookEntity(
                            key = key,
                            name = BookRules.bookNameFromFile(entry.name),
                            author = null, // metadata extraction: reader phases
                            description = null,
                            md5 = md5,
                            cover = null, // cover generation: cover row (P1)
                            format = BookRules.formatFromFile(entry.name),
                            publisher = null,
                            size = entry.size,
                            page = null,
                            path = entry.uri, // content:// doc URI, re-openable here
                            charset = null,
                        )
                        if (batch.size >= BATCH_SIZE) {
                            dao.upsertAll(batch.toList())
                            imported += batch.size
                            batch.clear()
                        }
                    }
                }
            } catch (e: Exception) {
                failed++
            }
            if ((index + 1) % PROGRESS_STEP == 0 || index == entries.lastIndex) {
                _importState.value = ImportState.Running(index + 1, entries.size)
            }
        }
        if (batch.isNotEmpty()) {
            dao.upsertAll(batch.toList())
            imported += batch.size
        }
        return ImportTotals(imported, duplicates, failed)
    }

    /** Streaming MD5 (64 KiB buffer) — never loads a whole book into memory. */
    private fun md5Of(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            val digest = MessageDigest.getInstance("MD5")
            val stream = resolver.openInputStream(uri) ?: return null
            stream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()

    private companion object {
        const val BATCH_SIZE = 50
        const val PROGRESS_STEP = 10
    }
}
