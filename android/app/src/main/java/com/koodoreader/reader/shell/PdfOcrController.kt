package com.koodoreader.reader.shell

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.koodoreader.feature.ocr.OcrEngine
import com.koodoreader.feature.ocr.OcrHit
import com.koodoreader.feature.ocr.OcrImage
import com.koodoreader.feature.ocr.OcrScript
import com.koodoreader.feature.ocr.OcrSearchRepository
import com.koodoreader.feature.ocr.platform.MlKitOcrProvider
import com.koodoreader.feature.ocr.platform.MlKitPageImage
import com.koodoreader.feature.ocr.platform.OcrWiring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the PDF reader's OCR sheet shows.
 *
 * @property indexedPages pages of this book already in the OCR index
 * @property running a scan-to-index pass is in flight
 * @property done/total page progress of that pass (0/0 when idle)
 * @property summary result of the last finished pass
 * @property modelError set when the pass stopped because the ML Kit model could
 *   not be installed (offline / no Play services); the UI pairs it with a key
 * @property hits OCR search results for [query]
 */
data class PdfOcrUiState(
    val indexedPages: Int = 0,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val summary: OcrIndexSummary? = null,
    val modelError: String? = null,
    val searching: Boolean = false,
    val query: String = "",
    val hits: List<OcrHit> = emptyList(),
)

/**
 * OCR glue for the native PDF reader (P6 OCR ⇄ P3 reader).
 *
 * The PDF engine already rasterises pages; this class feeds those rasters to
 * `feature:ocr` and exposes the result as Compose state. Everything OCR-side is
 * reused as delivered — [OcrSearchRepository] owns indexing, the on-demand model
 * install (through `MlKitModelDownloader`) and scoring. This class adds only
 * threading, progress and the app-side raster.
 *
 * Script choice: `books` has no language column, so the *UI* language picks the
 * ML Kit script (`OcrScript.forLanguageTag`). A Latin UI reading a Chinese scan
 * therefore needs the language switched first — a real limitation recorded in
 * docs/android-completeness-2026-09-24.md §16 rather than hidden behind a guess.
 */
class PdfOcrController(
    context: Context,
    private val bookKey: String,
    private val script: OcrScript,
    private val raster: suspend (Int) -> OcrImage?,
    private val scope: CoroutineScope,
    private val engine: OcrEngine = MlKitOcrProvider(),
    private val repository: OcrSearchRepository = OcrSearchRepository(
        engine = engine,
        store = OcrWiring.store(context),
        downloader = OcrWiring.downloader(context),
    ),
) {

    var state by mutableStateOf(PdfOcrUiState())
        private set

    private var indexJob: Job? = null
    private var searchJob: Job? = null

    /** Refresh the "pages indexed" counter (cheap; call when opening the sheet). */
    fun refresh() {
        scope.launch {
            val count = withContext(Dispatchers.IO) { repository.indexedPages(bookKey) }
            state = state.copy(indexedPages = count)
        }
    }

    /**
     * Index [pages] (inclusive, 1-based). One pass at a time: a second call while
     * a pass runs is ignored, so a double tap cannot index twice.
     */
    fun index(pages: IntRange) {
        if (state.running) return
        state = state.copy(
            running = true,
            done = 0,
            total = pages.count(),
            summary = null,
            modelError = null,
        )
        indexJob = scope.launch {
            // The loop stays on the main dispatcher (state writes stay ordered);
            // the rasteriser hops to IO itself, and Room / ML Kit suspending calls
            // own their own threads.
            val summary = PdfOcrIndexer.run(
                repository = repository,
                bookKey = bookKey,
                script = script,
                pages = pages,
                raster = raster,
                onProgress = { done, total -> state = state.copy(done = done, total = total) },
            )
            val indexed = withContext(Dispatchers.IO) { repository.indexedPages(bookKey) }
            state = state.copy(
                running = false,
                indexedPages = indexed,
                summary = summary,
                modelError = summary.modelUnavailableReason,
            )
        }
    }

    fun cancel() {
        indexJob?.cancel()
        indexJob = null
        state = state.copy(running = false)
    }

    /** Search the OCR index of this book (never the pdf.js text layer). */
    fun search(query: String) {
        val trimmed = query.trim()
        state = state.copy(query = query)
        if (trimmed.isEmpty()) {
            state = state.copy(hits = emptyList(), searching = false)
            return
        }
        searchJob?.cancel()
        state = state.copy(searching = true)
        searchJob = scope.launch {
            val hits = withContext(Dispatchers.IO) {
                runCatching { repository.search(trimmed, bookKey = bookKey) }
                    .getOrDefault(emptyList())
            }
            state = state.copy(searching = false, hits = hits)
        }
    }

    /** Drop this book's OCR index. */
    fun forget() {
        scope.launch {
            withContext(Dispatchers.IO) { repository.forgetBook(bookKey) }
            state = state.copy(indexedPages = 0, hits = emptyList(), summary = null, modelError = null)
        }
    }

    fun close() {
        indexJob?.cancel()
        searchJob?.cancel()
        runCatching { engine.close() }
    }

    companion object {
        /**
         * Width the page is rasterised at for OCR. ML Kit wants noticeably more
         * resolution than the screen pass (small CJK glyphs are the hard case);
         * ~1600 px keeps one page around 10 MB of ARGB.
         */
        const val RASTER_WIDTH_PX = 1600

        /** Wrap an Android bitmap as an [OcrImage] for ML Kit. */
        fun pageImage(bitmap: Bitmap): OcrImage = MlKitPageImage(bitmap)
    }
}
