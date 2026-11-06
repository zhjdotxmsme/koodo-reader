package com.koodoreader.reader.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.koodoreader.core.designsystem.MotionTokens
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.ui.component.ErrorState
import com.koodoreader.core.ui.theme.KoodoMotion
import com.koodoreader.reader.R
import kotlinx.coroutines.launch

/**
 * Bookshelf (P1): Room-backed grid/list of `books` rows, SAF import entry,
 * sort/view/favorites/trash (desktop manager parity, [LibraryLogic]) and
 * long-press drag reorder in grid mode.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
    metadataViewModel: BookMetadataViewModel = viewModel(),
) {
    val books by viewModel.libraryBooks.collectAsStateWithLifecycle()
    val shelf by viewModel.shelf.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val libraryLoading by viewModel.libraryLoading.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val metadataState by metadataViewModel.state.collectAsStateWithLifecycle()
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
    var newShelfOpen by remember { mutableStateOf(false) }
    var newShelfTitle by remember { mutableStateOf("") }
    var shelfPickKey by remember { mutableStateOf<String?>(null) }
    var metadataBookKey by remember { mutableStateOf<String?>(null) }
    var moveDialogOpen by remember { mutableStateOf(false) }
    var moveFolderName by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current

    val gridState = rememberLazyGridState()
    var draggingKey by remember { mutableStateOf<String?>(null) }

    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showFailureDialog by remember { mutableStateOf(false) }
    var sortSheetOpen by remember { mutableStateOf(false) }
    val movedToTrashText = t("Moved to trash")
    val undoText = t("Undo")

    BackHandler(enabled = selection.active) { viewModel.clearSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AnimatedVisibility(
                visible = selection.active,
                enter = fadeIn(tween(MotionTokens.NORMAL, easing = KoodoMotion.Emphasized)) +
                    slideInVertically(tween(MotionTokens.NORMAL)) { -it / 2 },
                exit = fadeOut(tween(MotionTokens.FAST)),
            ) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = t("Cancel"))
                        }
                    },
                    title = { Text("${selection.selected.size}") },
                    actions = {
                        TextButton(onClick = viewModel::selectAllVisible) { Text(t("Select all")) }
                        TextButton(onClick = { moveDialogOpen = true }) { Text(t("Move")) }
                        TextButton(onClick = {
                            viewModel.deleteSelected()
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = movedToTrashText,
                                    actionLabel = undoText,
                                    duration = SnackbarDuration.Long,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.undoDelete()
                                } else {
                                    viewModel.commitDelete()
                                }
                            }
                        }) { Text(t("Delete")) }
                    },
                )
            }
            AnimatedVisibility(
                visible = !selection.active,
                enter = fadeIn(tween(MotionTokens.NORMAL, easing = KoodoMotion.Emphasized)) +
                    slideInVertically(tween(MotionTokens.NORMAL)) { -it / 2 },
                exit = fadeOut(tween(MotionTokens.FAST)),
            ) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = {
                    Text(
                        t("Library"),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
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
                            Icon(painterResource(R.drawable.ic_import), contentDescription = t("Import"))
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
                            Icon(painterResource(R.drawable.ic_more), contentDescription = t("View"))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (shelf.viewGrid) t("Switch to list view") else t("Switch to grid view")) },
                                onClick = { viewModel.setViewGrid(!shelf.viewGrid); menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text(t("Sort by")) },
                                onClick = { menuOpen = false; sortSheetOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text(if (shelf.favoritesOnly) t("Show all books") else t("Show favorites")) },
                                onClick = { viewModel.setFavoritesOnly(!shelf.favoritesOnly); menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text(t("New shelf")) },
                                onClick = { menuOpen = false; newShelfOpen = true },
                            )
                            if (shelf.activeShelf != null) {
                                DropdownMenuItem(
                                    text = { Text(t("Delete")) },
                                    onClick = { viewModel.deleteActiveShelf(); menuOpen = false },
                                )
                            }
                        }
                    }
                },
            )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            val keyboard = LocalSoftwareKeyboardController.current
            TextField(
                value = shelf.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = SpaceTokens.SCREEN_HORIZONTAL.dp,
                        vertical = SpaceTokens.sm.dp,
                    ),
                singleLine = true,
                trailingIcon = {
                    if (shelf.searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                viewModel.setSearchQuery("")
                                keyboard?.hide()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = t("Clear"),
                            )
                        }
                    }
                },
                placeholder = { Text(t("Search")) },
                leadingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_search),
                        contentDescription = t("Search"),
                    )
                },
                shape = ShellChrome.searchCorner,
                colors = ShellChrome.searchFieldColors(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = SpaceTokens.SCREEN_HORIZONTAL.dp),
                horizontalArrangement = Arrangement.spacedBy(SpaceTokens.sm.dp),
            ) {
                FilterChip(
                    selected = shelf.activeShelf == null,
                    onClick = { viewModel.setActiveShelf(null) },
                    label = { Text(t("All books")) },
                    colors = ShellChrome.shelfChipColors(),
                    border = ShellChrome.shelfChipBorder(shelf.activeShelf == null),
                    shape = CircleShape,
                )
                shelf.shelves.keys.forEach { title ->
                    FilterChip(
                        selected = shelf.activeShelf == title,
                        onClick = { viewModel.setActiveShelf(title) },
                        label = { Text(title) },
                        colors = ShellChrome.shelfChipColors(),
                        border = ShellChrome.shelfChipBorder(shelf.activeShelf == title),
                        shape = CircleShape,
                    )
                }
            }
            val running = importState as? ImportState.Running
            if (running != null && running.total > 0) {
                LinearProgressIndicator(
                    progress = running.done.toFloat() / running.total,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val finished = importState as? ImportState.Finished
            val importFailedText = t("Import failed")
            val importText = t("Import")
            val duplicateText = t("Duplicate")
            val viewDetailsText = t("View details")
            LaunchedEffect(statusMessage) {
                val message = statusMessage ?: return@LaunchedEffect
                snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
                viewModel.consumeStatusMessage()
            }
            LaunchedEffect(finished) {
                val state = finished ?: return@LaunchedEffect
                val message = buildString {
                    if (state.error != null) {
                        append(importFailedText).append(": ").append(state.error)
                    } else {
                        append(importText).append(' ').append(state.imported)
                        if (state.duplicates > 0) append(" · ").append(duplicateText).append(' ').append(state.duplicates)
                        if (state.failed > 0) append(" · ").append(importFailedText).append(' ').append(state.failed)
                    }
                }
                val needsDetail = state.error != null || state.failed > 0
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = if (needsDetail) viewDetailsText else null,
                    duration = SnackbarDuration.Long,
                )
                if (needsDetail && result == SnackbarResult.ActionPerformed) {
                    showFailureDialog = true
                }
            }
            if (libraryLoading) {
                LibraryLoadingSkeleton()
            } else if (loadError != null) {
                ErrorState(
                    message = t("Can't load library"),
                    onRetry = viewModel::retryLoad,
                    retryText = t("Retry"),
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (books.isEmpty()) {
                when {
                    shelf.searchQuery.isNotEmpty() ->
                        EmptyLibraryHint(message = t("No matching books"))
                    shelf.favoritesOnly ->
                        EmptyLibraryHint(message = t("No favorite books"))
                    shelf.activeShelf != null ->
                        EmptyLibraryHint(message = t("This shelf is empty"))
                    else -> EmptyLibraryHint(
                        message = t("Library is empty"),
                        showImport = true,
                        onImportClick = { filesLauncher.launch(arrayOf("*/*")) },
                    )
                }
            } else if (shelf.viewGrid) {                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(SpaceTokens.SCREEN_HORIZONTAL.dp),
                    horizontalArrangement = Arrangement.spacedBy(SpaceTokens.GRID_GAP_HORIZONTAL.dp),
                    verticalArrangement = Arrangement.spacedBy(SpaceTokens.GRID_GAP_VERTICAL.dp),
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
                            onAddToShelf = { shelfPickKey = book.key },
                            onShowDetails = {
                                metadataViewModel.reset()
                                metadataBookKey = book.key
                            },
                            selectionActive = selection.active,
                            selected = book.key in selection.selected,
                            onToggleSelection = { viewModel.toggleSelection(book.key) },
                            onClick = {
                                viewModel.markRecentOpened(book.key)
                                onOpenBook(book.key)
                            },
                            modifier = Modifier.pointerInput(book.key, selection.active) {
                                if (selection.active) return@pointerInput
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
                    contentPadding = PaddingValues(SpaceTokens.SCREEN_HORIZONTAL.dp),
                    verticalArrangement = Arrangement.spacedBy(SpaceTokens.md.dp),
                ) {
                    items(books, key = { it.key }) { book ->
                        ShellSettingsGroup {
                            BookListRow(
                                book = book,
                                isFavorite = book.key in shelf.favorites,
                                coverVersion = coverVersion,
                                onToggleFavorite = { viewModel.toggleFavorite(book.key) },
                                onMoveToTrash = { viewModel.moveToTrash(book.key) },
                                onAddToShelf = { shelfPickKey = book.key },
                                onShowDetails = {
                                    metadataViewModel.reset()
                                    metadataBookKey = book.key
                                },
                                selectionActive = selection.active,
                                selected = book.key in selection.selected,
                                onToggleSelection = { viewModel.toggleSelection(book.key) },
                                onClick = {
                                    viewModel.markRecentOpened(book.key)
                                    onOpenBook(book.key)
                                },
                                modifier = Modifier.padding(
                                    horizontal = SpaceTokens.md.dp,
                                    vertical = SpaceTokens.sm.dp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(metadataState) {
        if (metadataState is BookMetadataUiState.Saved) {
            metadataBookKey = null
            metadataViewModel.reset()
        }
    }
    val metadataBook = books.firstOrNull { it.key == metadataBookKey }
    if (metadataBook != null) {
        BookMetadataDialog(
            book = metadataBook,
            state = metadataState,
            onSave = { draft -> metadataViewModel.save(metadataBook.key, draft) },
            onDismiss = {
                metadataBookKey = null
                metadataViewModel.reset()
            },
        )
    }
    if (newShelfOpen) {
        AlertDialog(
            onDismissRequest = { newShelfOpen = false },
            title = { Text(t("New shelf")) },
            text = {
                OutlinedTextField(
                    value = newShelfTitle,
                    onValueChange = { newShelfTitle = it },
                    singleLine = true,
                    label = { Text(t("Shelf")) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val title = newShelfTitle.trim()
                        if (title.isNotEmpty()) viewModel.createShelf(title)
                        newShelfTitle = ""
                        newShelfOpen = false
                    },
                ) { Text(t("Confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { newShelfOpen = false }) { Text(t("Cancel")) }
            },
        )
    }
    val pickKey = shelfPickKey
    if (pickKey != null) {
        AlertDialog(
            onDismissRequest = { shelfPickKey = null },
            title = { Text(t("Add to shelf")) },
            text = {
                Column {
                    if (shelf.shelves.isEmpty()) {
                        Text(t("New shelf"))
                    }
                    shelf.shelves.keys.forEach { title ->
                        TextButton(
                            onClick = {
                                viewModel.addToShelf(title, pickKey)
                                shelfPickKey = null
                            },
                        ) { Text(title) }
                    }
                    TextButton(
                        onClick = {
                            shelfPickKey = null
                            newShelfOpen = true
                        },
                    ) { Text(t("New shelf")) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { shelfPickKey = null }) { Text(t("Cancel")) }
            },
        )
    }
    if (moveDialogOpen) {
        AlertDialog(
            onDismissRequest = { moveDialogOpen = false },
            title = { Text(t("Move to folder")) },
            text = {
                OutlinedTextField(
                    value = moveFolderName,
                    onValueChange = { moveFolderName = it },
                    singleLine = true,
                    label = { Text(t("Folder")) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = moveFolderName.trim().trim('/', '\\')
                        if (name.isNotEmpty() && !name.contains("..")) {
                            viewModel.moveSelectedTo(
                                java.io.File(java.io.File(context.filesDir, "books"), name),
                            )
                        }
                        moveFolderName = ""
                        moveDialogOpen = false
                    },
                ) { Text(t("Confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { moveDialogOpen = false }) { Text(t("Cancel")) }
            },
        )
    }
    if (sortSheetOpen) {
        SortSheet(
            current = shelf.sort,
            onSelectField = {
                viewModel.setSortField(it)
                sortSheetOpen = false
            },
            onToggleDirection = viewModel::toggleSortDirection,
            onDismiss = { sortSheetOpen = false },
        )
    }
    if (showFailureDialog) {
        val state = importState as? ImportState.Finished
        AlertDialog(
            onDismissRequest = { showFailureDialog = false },
            title = { Text(t("Import failed")) },
            text = {
                Text(
                    state?.error ?: buildString {
                        append(t("Import failed"))
                        append(' ').append(state?.failed ?: 0)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { showFailureDialog = false }) { Text(t("Confirm")) }
            },
        )
    }
}

/** Sort field picker sheet: tap the active field to flip direction, tap
 *  another field to switch and close. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(
    current: SortSpec,
    onSelectField: (SortField) -> Unit,
    onToggleDirection: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SortField.entries.forEach { field ->
            val selected = current.field == field
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = {
                            if (selected) onToggleDirection() else onSelectField(field)
                        },
                    )
                    .padding(horizontal = SpaceTokens.lg.dp, vertical = SpaceTokens.md.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Text(
                    text = t(field.labelKey),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = SpaceTokens.md.dp),
                )
                if (selected) {
                    Icon(
                        imageVector = if (current.ascending) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (current.ascending) t("Ascending") else t("Descending"),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryHint(
    message: String,
    showImport: Boolean = false,
    onImportClick: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(R.drawable.ic_import),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = SpaceTokens.md.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (showImport) {
                Button(
                    onClick = onImportClick,
                    modifier = Modifier.padding(top = SpaceTokens.md.dp),
                ) {
                    Text(t("Import"))
                }
            }
        }
    }
}

/** Language names are shown in their own language by convention (untranslated). */
internal fun languageLabel(code: String): String = when (code) {
    I18nState.SYSTEM -> "System"
    "en" -> "English"
    "zh-CN" -> "中文"
    "zh-TW" -> "繁體中文"
    "ja" -> "日本語"
    "ko" -> "한국어"
    "es" -> "Español"
    "fr" -> "Français"
    "de" -> "Deutsch"
    "ru" -> "Русский"
    "pt" -> "Português"
    "it" -> "Italiano"
    else -> code
}
