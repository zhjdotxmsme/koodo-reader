package com.koodoreader.reader.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.koodoreader.core.designsystem.SpaceTokens

/**
 * Word list — the vocabulary saved from the reader's dictionary popup.
 *
 * The data is the desktop `words` table, so this is a view, not a new store:
 * words saved on the phone survive a desktop backup round-trip and vice versa
 * (see the Dictionary history export in Backup).
 *
 * Rows open the book the word came from, so a saved word leads back to the
 * sentence it was met in.
 */
@Composable
fun WordbookScreen(
    onBack: () -> Unit,
    onOpenBook: (bookKey: String) -> Unit,
    viewModel: WordbookViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val i18n = LocalI18n.current
    val savedWords by viewModel.items.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val visible = remember(savedWords, query) { WordbookAggregation.search(savedWords, query) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = SpaceTokens.SCREEN_HORIZONTAL.dp,
                    end = SpaceTokens.SCREEN_HORIZONTAL.dp,
                    top = SpaceTokens.lg.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = i18n.localization.t("Word list"),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text(i18n.localization.t("Back")) }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = {
                Text(
                    text = i18n.localization.t("Search words"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { query = "" }) {
                        Text(i18n.localization.t("Clear"))
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = SpaceTokens.SCREEN_HORIZONTAL.dp,
                    end = SpaceTokens.SCREEN_HORIZONTAL.dp,
                    top = SpaceTokens.sm.dp,
                    bottom = SpaceTokens.sm.dp,
                ),
        )

        if (visible.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = i18n.localization.t(
                        if (query.isNotBlank()) "No results found" else "No saved words",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = SpaceTokens.xl.dp),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.key }) { item ->
                    WordRow(
                        item = item,
                        onOpen = { if (item.bookKey.isNotBlank()) onOpenBook(item.bookKey) },
                        onDelete = { viewModel.delete(item.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WordRow(item: WordItem, onOpen: () -> Unit, onDelete: () -> Unit) {
    val i18n = LocalI18n.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(
                horizontal = SpaceTokens.SCREEN_HORIZONTAL.dp,
                vertical = SpaceTokens.sm.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = item.word,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.hasSentence) {
            Text(
                text = item.sentence,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = listOf(item.bookTitle, item.chapter, item.dateLabel)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDelete) { Text(i18n.localization.t("Delete")) }
        }
    }
}
