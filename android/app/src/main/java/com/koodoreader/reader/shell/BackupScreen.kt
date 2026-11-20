package com.koodoreader.reader.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.koodoreader.core.sync.ProgressChoice
import com.koodoreader.core.sync.ProgressConflict
import kotlin.math.roundToInt

/**
 * Backup / restore + data export/import (P7): 桌面 .db ⇄ native Room, both
 * directions; desktop CSV/JSON ⇄ native Room for notes/highlights/words.
 *
 * The screen is split in two halves:
 *  - "Full bundle" — `KoodoReader-Backup-*.zip` carrying all five tables +
 *    covers + books (mirrors `src/utils/file/backup.ts`).
 *  - "Data only" — CSV/JSON export of notes, highlights, or dictionary
 *    history (mirrors `src/utils/file/export.ts`), accepting a CSV/JSON
 *    SAF pick on the import side (mirrors `src/utils/file/importData.ts`).
 *
 * Optional sync snapshot (local dir / WebDAV / presigned S3) via `:core:sync`.
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncPrefs = remember { viewModel.syncPrefs() }
    var syncKind by remember { mutableStateOf(syncPrefs.kind) }
    var localPath by remember { mutableStateOf(syncPrefs.localSnapshotPath) }
    var webDavUrl by remember { mutableStateOf(syncPrefs.webDavUrl) }
    var webDavUser by remember { mutableStateOf(syncPrefs.webDavUser) }
    var webDavPass by remember { mutableStateOf(syncPrefs.webDavPassword) }
    var s3Url by remember { mutableStateOf(syncPrefs.s3Url) }
    var s3Headers by remember { mutableStateOf(syncPrefs.s3HeadersJson) }
    val importZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importBackup) }
    val importDataLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importData) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(t("Backup / restore"), style = MaterialTheme.typography.titleLarge)
        Text(
            t("Local zip backups; optional sync snapshot to a folder or remote URL."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        // ====================================================== full bundle
        Text(
            t("Full desktop backup (zip)"),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            t("Bundles books, notes, highlights, bookmarks, dictionary history, covers and book files; restore on desktop via the backup dialog."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { importZipLauncher.launch(arrayOf("application/zip", "*/*")) },
                enabled = state !is BackupUiState.Running,
                modifier = Modifier.weight(1f),
            ) { Text(t("Import zip")) }
            OutlinedButton(
                onClick = { viewModel.exportBackup() },
                enabled = state !is BackupUiState.Running,
                modifier = Modifier.weight(1f),
            ) { Text(t("Export zip")) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(t("Sync snapshot"), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { syncKind = SyncPrefs.TargetKind.LOCAL },
                label = { Text(t("Local")) },
            )
            AssistChip(
                onClick = { syncKind = SyncPrefs.TargetKind.WEBDAV },
                label = { Text(t("WebDAV")) },
            )
            AssistChip(
                onClick = { syncKind = SyncPrefs.TargetKind.S3 },
                label = { Text(t("S3 Compatible")) },
            )
        }
        when (syncKind) {
            SyncPrefs.TargetKind.LOCAL -> OutlinedTextField(
                value = localPath,
                onValueChange = { localPath = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(t("Snapshot directory")) },
                singleLine = true,
            )
            SyncPrefs.TargetKind.WEBDAV -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = webDavUrl,
                    onValueChange = { webDavUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t("WebDAV URL")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = webDavUser,
                    onValueChange = { webDavUser = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t("Username")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = webDavPass,
                    onValueChange = { webDavPass = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t("Password")) },
                    singleLine = true,
                )
            }
            SyncPrefs.TargetKind.S3 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = s3Url,
                    onValueChange = { s3Url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t("S3 / presigned URL")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = s3Headers,
                    onValueChange = { s3Headers = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t("Headers JSON")) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    syncPrefs.kind = syncKind
                    syncPrefs.localSnapshotPath = localPath
                    syncPrefs.webDavUrl = webDavUrl
                    syncPrefs.webDavUser = webDavUser
                    syncPrefs.webDavPassword = webDavPass
                    syncPrefs.s3Url = s3Url
                    syncPrefs.s3HeadersJson = s3Headers
                    viewModel.syncPull()
                },
                enabled = state !is BackupUiState.Running,
                modifier = Modifier.weight(1f),
            ) { Text(t("Pull")) }
            Button(
                onClick = {
                    syncPrefs.kind = syncKind
                    syncPrefs.localSnapshotPath = localPath
                    syncPrefs.webDavUrl = webDavUrl
                    syncPrefs.webDavUser = webDavUser
                    syncPrefs.webDavPassword = webDavPass
                    syncPrefs.s3Url = s3Url
                    syncPrefs.s3HeadersJson = s3Headers
                    viewModel.syncPush()
                },
                enabled = state !is BackupUiState.Running,
                modifier = Modifier.weight(1f),
            ) { Text(t("Push")) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ===================================================== per-type data
        Text(
            t("Data export / import (CSV / JSON / MD)"),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            t("Mirrors the desktop `exportNotes` / `exportHighlights` / `exportDictionaryHistory` flows; round-trips through any CSV or JSON file the desktop produces."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DataExportRow(
            title = t("Notes"),
            enabled = state !is BackupUiState.Running,
            onExport = { fmt -> viewModel.exportData(BackupDataExportType.NOTES, fmt) },
        )
        DataExportRow(
            title = t("Highlights"),
            enabled = state !is BackupUiState.Running,
            onExport = { fmt -> viewModel.exportData(BackupDataExportType.HIGHLIGHTS, fmt) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { viewModel.exportData(BackupDataExportType.DICTIONARY, BackupDataExportFormat.CSV) },
                enabled = state !is BackupUiState.Running,
                modifier = Modifier.weight(1f),
            ) { Text(t("Dictionary → CSV")) }
            OutlinedButton(
                onClick = { viewModel.exportData(BackupDataExportType.DICTIONARY, BackupDataExportFormat.JSON) },
                enabled = state !is BackupUiState.Running,
                modifier = Modifier.weight(1f),
            ) { Text(t("Dictionary → JSON")) }
        }
        Button(
            onClick = { importDataLauncher.launch(arrayOf("text/csv", "application/json", "*/*")) },
            enabled = state !is BackupUiState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(t("Import CSV / JSON")) }

        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
            Text(t("Back to library"))
        }
        Spacer(modifier = Modifier.height(8.dp))
        when (val s = state) {
            BackupUiState.Idle -> Text(
                t("No action yet"),
                style = MaterialTheme.typography.bodyMedium,
            )
            is BackupUiState.Running -> Text(
                s.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            is BackupUiState.Finished -> Column {
                // The wording comes from the locale catalogs; the counts come
                // from the structured report. The ViewModel deliberately does
                // not assemble a user-visible sentence.
                Text(
                    t(s.message),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                s.report?.let { report ->
                    Text(
                        text = t("Sync applied") + ": " +
                            "${report.progressApplied} · " +
                            t("Notes") + " +${report.notesAdded}" +
                            (if (report.notesKept > 0) " (${report.notesKept})" else "") +
                            " · " +
                            t("Bookmarks") + " +${report.bookmarksAdded}" +
                            (if (report.bookmarksKept > 0) " (${report.bookmarksKept})" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            is BackupUiState.Errored -> Text(
                s.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            is BackupUiState.Conflicts -> Text(
                t("Reading positions differ"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    (state as? BackupUiState.Conflicts)?.let { conflicts ->
        SyncConflictDialog(
            state = conflicts,
            onConfirm = viewModel::resolveConflicts,
            onCancel = viewModel::cancelSyncPull,
        )
    }
}

/**
 * The pull parked by [BackupViewModel.syncPull] because a book would have moved
 * backwards. Defaults never discard progress: each row starts on the side that
 * is further into the book.
 */
@Composable
private fun SyncConflictDialog(
    state: BackupUiState.Conflicts,
    onConfirm: (Map<String, ProgressChoice>) -> Unit,
    onCancel: () -> Unit,
) {
    val choices = remember(state) {
        mutableStateMapOf<String, ProgressChoice>().apply {
            state.conflicts.forEach { put(it.bookKey, it.suggested) }
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(t("Reading positions differ")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    t("Choose which position to keep for each book."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.conflicts.forEach { conflict ->
                    SyncConflictRow(
                        conflict = conflict,
                        title = state.titles[conflict.bookKey] ?: conflict.bookKey,
                        choice = choices[conflict.bookKey] ?: conflict.suggested,
                        onChoice = { choices[conflict.bookKey] = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(choices.toMap()) }) { Text(t("Apply")) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(t("Cancel")) }
        },
    )
}

@Composable
private fun SyncConflictRow(
    conflict: ProgressConflict,
    title: String,
    choice: ProgressChoice,
    onChoice: (ProgressChoice) -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConflictOption(
                label = t("This device") + " · " + percent(conflict.localFraction),
                selected = choice == ProgressChoice.KEEP_LOCAL,
                modifier = Modifier.weight(1f),
                onClick = { onChoice(ProgressChoice.KEEP_LOCAL) },
            )
            ConflictOption(
                label = t("Remote") + " · " + percent(conflict.remoteFraction),
                selected = choice == ProgressChoice.TAKE_REMOTE,
                modifier = Modifier.weight(1f),
                onClick = { onChoice(ProgressChoice.TAKE_REMOTE) },
            )
        }
    }
}

@Composable
private fun ConflictOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun percent(fraction: Double): String =
    "${(fraction.coerceIn(0.0, 1.0) * 100).roundToInt()}%"

@Composable
private fun DataExportRow(
    title: String,
    enabled: Boolean,
    onExport: (BackupDataExportFormat) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        AssistChip(
            onClick = { onExport(BackupDataExportFormat.CSV) },
            enabled = enabled,
            label = { Text(t("CSV")) },
        )
        AssistChip(
            onClick = { onExport(BackupDataExportFormat.JSON) },
            enabled = enabled,
            label = { Text(t("JSON")) },
        )
        AssistChip(
            onClick = { onExport(BackupDataExportFormat.MD) },
            enabled = enabled,
            label = { Text(t("MD")) },
        )
    }
}
