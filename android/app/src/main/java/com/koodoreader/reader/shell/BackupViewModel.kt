package com.koodoreader.reader.shell

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koodoreader.core.data.KoodoDatabase
import com.koodoreader.core.data.KoodoDatabaseProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
                    },
                )
            }
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
