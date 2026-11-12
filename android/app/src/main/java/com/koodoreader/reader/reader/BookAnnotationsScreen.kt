package com.koodoreader.reader.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.ui.component.koodoPressScale
import com.koodoreader.core.ui.theme.KoodoCardShape
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
        loading -> CircularProgressIndicator(modifier = Modifier.padding(SpaceTokens.md.dp))
        error != null -> Column(modifier = Modifier.padding(SpaceTokens.md.dp)) {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry, modifier = Modifier.koodoPressScale()) { Text(t("Retry")) }
        }
        notes.isEmpty() && bookmarks.isEmpty() -> Text(
            t("No notes yet"),
            modifier = Modifier.padding(SpaceTokens.md.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = SpaceTokens.LIST_ITEM_HORIZONTAL.dp,
                    vertical = SpaceTokens.xs.dp,
                ),
            ) {
                TextButton(
                    onClick = { addingNote = true; newNoteDraft = "" },
                    modifier = Modifier.koodoPressScale(),
                ) {
                    Text(t("Note"))
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(bottom = SpaceTokens.md.dp),
                verticalArrangement = Arrangement.spacedBy(SpaceTokens.GROUP_GAP.dp),
            ) {
                items(notes, key = { it.key }) { note ->
                    Surface(
                        shape = KoodoCardShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = SpaceTokens.LIST_ITEM_HORIZONTAL.dp,
                                    vertical = SpaceTokens.LIST_ITEM_VERTICAL.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(SpaceTokens.GROUP_TITLE_GAP.dp),
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
                                TextButton(
                                    onClick = { editing = note; draft = note.notes.orEmpty() },
                                    modifier = Modifier.koodoPressScale(),
                                ) {
                                    Text(t("Edit"))
                                }
                                TextButton(
                                    onClick = { onDeleteNote(note) },
                                    modifier = Modifier.koodoPressScale(),
                                ) { Text(t("Delete")) }
                                note.cfi?.takeIf { it.isNotBlank() }?.let { cfi ->
                                    TextButton(
                                        onClick = { onJump(cfi) },
                                        modifier = Modifier.koodoPressScale(),
                                    ) { Text(t("Jump")) }
                                }
                            }
                            if (note.color != null || note.text?.isNotBlank() == true) {
                                Row(modifier = Modifier.padding(top = SpaceTokens.xs.dp)) {
                                    recolorPalette.forEach { swatch ->
                                        val code = swatch.code.toLong()
                                        val rgb = HighlightPalette.paintArgb(code) and 0xFFFFFF
                                        TextButton(
                                            onClick = { onRecolor(note, code) },
                                            modifier = Modifier
                                                .padding(end = SpaceTokens.xs.dp)
                                                .koodoPressScale(),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .background(Color(rgb or 0xFF000000), CircleShape),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                items(bookmarks, key = { it.key }) { bookmark ->
                    Row(
                        modifier = Modifier.padding(
                            horizontal = SpaceTokens.LIST_ITEM_HORIZONTAL.dp,
                            vertical = SpaceTokens.LIST_ITEM_VERTICAL.dp,
                        ),
                    ) {
                        Text(
                            bookmark.label ?: bookmark.chapter.orEmpty(),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        bookmark.cfi?.takeIf { it.isNotBlank() }?.let { cfi ->
                            TextButton(
                                onClick = { onJump(cfi) },
                                modifier = Modifier.koodoPressScale(),
                            ) { Text(t("Jump")) }
                        }
                        TextButton(
                            onClick = { onDeleteBookmark(bookmark) },
                            modifier = Modifier.koodoPressScale(),
                        ) { Text(t("Delete")) }
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
                TextButton(
                    onClick = {
                        onAddNote(newNoteDraft.trim())
                        addingNote = false
                    },
                    modifier = Modifier.koodoPressScale(),
                ) { Text(t("Confirm")) }
            },
            dismissButton = {
                TextButton(
                    onClick = { addingNote = false },
                    modifier = Modifier.koodoPressScale(),
                ) { Text(t("Cancel")) }
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
                TextButton(
                    onClick = {
                        onEditNote(note, draft)
                        editing = null
                    },
                    modifier = Modifier.koodoPressScale(),
                ) { Text(t("Confirm")) }
            },
            dismissButton = {
                TextButton(
                    onClick = { editing = null },
                    modifier = Modifier.koodoPressScale(),
                ) { Text(t("Cancel")) }
            },
        )
    }
}
