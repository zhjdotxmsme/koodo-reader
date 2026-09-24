package com.koodoreader.reader.shell

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koodoreader.core.data.KoodoDatabase
import com.koodoreader.core.data.KoodoDatabaseProvider
import com.koodoreader.core.dbio.DataExport
import com.koodoreader.core.dbio.DataImport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI state for the backup/restore screen (single message channel, P1 shell). */
sealed interface BackupUiState {
    data object Idle : BackupUiState
    data class Running(val label: String) : BackupUiState
    data class Finished(val message: String) : BackupUiState
    data class Errored(val message: String) : BackupUiState
}

class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val db = KoodoDatabaseProvider.get(app)
    private val coverDir = File(app.filesDir, "cover")
    private val booksDir = File(app.filesDir, "books")
    private val fontsDir = File(app.filesDir, "fonts")
    private val backupDir = File(app.cacheDir, "backup")
    private val exportDir = File(app.cacheDir, "data-export")

    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = _state

    /** Import desktop → native from a SAF-picked zip (or unpacked tree uri). */
    fun importBackup(uri: Uri) {
        if (_state.value is BackupUiState.Running) return
        viewModelScope.launch {
            _state.value = BackupUiState.Running("Reading backup bundle…")
            val workDir = File(getApplication<Application>().cacheDir, "import-backup")
            val local = runCatching {
                withContext(Dispatchers.IO) {
                    workDir.deleteRecursively()
                    workDir.mkdirs()
                    val src = File(workDir, "bundle.zip")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        src.outputStream().use { input.copyTo(it) }
                    } ?: run {
                        workDir.deleteRecursively()
                        throw IllegalStateException("cannot open $uri")
                    }
                    src
                }
            }
            if (local.isFailure) {
                _state.value = BackupUiState.Errored(local.exceptionOrNull()?.message ?: "open failed")
                return@launch
            }
            val file = local.getOrThrow()
            val report = runCatching {
                DesktopBridge.importBackup(file, db, booksDir, coverDir, fontsDir)
            }
            withContext(Dispatchers.IO) { file.parentFile?.deleteRecursively() }
            if (report.isFailure) {
                _state.value = BackupUiState.Errored(report.exceptionOrNull()?.message ?: "import failed")
                return@launch
            }
            _state.value = BackupUiState.Finished(formatImportReport(report.getOrThrow()))
        }
    }

    /** Native → desktop: writes a desktop-readable zip under cache/backup. */
    fun exportBackup() {
        if (_state.value is BackupUiState.Running) return
        viewModelScope.launch {
            _state.value = BackupUiState.Running("Building desktop backup…")
            val report = runCatching {
                DesktopBridge.exportBackup(
                    outDir = backupDir,
                    db = db,
                    coverDir = coverDir,
                    booksDir = booksDir, // P7: bundle books/ entries under <filesDir>/books
                    timestamp = System.currentTimeMillis(),
                )
            }
            if (report.isFailure) {
                _state.value = BackupUiState.Errored(report.exceptionOrNull()?.message ?: "export failed")
            } else {
                val r = report.getOrThrow()
                _state.value = BackupUiState.Finished(
                    buildString {
                        append("Wrote ").append(r.file.name)
                            .append(" (").append(r.file.absolutePath).append(")\n")
                        r.tables.entries.forEach { (t, n) -> append(t).append(": ").append(n).append("  ") }
                        append("\ncovers: ").append(r.covers)
                        append("  books: ").append(r.books)
                    },
                )
            }
        }
    }

    // ----------------------------------------------- per-data export/import

    /**
     * Native → desktop CSV/JSON for notes/highlights/dictionary history.
     * Mirrors the desktop `export.ts` per-book zip layout when the data
     * spans more than one book; single-book output is one bare file.
     */
    fun exportData(type: BackupDataExportType, format: BackupDataExportFormat) {
        if (_state.value is BackupUiState.Running) return
        viewModelScope.launch {
            _state.value = BackupUiState.Running("Exporting ${type.wire}…")
            val report = runCatching {
                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    when (type) {
                        BackupDataExportType.NOTES,
                        BackupDataExportType.HIGHLIGHTS -> {
                            val all = db.noteDao().observeAll().first()
                            val books = db.bookDao().observeAll().first().associate { it.key to it }
                            val wantType = if (type == BackupDataExportType.NOTES) "note" else "highlight"
                            val rows = all.filter { !it.notes.isNullOrEmpty() == (type == BackupDataExportType.NOTES) }
                                .map { n -> DesktopBridge.noteRowForExport(n, books) }
                            require(rows.isNotEmpty()) { "No ${type.wire} to export" }
                            DataExport.writeExport(
                                outDir = exportDir,
                                records = rows,
                                format = format.toCore(),
                                type = if (type == BackupDataExportType.NOTES) "Note" else "Highlight",
                                now = now,
                            )
                        }
                        BackupDataExportType.DICTIONARY -> {
                            val words = db.wordDao().observeAll().first()
                            val books = db.bookDao().observeAll().first().associate { it.key to it }
                            val rows = words.map { w -> DesktopBridge.wordRowForExport(w, books) }
                            require(rows.isNotEmpty()) { "No dictionary history to export" }
                            val name = "KoodoReader-Dictionary-History-${DataExport.fileTimestamp(now)}.${format.toCore().extension}"
                            val out = File(exportDir, name)
                            exportDir.mkdirs()
                            out.writeText(
                                DataExport.encodeWords(rows, format.toCore()),
                                Charsets.UTF_8,
                            )
                            out
                        }
                    }
                }
            }
            if (report.isFailure) {
                _state.value = BackupUiState.Errored(report.exceptionOrNull()?.message ?: "export failed")
            } else {
                _state.value = BackupUiState.Finished(
                    "Wrote ${report.getOrThrow().absolutePath}",
                )
            }
        }
    }

    /** Native ← desktop CSV/JSON: same flow as desktop `importNotesData`. */
    fun importData(uri: Uri) {
        if (_state.value is BackupUiState.Running) return
        viewModelScope.launch {
            _state.value = BackupUiState.Running("Reading data file…")
            val workDir = File(getApplication<Application>().cacheDir, "import-data")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    workDir.deleteRecursively()
                    workDir.mkdirs()
                    val src = File(workDir, "data.csv")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        src.outputStream().use { input.copyTo(it) }
                    } ?: run {
                        workDir.deleteRecursively()
                        throw IllegalStateException("cannot open $uri")
                    }
                    val content = src.readText(Charsets.UTF_8)
                    val decoded = DataImport.decodeFile(src.name, content)
                    require(decoded.isNotEmpty()) { "Invalid data" }
                    val books = db.bookDao().observeAll().first()
                    val bookIndex = DesktopBridge.buildBookIndex(books)
                    val (resolved, missing) = DataImport.resolveBooks(decoded, bookIndex)
                    if (missing.isNotEmpty()) {
                        workDir.deleteRecursively()
                        throw IllegalStateException(
                            "Book(s) missing: ${missing.joinToString(", ")}",
                        )
                    }
                    val existing = db.noteDao().observeAll().first().map { it.key }.toHashSet() +
                        db.wordDao().observeAll().first().map { it.key }.toHashSet()
                    val (fresh, skipped) = DataImport.dedupe(resolved, existing)
                    DesktopBridge.applyImport(fresh, db)
                    Triple(fresh.size, skipped, src)
                }
            }
            withContext(Dispatchers.IO) { workDir.deleteRecursively() }
            if (result.isFailure) {
                _state.value = BackupUiState.Errored(result.exceptionOrNull()?.message ?: "import failed")
                return@launch
            }
            val (imported, skipped, _) = result.getOrThrow()
            _state.value = BackupUiState.Finished(
                "Imported $imported records, skipped $skipped existing records",
            )
        }
    }

    companion object {
        fun formatImportReport(r: ImportReport): String = buildString {
            r.tables.forEach { (t, tr) ->
                append(t).append("[").append(tr.source).append("] ")
                    .append(tr.imported).append("/").append(tr.rows)
                append("   ")
            }
            append("\ncovers: ").append(r.coversImported)
            append("  books: ").append(r.booksImported)
            if (r.failures.isNotEmpty()) {
                append("\nwarnings:\n")
                r.failures.forEach { append(" - ").append(it).append('\n') }
            }
        }
    }
}

/** P7 data-export type — mirrors desktop `exportType` (`note|highlight|dictionaryHistory`). */
enum class BackupDataExportType(val wire: String) {
    NOTES("note"),
    HIGHLIGHTS("highlight"),
    DICTIONARY("dictionaryHistory");
}

/** Export format choices; desktop uses CSV/JSON/MD/TXT/HTML. */
enum class BackupDataExportFormat(val extension: String) {
    CSV("csv"), JSON("json"), MD("md"), TXT("txt"), HTML("html");

    fun toCore(): DataExport.Format = when (this) {
        CSV -> DataExport.Format.CSV
        JSON -> DataExport.Format.JSON
        MD -> DataExport.Format.MD
        TXT -> DataExport.Format.TXT
        HTML -> DataExport.Format.HTML
    }
}
