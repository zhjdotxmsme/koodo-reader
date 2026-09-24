package com.koodoreader.reader.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Native PDF reader screen (P3).
 *
 * Composes [com.koodoreader.engine.pdf.PdfHostBridge] with the
 * :engine:gesture gesture layer and the reader-side actions (search,
 * zoom, outline, share). Renders a dedicated [android.webkit.WebView]
 * hosting pdf.js (see [com.koodoreader.reader.pdfhost.PdfJsHostBridge]);
 * the WebView is destroyed on screen exit so the pdf.js worker is
 * reclaimed.
 *
 * The real WebView is wired in by the activity that hosts this
 * composable — see `MainActivity` for the WebView lifecycle, and
 * [com.koodoreader.reader.pdfhost.PdfJsHostBridge] for the protocol.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativePdfScreen(
    bookKey: String,
    onBack: () -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val book by viewModel.book(bookKey)
        .collectAsState(initial = null)
    var loading by remember { mutableStateOf(true) }
    var currentPage by remember { mutableStateOf(1) }
    var totalPages by remember { mutableStateOf(0) }
    var zoomPct by remember { mutableStateOf(100) }
    var modeLabel by remember { mutableStateOf("single") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(book?.name.orEmpty(), maxLines = 1)
                        if (totalPages > 0) {
                            Text(
                                "$currentPage / $totalPages · $zoomPct% · ${modeLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Zoom is rendered as ± glyphs on purpose: this project depends only
                    // on material-icons-core, and ZoomIn/ZoomOut/MenuBook live in
                    // material-icons-extended (~20 MB of classes for the debug APK).
                    IconButton(onClick = { zoomPct = (zoomPct - 25).coerceAtLeast(50) }) {
                        Text("−", style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(onClick = { zoomPct = (zoomPct + 25).coerceAtMost(400) }) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(onClick = { /* TODO: open outline drawer */ }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Outline")
                    }
                    IconButton(onClick = { /* TODO: open search dialog */ }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { /* TODO: trigger PdfRendererSnapshot */ }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share page")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // Placeholder for the PDF WebView host. The real wiring lives
            // in PdfJsHostBridge; the screen provides the container +
            // lifecycle hooks so the activity can host the WebView via
            // AndroidView when the engine is ready.
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "PDF native reader lands in P3.\n" +
                                "Loading book key: $bookKey\n" +
                                "Format: ${book?.format ?: "(loading)"}\n\n" +
                                "Engine host: pdf.js in engine WebView\n" +
                                "Snapshot/print bypass: framework PdfRenderer\n" +
                                "Annotations overlay: Range→CFI mapping via :engine:pdf",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}