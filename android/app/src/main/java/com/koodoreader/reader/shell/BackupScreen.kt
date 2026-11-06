package com.koodoreader.reader.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

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
                label = { Text("WebDAV") },
            )
            AssistChip(
                onClick = { syncKind = SyncPrefs.TargetKind.S3 },
                label = { Text("S3") },
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
                    label = { Text("WebDAV URL") },
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
                    label = { Text("S3 / presigned URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = s3Headers,
                    onValueChange = { s3Headers = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Headers JSON") },
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
            is BackupUiState.Finished -> Text(
                s.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            is BackupUiState.Errored -> Text(
                s.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

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
            label = { Text("CSV") },
        )
        AssistChip(
            onClick = { onExport(BackupDataExportFormat.JSON) },
            enabled = enabled,
            label = { Text("JSON") },
        )
        AssistChip(
            onClick = { onExport(BackupDataExportFormat.MD) },
            enabled = enabled,
            label = { Text("MD") },
        )
    }
}
