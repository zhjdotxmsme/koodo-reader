package com.koodoreader.reader.shell

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koodoreader.core.data.KoodoDatabase
import com.koodoreader.core.data.KoodoDatabaseProvider
import com.koodoreader.core.data.entity.BookEntity
import com.koodoreader.core.importer.BookRecord
import com.koodoreader.core.importer.BookRules
import com.koodoreader.core.importer.BookSource
import com.koodoreader.core.importer.ImportPipeline
import com.koodoreader.reader.FolderEnumerator
import java.io.File
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

/** Counts of user annotations attached to one book (notes/bookmarks/words). */
data class AnnotationCounts(val notes: Long, val bookmarks: Long, val words: Long)

/** Progress/result of a SAF import (folder or multi-file; P1 acceptance case). */
sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val done: Int, val total: Int) : ImportState
    data class Finished(
        val imported: Int,
        val duplicates: Int,
        val failed: Int,
        /** Non-null when the import died before processing (setup/enumeration). */
        val error: String? = null,
    ) : ImportState
}

/** Candidates produced by a SAF picker plus files dropped before processing. */
private class PendingBatch(val sources: List<BookSource>, val skipped: Int)

/** Shelf UI state — the native mirror of the desktop config JSONs. */
data class ShelfUiState(
    val sort: SortSpec = SortSpec.DEFAULT,
    val viewGrid: Boolean = true,
    val favorites: Set<String> = emptySet(),
    val trashed: Set<String> = emptySet(),
    val manualOrder: List<String> = emptyList(),
    val favoritesOnly: Boolean = false,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val db: KoodoDatabase = KoodoDatabaseProvider.get(app)
    private val prefs = LibraryPrefs(app)

    val books: StateFlow<List<BookEntity>> =
        db.bookDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ------------------------------------------------------------------
    // Shelf (sort / view / favorites / trash / manual order) — persisted in
    // LibraryPrefs, semantics in LibraryLogic (desktop parity, bookDrag.ts).
    // ------------------------------------------------------------------

    private val _shelf = MutableStateFlow(
        ShelfUiState(
            sort = SortSpec(prefs.sortField, prefs.sortAscending),
            viewGrid = prefs.viewGrid,
            favorites = prefs.favorites(),
            trashed = prefs.trashed(),
            manualOrder = prefs.manualOrder(),
        ),
    )
    val shelf: StateFlow<ShelfUiState> = _shelf

    /** Books the shelf shows (trashed excluded, sorted, optional favorites filter). */
    val libraryBooks: StateFlow<List<BookEntity>> =
        combine(books, _shelf) { list, s ->
            val byKey = list.associateBy { it.key }
            val sortable = list.map { SortableBook(it.key, it.name, it.author, it.format) }
            val visible = LibraryLogic.visibleKeys(sortable, s.trashed)
            var keys = LibraryLogic.sortedKeys(visible, s.sort, s.manualOrder)
            if (s.favoritesOnly) keys = keys.filter { it in s.favorites }
            keys.mapNotNull { byKey[it] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 回收站视图：trashed 集合内的书（按加入时间倒序近似 = key 倒序）。 */
    val trashedBooks: StateFlow<List<BookEntity>> =
        combine(books, _shelf) { list, s ->
            list.filter { it.key in s.trashed }.sortedByDescending { it.key }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSortField(field: SortField) {
        val cur = _shelf.value.sort
        if (cur.field == field) {
            toggleSortDirection()
            return
        }
        updateShelf { it.copy(sort = SortSpec(field, ascending = field != SortField.ADDED)) }
    }

    fun toggleSortDirection() = updateShelf {
        it.copy(sort = it.sort.copy(ascending = !it.sort.ascending))
    }

    fun setViewGrid(grid: Boolean) = updateShelf { it.copy(viewGrid = grid) }

    fun setFavoritesOnly(only: Boolean) = updateShelf { it.copy(favoritesOnly = only) }

    /** 收藏/取消收藏；收藏同时把书从回收站恢复（桌面 parity）。 */
    fun toggleFavorite(key: String) = updateShelf {
        val t = if (key in it.favorites) {
            ShelfTransition(it.trashed, it.favorites - key, 1)
        } else {
            LibraryLogic.favorite(listOf(key), it.trashed, it.favorites)
        }
        prefs.setFavorites(t.favorites)
        prefs.setTrashed(t.trashed)
        it.copy(favorites = t.favorites, trashed = t.trashed)
    }

    fun moveToTrash(key: String) = updateShelf {
        val t = LibraryLogic.softDelete(listOf(key), it.trashed, it.favorites)
        prefs.setFavorites(t.favorites)
        prefs.setTrashed(t.trashed)
        it.copy(favorites = t.favorites, trashed = t.trashed)
    }

    fun restoreFromTrash(key: String) = updateShelf {
        val t = LibraryLogic.restore(listOf(key), it.trashed)
        prefs.setTrashed(t.trashed)
        it.copy(trashed = t.trashed)
    }

    /** 彻底删除：仅允许回收站内的书；连带书籍文件与封面文件。 */
    fun purge(keys: List<String>) {
        val targets = LibraryLogic.purgeTargets(keys, _shelf.value.trashed)
        if (targets.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val booksDir = File(app.filesDir, "books")
                val coverStore = CoverStore(app)
                for (k in targets) {
                    db.bookDao().deleteByKey(k)
                    booksDir.listFiles()?.forEach { f ->
                        if (f.name.startsWith("$k.")) f.delete()
                    }
                    runCatching { coverStore.fileFor(k)?.delete() }
                }
            }
            updateShelf {
                val t = LibraryLogic.restore(targets, it.trashed)
                prefs.setTrashed(t.trashed)
                it.copy(trashed = t.trashed)
            }
        }
    }

    /** 拖拽落点：更新手动顺序并切到 MANUAL 排序（拖拽即启用手动模式）。 */
    fun moveManual(fromKey: String, targetKey: String) {
        if (fromKey == targetKey) return
        updateShelf { s ->
            val visibleKeys = libraryBooks.value.map { it.key }
            // seed the manual order with the currently visible shelf once
            val base = s.manualOrder.ifEmpty { visibleKeys }
            val missing = visibleKeys.filter { it !in base }
            val order = LibraryLogic.moveInOrder(base + missing, fromKey, targetKey)
            prefs.setManualOrder(order)
            s.copy(manualOrder = order, sort = SortSpec(SortField.MANUAL, ascending = true))
        }
    }

    private fun updateShelf(block: (ShelfUiState) -> ShelfUiState) {
        val next = block(_shelf.value)
        _shelf.value = next
        prefs.sortField = next.sort.field
        prefs.sortAscending = next.sort.ascending
        prefs.viewGrid = next.viewGrid
    }


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
    // SAF import (P1): enumerate (folder tree / multi-file picker) ->
    // ImportPipeline (copy -> streaming-MD5 dedupe -> EPUB/CBZ enrichment)
    // -> CoverStore cover files + chunked Room upserts.
    // BookRules (Kotlin single source of truth) + the pipeline's streaming
    // IO keep the 1000-book acceptance case within memory limits.
    // ------------------------------------------------------------------

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState

    /** Import every book file found under this SAF tree (depth-capped). */
    fun importFolder(treeUri: Uri) {
        startImport { resolver ->
            persistReadPermission(resolver, treeUri)
            PendingBatch(
                FolderEnumerator
                    .enumerate(resolver, treeUri, BookRules.FOLDER_DEPTH, BookRules.MAX_FILES)
                    .take(BookRules.MAX_FILES)
                    .map { file ->
                        BookSource(
                            name = file.name,
                            openStream = {
                                resolver.openInputStream(file.uri)
                                    ?: throw IllegalStateException("stream missing for ${file.name}")
                            },
                        )
                    },
                skipped = 0,
            )
        }
    }

    /** Import the individually picked book files (SAF multi-select). */
    fun importFiles(fileUris: List<Uri>) {
        startImport { resolver ->
            fileUris.forEach { persistReadPermission(resolver, it) }
            var skipped = 0
            val sources = fileUris
                .mapNotNull { uri ->
                    val name = displayName(resolver, uri)
                    if (name == null) {
                        skipped++ // reported in the result, never silently dropped
                        return@mapNotNull null
                    }
                    BookSource(
                        name = name,
                        openStream = {
                            resolver.openInputStream(uri)
                                ?: throw IllegalStateException("stream missing for $name")
                        },
                    )
                }
                .take(BookRules.MAX_FILES)
            PendingBatch(sources, skipped)
        }
    }

    private fun startImport(
        pendingFactory: (ContentResolver) -> PendingBatch,
    ) {
        if (_importState.value is ImportState.Running) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            _importState.value = ImportState.Running(0, 0)
            withContext(Dispatchers.IO) {
                val batch = runCatching { pendingFactory(app.contentResolver) }.getOrElse {
                    // Distinguishable from a genuine "imported 0" (review fix).
                    _importState.value = ImportState.Finished(
                        imported = 0, duplicates = 0, failed = 0,
                        error = it.message ?: "cannot read the picked location",
                    )
                    return@withContext
                }
                val pending = batch.sources
                if (pending.isEmpty()) {
                    _importState.value = ImportState.Finished(0, 0, batch.skipped)
                    return@withContext
                }
                _importState.value = ImportState.Running(0, pending.size)

                val dao = db.bookDao()
                val booksDir = File(app.filesDir, "books")
                val coverStore = CoverStore(app)
                val progressHook: (Int, Int) -> Unit = { done, total ->
                    _importState.value = ImportState.Running(done, total)
                }
                val result = ImportPipeline(
                    extraCoverExtractors = mapOf(
                        // PDF page-1 render needs the framework PdfRenderer —
                        // not available to the pure-JVM importer module.
                        "pdf" to { file -> com.koodoreader.reader.PdfCoverExtractor.extract(file) },
                    ),
                ).process(
                    pending = pending,
                    booksOut = booksDir,
                    existingMd5 = { md5 -> dao.getByMd5(md5).isNotEmpty() },
                    onProgress = progressHook,
                )

                // Cover images -> desktop-parity cover/ directory.
                var coverFailures = 0
                for ((key, cover) in result.covers) {
                    val ok = runCatching { coverStore.save(key, cover) }.isSuccess
                    if (!ok) coverFailures++
                }

                // Chunked upserts keep per-statement memory bounded.
                var upserted = 0
                result.records.chunked(BATCH_SIZE).forEach { chunk ->
                    dao.upsertAll(chunk.map { it.toEntity() })
                    upserted += chunk.size
                }

                _importState.value = ImportState.Finished(
                    imported = upserted,
                    duplicates = result.duplicates,
                    failed = result.failures.size + result.unsupported + coverFailures + batch.skipped,
                )
            }
        }
    }

    private fun persistReadPermission(resolver: ContentResolver, uri: Uri) {
        // Re-imports must survive process restarts (WebView-track parity).
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            }
        }.getOrNull()

    private fun BookRecord.toEntity(): BookEntity = BookEntity(
        key = key,
        name = name,
        author = author,
        description = description,
        md5 = md5,
        cover = cover,
        format = format,
        publisher = publisher,
        size = size,
        page = page,
        path = path,
        charset = charset,
    )

    private companion object {
        const val BATCH_SIZE = 50
    }
}
