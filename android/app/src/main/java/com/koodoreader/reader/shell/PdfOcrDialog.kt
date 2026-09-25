package com.koodoreader.reader.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The PDF reader's OCR sheet (P6 OCR ⇄ P3 reader).
 *
 * Two jobs, in the order a scanned-PDF reader needs them:
 *  1. **index** — recognise pages into the OCR index (current page, or the whole
 *     document), with a determinate progress bar and a cancel;
 *  2. **search** — query that index. It is deliberately a separate search from
 *     the pdf.js text-layer one (the toolbar's magnifier), because a scanned PDF
 *     has no text layer at all: that is why OCR exists here.
 *
 * The dialog is stateless; [PdfOcrController] owns everything else.
 */
@Composable
fun PdfOcrDialog(
    state: PdfOcrUiState,
    currentPage: Int,
    pageCount: Int,
    onIndexPage: (Int) -> Unit,
    onIndexAll: () -> Unit,
    onCancel: () -> Unit,
    onSearch: (String) -> Unit,
    onForget: () -> Unit,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(state.query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("OCR text recognition for scanned PDFs"), maxLines = 2) },
        text = {
            Column {
                Text(
                    text = "${t("Indexed pages")}: ${state.indexedPages}",
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (state.running) {
                    val progress = if (state.total > 0) state.done.toFloat() / state.total else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    Text(
                        text = "${t("Recognizing")} ${state.done}/${state.total}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = { onIndexPage(currentPage) }) {
                            Text(t("Index this page"))
                        }
                        TextButton(onClick = onIndexAll) {
                            Text(t("Index all pages"))
                        }
                    }
                }

                state.modelError?.let { reason ->
                    Text(
                        text = "${t("OCR model unavailable")}: $reason",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                state.summary?.takeIf { it.modelUnavailableReason == null }?.let { summary ->
                    Text(
                        text = "${t("Indexed pages")}: ${summary.indexed} · " +
                            "${t("Pages")}: ${summary.attempted}" +
                            if (summary.failed > 0) " · ${t("Import failed")}: ${summary.failed}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(t("Search")) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )

                when {
                    state.searching -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                    state.hits.isEmpty() && query.isNotBlank() -> Text(
                        t("No result found"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )

                    else -> LazyColumn(modifier = Modifier.fillMaxHeight(0.45f)) {
                        itemsIndexed(state.hits) { _, hit ->
                            TextButton(
                                onClick = { onJump(hit.pageIndex) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "[${hit.pageIndex}] ${hit.snippet}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                state.running -> TextButton(onClick = onCancel) { Text(t("Cancel")) }
                state.indexedPages > 0 -> TextButton(onClick = onForget) {
                    Text(t("Clear OCR index"))
                }
                else -> TextButton(onClick = onDismiss) { Text(t("Close")) }
            }
        },
        dismissButton = {
            TextButton(onClick = { onSearch(query) }) { Text(t("Search")) }
        },
    )
}
