package com.koodoreader.reader.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Backup / restore (P1 bridge card): 桌面 .db ⇄ native Room, both directions
 * over the desktop bundle layout (per-table `.db` files under `config/`,
 * plus `cover/` and `book/` entries).
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importBackup) }

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
            t("Import desktop backup") + " / " + t("Export desktop backup"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = { importLauncher.launch(arrayOf("application/zip", "*/*")) },
            enabled = state !is BackupUiState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(t("Import desktop backup") + " (zip)") }
        OutlinedButton(
            onClick = { viewModel.exportBackup() },
            enabled = state !is BackupUiState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(t("Export desktop backup") + " (zip)") }
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
