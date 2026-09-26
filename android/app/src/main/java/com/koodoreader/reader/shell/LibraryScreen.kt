package com.koodoreader.reader.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Bookshelf (P1): Room-backed grid/list of `books` rows, SAF import entry,
 * sort/view/favorites/trash (desktop manager parity, [LibraryLogic]) and
 * long-press drag reorder in grid mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val books by viewModel.libraryBooks.collectAsStateWithLifecycle()
    val shelf by viewModel.shelf.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::importFolder) }
    val filesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.importFiles(uris) }
    // Bumped after every finished import so card covers re-resolve
    // (cover files are written by the import, not the row).
    val coverVersion = (importState as? ImportState.Finished)?.hashCode() ?: 0
    var menuOpen by remember { mutableStateOf(false) }
    var importMenuOpen by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    var draggingKey by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Koodo Reader") },
                actions = {
                    when (val state = importState) {
                        is ImportState.Running -> Text(
                            text = t("Import") + " ${state.done}/${state.total}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        is ImportState.Finished -> Text(
                            text = buildString {
                                if (state.error != null) {
                                    append(t("Import failed")).append(": ").append(state.error)
                                } else {
                                    append(t("Import")).append(" ${state.imported}")
                                    if (state.duplicates > 0) append(" · ").append(t("Duplicate")).append(' ').append(state.duplicates)
                                    if (state.failed > 0) append(" · ").append(t("Import failed")).append(' ').append(state.failed)
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        ImportState.Idle -> Unit
                    }
                    // ONE import affordance with both sources inside, instead of
                    // two icon buttons whose glyphs said something else (the file
                    // picker wore a hamburger, backup wore a list icon).
                    Box {
                        IconButton(onClick = { importMenuOpen = true }) {
                            Icon(Icons.Filled.Add, contentDescription = t("Import"))
                        }
                        DropdownMenu(
                            expanded = importMenuOpen,
                            onDismissRequest = { importMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(t("Import")) },
                                onClick = {
                                    filesLauncher.launch(arrayOf("*/*"))
                                    importMenuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(t("Import books from folder")) },
                                onClick = {
                                    treeLauncher.launch(null)
                                    importMenuOpen = false
                                },
                            )
                        }
                    }
                    // The library's OWN view options (grid/list, sort, favourites)
                    // are what is left of the old seven-group menu. Everything
                    // global moved out: Trash / Backup / Dictionary → Settings,
                    // Stats → its own tab, Language → Settings › Language.
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = t("View"))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (shelf.viewGrid) t("Switch to list view") else t("Switch to grid view")) },
                                onClick = { viewModel.setViewGrid(!shelf.viewGrid); menuOpen = false },
                            )
                            SortField.entries.forEach { field ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            buildString {
                                                append(t("Sort by")).append(": ").append(t(field.labelKey))
                                                if (shelf.sort.field == field) {
                                                    append(if (shelf.sort.ascending) " ↑" else " ↓")
                                                }
                                            },
                                        )
                                    },
                                    onClick = { viewModel.setSortField(field); menuOpen = false },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (shelf.favoritesOnly) t("Show all books") else t("Show favorites")) },
                                onClick = { viewModel.setFavoritesOnly(!shelf.favoritesOnly); menuOpen = false },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            val running = importState as? ImportState.Running
            if (running != null && running.total > 0) {
                LinearProgressIndicator(
                    progress = running.done.toFloat() / running.total,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (books.isEmpty()) {
                EmptyLibraryHint(shelf.favoritesOnly)
            } else if (shelf.viewGrid) {                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(books, key = { it.key }) { book ->
                        val itemInfo = gridState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == book.key }
                        BookCard(
                            book = book,
                            coverVersion = coverVersion,
                            isFavorite = book.key in shelf.favorites,
                            onToggleFavorite = { viewModel.toggleFavorite(book.key) },
                            onMoveToTrash = { viewModel.moveToTrash(book.key) },
                            onClick = { onOpenBook(book.key) },
                            modifier = Modifier.pointerInput(book.key) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { draggingKey = book.key },
                                    onDragEnd = { draggingKey = null },
                                    onDragCancel = { draggingKey = null },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val drag = draggingKey ?: return@detectDragGesturesAfterLongPress
                                        val info = itemInfo ?: return@detectDragGesturesAfterLongPress
                                        val x = info.offset.x + change.position.x
                                        val y = info.offset.y + change.position.y
                                        val hover = gridState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { i ->
                                                x >= i.offset.x && x <= i.offset.x + i.size.width &&
                                                    y >= i.offset.y && y <= i.offset.y + i.size.height
                                            }
                                            ?.key as? String
                                        if (hover != null && hover != drag) {
                                            viewModel.moveManual(drag, hover)
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(books, key = { it.key }) { book ->
                        BookListRow(
                            book = book,
                            isFavorite = book.key in shelf.favorites,
                            coverVersion = coverVersion,
                            onClick = { onOpenBook(book.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryHint(favoritesOnly: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (favoritesOnly) {
                t("No favorite books")
            } else {
                t("Library is empty") + "\n" + t("Click the import button to add books")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Language names are shown in their own language by convention (untranslated). */
internal fun languageLabel(code: String): String = when (code) {
    I18nState.SYSTEM -> "System"
    "en" -> "English"
    "zh-CN" -> "中文"
    else -> code
}
