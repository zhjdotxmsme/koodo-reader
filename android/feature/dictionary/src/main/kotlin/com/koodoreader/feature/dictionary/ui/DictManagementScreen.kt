package com.koodoreader.feature.dictionary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.koodoreader.feature.dictionary.CloudDictItem
import com.koodoreader.feature.dictionary.DictEntry
import com.koodoreader.feature.dictionary.DictSourceKind

/**
 * 词典管理 — enable / order / set-default / import / delete / download.
 *
 * STATELESS BY DESIGN: the screen renders [DictManagementState] and reports intents;
 * the app owns the `DictRepository` and applies them. That keeps this file free of
 * Android lifecycle dependencies (like the other `feature`/`engine` modules) and
 * makes the state trivially assertable from a unit test.
 *
 * CHECKLIST DEVIATION (documented in `docs/p6-dictionary-architecture.md` §4): the
 * desktop settings page has no enable/order/default controls — it only imports,
 * deletes and downloads (src/containers/settings/dictSetting/component.tsx:28-122)
 * and the reader picks the dictionary from the plugin config
 * (popupDict/component.tsx:189). The flags come from [DictEntry] on the Android side.
 *
 * USER-VISIBLE STRINGS: passed in through [DictStrings] so the app can supply the
 * `:core:common` i18n lookups; the defaults below are the same keys the desktop uses
 * (`Import successful`, `Deletion successful`, `Download open dictionaries`,
 * `Download successful`, `Download failed`, `Dictionary already downloaded`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictManagementScreen(
    state: DictManagementState,
    onToggleEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onSetDefault: (String) -> Unit = {},
    onMove: (String, Int) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {},
    onImportClick: () -> Unit = {},
    onDownloadClick: (CloudDictItem) -> Unit = {},
    onBack: () -> Unit = {},
    strings: DictStrings = DictStrings(),
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(strings.title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = strings.back)
                    }
                },
                actions = {
                    IconButton(onClick = onImportClick) {
                        Icon(Icons.Default.Add, contentDescription = strings.importDict)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.dicts.isEmpty()) {
                item {
                    Text(
                        text = strings.empty,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(state.dicts, key = { it.id }) { entry ->
                DictRow(
                    entry = entry,
                    isFirst = state.dicts.firstOrNull()?.id == entry.id,
                    isLast = state.dicts.lastOrNull()?.id == entry.id,
                    strings = strings,
                    onToggleEnabled = { onToggleEnabled(entry.id, it) },
                    onSetDefault = { onSetDefault(entry.id) },
                    onMove = { delta -> onMove(entry.id, delta) },
                    onDelete = { onDelete(entry.id) },
                )
                HorizontalDivider()
            }

            if (state.cloudDicts.isNotEmpty()) {
                item {
                    Text(
                        text = strings.downloadSection, // desktop: <Trans>Download open dictionaries</Trans>
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                    )
                }
                items(state.cloudDicts, key = { "cloud-${it.id}" }) { item ->
                    CloudDictRow(
                        item = item,
                        installed = state.dicts.any { it.id == item.id },
                        downloading = state.downloadingId == item.id,
                        progress = state.downloadProgress,
                        strings = strings,
                        onDownloadClick = { onDownloadClick(item) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun DictRow(
    entry: DictEntry,
    isFirst: Boolean,
    isLast: Boolean,
    strings: DictStrings,
    onToggleEnabled: (Boolean) -> Unit,
    onSetDefault: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
                if (entry.isDefault) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = strings.defaultDict,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = buildString {
                    append(entry.extension.uppercase())
                    append(" · ")
                    append(sizeLabel(entry.sizeBytes))
                    if (entry.source != DictSourceKind.IMPORTED) {
                        append(" · ")
                        append(
                            when (entry.source) {
                                DictSourceKind.BUNDLED -> strings.sourceBundled
                                DictSourceKind.CLOUD -> strings.sourceCloud
                                DictSourceKind.IMPORTED -> strings.sourceImported
                            }
                        )
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = { onMove(-1) }, enabled = !isFirst) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = strings.moveUp)
        }
        IconButton(onClick = { onMove(1) }, enabled = !isLast) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = strings.moveDown)
        }
        if (!entry.isDefault) {
            TextButton(onClick = onSetDefault) { Text(strings.setDefault) }
        }
        Switch(checked = entry.enabled, onCheckedChange = onToggleEnabled)
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = strings.delete)
        }
    }
}

@Composable
private fun CloudDictRow(
    item: CloudDictItem,
    installed: Boolean,
    downloading: Boolean,
    progress: Float,
    strings: DictStrings,
    onDownloadClick: () -> Unit,
) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = item.name, style = MaterialTheme.typography.bodyLarge)
            if (item.source.isNotEmpty()) {
                Text(
                    text = item.source,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            when {
                installed -> Text(strings.alreadyInstalled, style = MaterialTheme.typography.labelMedium)
                downloading -> {
                    if (progress >= 0f) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                else -> TextButton(onClick = onDownloadClick) { Text(strings.download) }
            }
        }
    }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/** Everything the screen shows — the app supplies localized values. */
data class DictStrings(
    val title: String = "Dictionary",
    val back: String = "Back",
    val importDict: String = "Import dictionary",
    val empty: String = "No dictionary installed",
    val defaultDict: String = "Default dictionary",
    val setDefault: String = "Set default",
    val moveUp: String = "Move up",
    val moveDown: String = "Move down",
    val delete: String = "Delete",
    val downloadSection: String = "Download open dictionaries",
    val download: String = "Download",
    val alreadyInstalled: String = "Dictionary already downloaded",
    val sourceBundled: String = "Bundled",
    val sourceCloud: String = "Cloud",
    val sourceImported: String = "Imported",
)

/** Screen state; `downloadingId == null` means idle, `downloadProgress < 0` is indeterminate. */
data class DictManagementState(
    val dicts: List<DictEntry> = emptyList(),
    val cloudDicts: List<CloudDictItem> = emptyList(),
    val downloadingId: String? = null,
    val downloadProgress: Float = -1f,
    val message: String? = null,
)
