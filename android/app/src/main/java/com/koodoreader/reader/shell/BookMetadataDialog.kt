package com.koodoreader.reader.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.koodoreader.reader.metadata.BookIsbnProbe
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.koodoreader.core.data.entity.BookEntity
import com.koodoreader.core.importer.BookMetadata
import com.koodoreader.reader.metadata.FetchedBookMetadata

@Composable
fun BookMetadataDialog(
    book: BookEntity,
    state: BookMetadataUiState,
    searchState: BookMetadataSearchUiState,
    onSearch: (String, String, String) -> Unit,
    onSave: (BookMetadata, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(book.key) { mutableStateOf(book.name.orEmpty()) }
    var author by remember(book.key) { mutableStateOf(book.author.orEmpty()) }
    var description by remember(book.key) { mutableStateOf(book.description.orEmpty()) }
    var publisher by remember(book.key) { mutableStateOf(book.publisher.orEmpty()) }
    var isbn by remember(book.key) {
        mutableStateOf(BookIsbnProbe.probe(book).orEmpty())
    }
    var coverUrl by remember(book.key) { mutableStateOf<String?>(null) }
    val saving = state is BookMetadataUiState.Saving
    val searching = searchState is BookMetadataSearchUiState.Loading

    fun applyFetched(item: FetchedBookMetadata) {
        name = item.name
        author = item.author
        description = item.description
        publisher = item.publisher
        if (item.isbn.isNotBlank()) isbn = item.isbn
        coverUrl = item.coverUrl.takeIf { it.isNotBlank() }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(t("Book Detail")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { onSearch(name, author, isbn) },
                    enabled = !saving && !searching &&
                        (name.isNotBlank() || author.isNotBlank() || isbn.isNotBlank()),
                ) { Text(t("Get metadata")) }
                Text(
                    t("Metadata sources chained hint"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (searchState) {
                    BookMetadataSearchUiState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.fillMaxWidth(0.2f))
                    }
                    BookMetadataSearchUiState.Empty -> {
                        Text(t("No metadata found"), style = MaterialTheme.typography.bodySmall)
                    }
                    BookMetadataSearchUiState.Failed -> {
                        Text(t("Failed to fetch metadata"), style = MaterialTheme.typography.bodySmall)
                    }
                    is BookMetadataSearchUiState.Results -> {
                        searchState.items.forEach { item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { applyFetched(item) }
                                    .padding(vertical = 4.dp),
                            ) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                                if (item.author.isNotBlank()) {
                                    Text(item.author, style = MaterialTheme.typography.bodySmall)
                                }
                                metadataProviderLabel(item.source)?.let { label ->
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (item.description.isNotBlank()) {
                                    Text(
                                        item.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 3,
                                    )
                                }
                            }
                        }
                        Text(
                            t("Tap a result to fill the form, then save."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BookMetadataSearchUiState.Idle -> Unit
                }
                MetadataField(t("ISBN"), isbn, !saving) { isbn = it }
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
                onClick = { onSave(BookMetadata(name, author, description, publisher), coverUrl) },
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
private fun metadataProviderLabel(source: String): String? = when (source) {
    "douban" -> t("Metadata provider: Douban")
    "douban_api" -> t("Metadata provider: Douban API")
    "open_library" -> t("Metadata provider: Open Library")
    "google_books" -> t("Metadata provider: Google Books")
    else -> null
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