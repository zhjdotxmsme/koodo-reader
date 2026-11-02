package com.koodoreader.reader.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.koodoreader.core.data.entity.BookEntity
import com.koodoreader.core.importer.BookMetadata

@Composable
fun BookMetadataDialog(
    book: BookEntity,
    state: BookMetadataUiState,
    onSave: (BookMetadata) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(book.key) { mutableStateOf(book.name.orEmpty()) }
    var author by remember(book.key) { mutableStateOf(book.author.orEmpty()) }
    var description by remember(book.key) { mutableStateOf(book.description.orEmpty()) }
    var publisher by remember(book.key) { mutableStateOf(book.publisher.orEmpty()) }
    val saving = state is BookMetadataUiState.Saving

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(t("Book Detail")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetadataField(t("Book name"), name, !saving) { name = it }
                MetadataField(t("Author"), author, !saving) { author = it }
                MetadataField(t("Description"), description, !saving) { description = it }
                MetadataField(t("Publisher"), publisher, !saving) { publisher = it }
                ReadOnlyMetadataField(t("Format"), book.format?.uppercase())
                ReadOnlyMetadataField(t("File size"), book.size?.let { "$it B" })
                ReadOnlyMetadataField(t("Pages"), book.page?.toString())
                ReadOnlyMetadataField(t("MD5"), book.md5)
                if (saving) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                (state as? BookMetadataUiState.Error)?.let { error ->
                    Text(
                        text = "${t("Save failed")}: ${error.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(BookMetadata(name, author, description, publisher)) },
                enabled = !saving,
            ) { Text(t("Save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(t("Cancel")) }
        },
    )
}

@Composable
private fun MetadataField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
    )
}

@Composable
private fun ReadOnlyMetadataField(label: String, value: String?) {
    OutlinedTextField(
        value = value.orEmpty().ifEmpty { "—" },
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        readOnly = true,
        singleLine = true,
    )
}
