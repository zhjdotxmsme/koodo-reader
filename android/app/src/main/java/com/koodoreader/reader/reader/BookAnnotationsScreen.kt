package com.koodoreader.reader.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.koodoreader.core.data.entity.BookmarkEntity
import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.engine.annotate.HighlightColor
import com.koodoreader.engine.annotate.HighlightPalette
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
    onRecolor: (NoteEntity, Long) -> Unit = { _, _ -> },
    onAddNote: (String) -> Unit = {},
    onEditNote: (NoteEntity, String) -> Unit = { _, _ -> },
    onDeleteNote: (NoteEntity) -> Unit = {},
    onDeleteBookmark: (BookmarkEntity) -> Unit = {},
) {
    var editing by remember { mutableStateOf<NoteEntity?>(null) }
    var draft by remember { mutableStateOf("") }
    var addingNote by remember { mutableStateOf(false) }
    var newNoteDraft by remember { mutableStateOf("") }
    val recolorPalette = remember {
        listOf(
            HighlightColor.YELLOW,
            HighlightColor.GREEN,
            HighlightColor.PINK,
            HighlightColor.BLUE,
        )
    }

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
        else -> Column {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                TextButton(onClick = { addingNote = true; newNoteDraft = "" }) {
                    Text(t("Note"))
                }
            }
            LazyColumn {
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
                    if (note.color != null || note.text?.isNotBlank() == true) {
                        Row(modifier = Modifier.padding(top = 4.dp)) {
                            recolorPalette.forEach { swatch ->
                                val code = swatch.code.toLong()
                                val argb = HighlightPalette.paintArgb(code)
                                val rgb = (argb and 0xFFFFFF).toInt()
                                TextButton(
                                    onClick = { onRecolor(note, code) },
                                    modifier = Modifier.padding(end = 4.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .background(Color(rgb), CircleShape),
                                    )
                                }
                            }
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
    }

    if (addingNote) {
        AlertDialog(
            onDismissRequest = { addingNote = false },
            title = { Text(t("Note")) },
            text = {
                OutlinedTextField(
                    value = newNoteDraft,
                    onValueChange = { newNoteDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAddNote(newNoteDraft.trim())
                    addingNote = false
                }) { Text(t("Confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { addingNote = false }) { Text(t("Cancel")) }
            },
        )
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
