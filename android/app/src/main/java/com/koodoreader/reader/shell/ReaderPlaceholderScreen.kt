package com.koodoreader.reader.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Reader placeholder (P1). Shows the book record plus live annotation counts
 * to prove the Room pipeline end-to-end; the actual native reading engine
 * (engine/epub etc.) lands in P2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPlaceholderScreen(
    bookKey: String,
    onBack: () -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val book by viewModel.book(bookKey)
        .collectAsStateWithLifecycle(initialValue = null)
    val counts by viewModel.annotationCounts(bookKey)
        .collectAsStateWithLifecycle(initialValue = AnnotationCounts(0, 0, 0))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book?.name.orEmpty(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = book?.let { "${it.author.orEmpty()} · ${it.format?.uppercase().orEmpty()}" }
                    ?: "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Native reader engine lands in P2.", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "This screen already reads koodo.db through Room — " +
                            "annotation data below is live:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                CountChip("Notes", counts.notes)
                CountChip("Bookmarks", counts.bookmarks)
                CountChip("Words", counts.words)
            }

            Text(
                text = "Positions resolve via epubcfi (engine/cfi, ADR-002).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CountChip(label: String, value: Long) {
    Column {
        Text("$value", style = MaterialTheme.typography.headlineSmall)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
