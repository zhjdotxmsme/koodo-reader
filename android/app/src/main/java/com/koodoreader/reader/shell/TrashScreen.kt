package com.koodoreader.reader.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 回收站 (P1 shell): soft-deleted books (desktop `deletedBooks` list) with
 * restore / 彻底删除. The books table rows stay untouched until a purge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val trashed by viewModel.trashedBooks.collectAsStateWithLifecycle()
    val shelf by viewModel.shelf.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${t("Trash")} (${trashed.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (trashed.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    t("Trash is empty"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(trashed, key = { it.key }) { book ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BookListRow(
                            book = book,
                            isFavorite = book.key in shelf.favorites,
                            coverVersion = 0,
                            onClick = {},
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { viewModel.restoreFromTrash(book.key) },
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text(t("Restore")) }
                        Button(
                            onClick = { viewModel.purge(listOf(book.key)) },
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text(t("Permanently Delete")) }
                    }
                }
            }
        }
    }
}
