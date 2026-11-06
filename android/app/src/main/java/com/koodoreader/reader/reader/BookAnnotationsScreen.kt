package com.koodoreader.reader.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.koodoreader.core.data.entity.BookmarkEntity
import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.reader.shell.t

/**
 * Per-book notes / highlights / bookmarks list with edit, recolor, delete and
 * jump-back. Used inside a bottom sheet from the reader; all mutations go
 * through the callbacks (BookAnnotationsViewModel).
 */
@Composable
fun BookAnnotationsScreen(
    notes: List<NoteEntity>,
    bookmarks: List<BookmarkEntity>,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    onJump: (cfi: String) -> Unit = {},
    onEditNote: (NoteEntity, String) -> Unit = { _, _ -> },
    onDeleteNote: (NoteEntity) -> Unit = {},
    onDeleteBookmark: (BookmarkEntity) -> Unit = {},
) {
    var editing by remember { mutableStateOf<NoteEntity?>(null) }
    var draft by remember { mutableStateOf("") }

    when {
        loading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        error != null -> Column(modifier = Modifier.padding(16.dp)) {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text(t("Retry")) }
        }
        notes.isEmpty() && bookmarks.isEmpty() -> Text(
            t("No notes yet"),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> LazyColumn {
            items(notes, key = { it.key }) { note ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        note.text.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                    if (!note.notes.isNullOrEmpty()) {
                        Text(
                            note.notes.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        note.chapter.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = { editing = note; draft = note.notes.orEmpty() }) {
                            Text(t("Edit"))
                        }
                        TextButton(onClick = { onDeleteNote(note) }) { Text(t("Delete")) }
                        note.cfi?.takeIf { it.isNotBlank() }?.let { cfi ->
                            TextButton(onClick = { onJump(cfi) }) { Text(t("Jump")) }
                        }
                    }
                }
            }
            items(bookmarks, key = { it.key }) { bookmark ->
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        bookmark.label ?: bookmark.chapter.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    bookmark.cfi?.takeIf { it.isNotBlank() }?.let { cfi ->
                        TextButton(onClick = { onJump(cfi) }) { Text(t("Jump")) }
                    }
                    TextButton(onClick = { onDeleteBookmark(bookmark) }) { Text(t("Delete")) }
                }
            }
        }
    }

    editing?.let { note ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(t("Edit")) },
            text = {
                Column {
                    Text(
                        note.text.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onEditNote(note, draft)
                    editing = null
                }) { Text(t("Confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(t("Cancel")) }
            },
        )
    }
}
