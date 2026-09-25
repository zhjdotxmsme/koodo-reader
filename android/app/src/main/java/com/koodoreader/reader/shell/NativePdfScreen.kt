package com.koodoreader.reader.shell

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.koodoreader.engine.pdf.OutlineResolver
import com.koodoreader.engine.pdf.PdfSearchEngine
import com.koodoreader.feature.ocr.OcrScript
import com.koodoreader.reader.pdfhost.PdfJsHostBridge
import com.koodoreader.reader.pdfhost.PdfRendererSnapshot
import java.io.File

/**
 * Native PDF reader screen (P3).
 *
 * Joins the pieces that already existed but were never connected:
 *  - [PdfJsHostBridge] hosts pdf.js in a `WebView` over the loopback asset
 *    server and rasterises one page per call (see `assets/pdfengine/engine.mjs`,
 *    tested by `scripts/test-pdfengine.js`);
 *  - Compose draws the returned bitmap, so paging and zoom are host-side;
 *  - the outline drawer, the search dialog and "share current page" are the
 *    three toolbar actions P3 owed.
 *
 * The engine `WebView` is attached at 1×1 and never shown. It must be attached
 * for pdf.js's worker and canvas rendering to run, but nothing of it is visible
 * — pages arrive as bitmaps. A consequence, stated rather than hidden: this
 * design has no DOM text layer, so PDF *annotation* capture is not reachable
 * (`engine.mjs` documents `selectedRect`/`paintHighlights` as boundaries).
 *
 * Host prerequisites: the activity must have started its `LocalAssetServer` and
 * published the book (`ReaderAssetHost.publish`). Without it the screen shows an
 * explicit unavailable state — never a blank page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativePdfScreen(
    bookKey: String,
    onBack: () -> Unit,
    assets: ReaderAssetHost = ReaderAssetHost.NONE,
    viewModel: LibraryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val book by viewModel.book(bookKey).collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    // Books live in filesDir/books/<key>.<ext> (ReaderFiles.resolveBookFile);
    // Room's recorded path is authoritative when present.
    val pdfFile = remember(book?.key, book?.path) {
        val b = book ?: return@remember null
        ReaderFiles.resolveBookFile(
            booksDir = File(context.filesDir, "books"),
            bookKey = b.key,
            format = b.format,
            recordedPath = b.path,
        )
    }
    val pdfUrl = remember(pdfFile, assets.available) {
        pdfFile?.let { if (assets.available) assets.publish(it) else null }
    }

    var showOutline by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showOcr by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // One engine WebView per book URL; the controller tears the document down on
    // dispose. Created during composition, which is the main thread.
    val bridge = remember(pdfUrl) {
        pdfUrl?.let { PdfJsHostBridge(context, assets.baseUrl) }
    }

    if (bridge == null || pdfUrl == null || pdfFile == null) {
        PdfUnavailableScreen(title = book?.name.orEmpty(), bookKey = bookKey, onBack = onBack)
        return
    }

    val controller = remember(bridge, pdfFile, pdfUrl) {
        PdfReaderController(
            bridge = bridge,
            exporter = PdfRendererSnapshot(pdfFile),
            pdfFile = pdfFile,
            pdfUrl = pdfUrl,
            cacheDir = context.cacheDir,
            scope = scope,
            bootstrap = { bridge.bootstrap() },
        )
    }
    val state = controller.state

    // OCR (P6): the scanned-page index. Script comes from the UI language because
    // `books` has no language column (documented in the completeness report §16).
    val i18n = LocalI18n.current
    val i18nLanguage by i18n.language.collectAsState()
    val ocrController = remember(bridge, pdfFile, i18nLanguage) {
        PdfOcrController(
            context = context.applicationContext,
            bookKey = pdfFile.nameWithoutExtension,
            script = OcrScript.forLanguageTag(i18nLanguage),
            raster = { page ->
                controller.rasterForOcr(page)?.let(PdfOcrController::pageImage)
            },
            scope = scope,
        )
    }

    LaunchedEffect(controller) { controller.open() }
    DisposableEffect(controller) {
        onDispose {
            controller.close()
            assets.release(pdfFile)
        }
    }
    DisposableEffect(ocrController) {
        onDispose { ocrController.close() }
    }

    state.error?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            controller.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(book?.name.orEmpty(), maxLines = 1)
                        if (state.pageCount > 0) {
                            Text(
                                "${state.page} / ${state.pageCount} · ${state.zoomPercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back"))
                    }
                },
                actions = {
                    // Zoom is rendered as ± glyphs on purpose: this project depends
                    // only on material-icons-core (material-icons-extended would add
                    // ~20 MB of classes to the debug APK).
                    IconButton(onClick = controller::zoomOut, enabled = state.pageCount > 0) {
                        Text("−", style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(onClick = controller::zoomIn, enabled = state.pageCount > 0) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(
                        onClick = {
                            controller.loadOutline()
                            showOutline = true
                        },
                        enabled = state.pageCount > 0,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = t("PDF outline"),
                        )
                    }
                    IconButton(onClick = { showSearch = true }, enabled = state.pageCount > 0) {
                        Icon(Icons.Filled.Search, contentDescription = t("Search in book"))
                    }
                    IconButton(
                        onClick = {
                            ocrController.refresh()
                            showOcr = true
                        },
                        enabled = state.pageCount > 0,
                    ) {
                        // Text glyph on purpose: material-icons-core has no OCR icon
                        // and material-icons-extended would add ~20 MB of classes.
                        Text(t("OCR"), style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(
                        onClick = {
                            controller.exportCurrentPage { file -> shareSnapshot(context, file) }
                        },
                        enabled = state.pageCount > 0 && !state.exporting,
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = t("Share"))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                // Tap zones: the outer quarters page back/forward. The middle is
                // deliberately inert so the P2 gesture layer can take it.
                .pointerInput(state.pageCount) {
                    detectTapGestures { offset ->
                        val width = size.width
                        when {
                            offset.x < width * 0.25f -> controller.previousPage()
                            offset.x > width * 0.75f -> controller.nextPage()
                            else -> Unit
                        }
                    }
                }
                .onSizeChanged {
                    containerSize = it
                    controller.onViewportWidth(it.width)
                },
        ) {
            // The engine WebView: attached (pdf.js needs a live view) but invisible.
            AndroidView(factory = { bridge.view }, modifier = Modifier.size(1.dp))

            when {
                state.opening -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(t("Loading"), modifier = Modifier.padding(top = 12.dp))
                }

                state.passwordRequired -> PasswordPrompt(
                    error = state.passwordError,
                    onSubmit = { password -> controller.open(password) },
                )

                else -> {
                    if (state.rendering && state.pageImage == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    state.pageImage?.let { image ->
                        // Zoom is applied to the drawn width: the raster itself is
                        // requested at `viewport × zoom`, so the image is sharp.
                        val drawnWidth = with(density) {
                            (containerSize.width * state.zoom).coerceAtLeast(1f).toDp()
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            Image(
                                bitmap = image,
                                contentDescription = null,
                                modifier = Modifier
                                    .width(drawnWidth)
                                    .align(Alignment.TopCenter),
                            )
                        }
                    }
                    PagerBar(
                        page = state.page,
                        pageCount = state.pageCount,
                        onPrevious = controller::previousPage,
                        onNext = controller::nextPage,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    if (showOutline) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showOutline = false },
            sheetState = sheetState,
        ) {
            OutlineList(
                rows = state.outline,
                loading = state.outlineLoading,
                onPick = { row ->
                    controller.openOutlineRow(row)
                    showOutline = false
                },
            )
        }
    }

    if (showSearch) {
        SearchDialog(
            initialQuery = state.searchQuery,
            hits = state.hits,
            hitIndex = state.hitIndex,
            searching = state.searching,
            onSearch = controller::search,
            onCycle = controller::cycleHit,
            onJump = controller::jumpToHit,
            onDismiss = { showSearch = false },
        )
    }

    if (showOcr) {
        PdfOcrDialog(
            state = ocrController.state,
            currentPage = state.page,
            pageCount = state.pageCount,
            onIndexPage = { page -> ocrController.index(page..page) },
            onIndexAll = { ocrController.index(1..state.pageCount) },
            onCancel = ocrController::cancel,
            onSearch = ocrController::search,
            onForget = ocrController::forget,
            onJump = { page ->
                controller.goTo(page)
                showOcr = false
            },
            onDismiss = { showOcr = false },
        )
    }
}

/**
 * Shown when the book file or the loopback origin is missing.
 *
 * The message is English-only on purpose: the Android locale catalogs are
 * generated from the desktop ones by `scripts/sync-locales-android.js --check`,
 * which CI enforces, so a native-only key cannot be added to just one side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfUnavailableScreen(title: String, bookKey: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (title.isEmpty()) bookKey else title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back"))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "The local PDF engine is unavailable for this book.\n" +
                    "(book key: $bookKey)\n\n" +
                    "The loopback asset server must be running and the book file must " +
                    "exist under files/books/.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PasswordPrompt(error: Boolean, onSubmit: (String) -> Unit) {
    var field by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(t("Password")) },
        text = {
            Column {
                Text(t("Enter password"))
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit(field) }),
                    isError = error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (error) {
                    Text(
                        t("Wrong password"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(field) }) { Text(t("Enter password")) }
        },
    )
}

/** Bottom pager: explicit prev/next, so paging never depends on tap zones. */
@Composable
private fun PagerBar(
    page: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 0) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPrevious, enabled = page > 1) {
                Text("‹ ${t("Previous page")}")
            }
            Text("$page / $pageCount", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onNext, enabled = page < pageCount) {
                Text("${t("Next page")} ›")
            }
        }
    }
}

@Composable
private fun OutlineList(
    rows: List<OutlineResolver.Row>,
    loading: Boolean,
    onPick: (OutlineResolver.Row) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = t("PDF outline"),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (rows.isEmpty()) {
            Text(
                text = t("PDF empty outline"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxHeight(0.8f)) {
            itemsIndexed(rows) { _, row ->
                val resolved = row.entry.pageNumber > 0
                TextButton(
                    onClick = { onPick(row) },
                    enabled = resolved,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (resolved) {
                            "${row.entry.title}  ·  ${row.entry.pageNumber}"
                        } else {
                            row.entry.title
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (row.depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (row.depth * 12).dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchDialog(
    initialQuery: String,
    hits: List<PdfSearchEngine.Hit>,
    hitIndex: Int,
    searching: Boolean,
    onSearch: (String) -> Unit,
    onCycle: (Int) -> Unit,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Search in book")) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(t("Search")) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    searching -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                    hits.isEmpty() && query.isNotBlank() -> Text(
                        t("No result found"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )

                    hits.isEmpty() -> Unit

                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${hitIndex + 1} / ${hits.size}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Row {
                                TextButton(onClick = { onCycle(-1) }) { Text("‹") }
                                TextButton(onClick = { onCycle(1) }) { Text("›") }
                            }
                        }
                        LazyColumn(modifier = Modifier.fillMaxHeight(0.5f)) {
                            itemsIndexed(hits) { index, hit ->
                                TextButton(
                                    onClick = { onJump(index) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = "[${hit.pageNumber}] ${hit.snippet}",
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (index == hitIndex) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSearch(query) }) { Text(t("Search")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("Close")) }
        },
    )
}

/** Hand the exported snapshot to the share sheet through the FileProvider. */
private fun shareSnapshot(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
    }.onFailure {
        Toast.makeText(context, it.message ?: "share failed", Toast.LENGTH_SHORT).show()
    }
}
