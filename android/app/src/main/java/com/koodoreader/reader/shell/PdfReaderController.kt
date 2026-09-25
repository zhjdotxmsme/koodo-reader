package com.koodoreader.reader.shell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.koodoreader.engine.pdf.OutlineResolver
import com.koodoreader.engine.pdf.PdfHostBridge
import com.koodoreader.engine.pdf.PdfSearchEngine
import com.koodoreader.engine.pdf.PdfSnapshotExporter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the native PDF reader screen (P3) shows.
 *
 * @property opening the document is being loaded (bootstrap + `getDocument`)
 * @property rendering a page raster is in flight
 * @property page 1-based current page
 * @property pageImage the rasterised page, drawn by Compose
 * @property outline flattened outline rows (see [OutlineResolver.flatten])
 * @property hits search results, in `(page, y, x)` order
 * @property hitIndex index into [hits]; -1 when there is no result
 * @property passwordRequired the document is encrypted and needs a password
 * @property passwordError the last password attempt was rejected
 * @property error fatal, user-visible failure (engine message)
 */
data class PdfReaderUiState(
    val opening: Boolean = true,
    val rendering: Boolean = false,
    val page: Int = 1,
    val pageCount: Int = 0,
    val zoom: Float = 1f,
    val pageImage: ImageBitmap? = null,
    val outline: List<OutlineResolver.Row> = emptyList(),
    val outlineLoading: Boolean = false,
    val outlineLoaded: Boolean = false,
    val searching: Boolean = false,
    val searchQuery: String = "",
    val hits: List<PdfSearchEngine.Hit> = emptyList(),
    val hitIndex: Int = -1,
    val passwordRequired: Boolean = false,
    val passwordError: Boolean = false,
    val exporting: Boolean = false,
    val error: String? = null,
) {
    val zoomPercent: Int get() = Math.round(zoom * 100)
}

/**
 * Drives [PdfHostBridge] for [NativePdfScreen].
 *
 * Why a hand-rolled state holder instead of a `ViewModel`: the bridge owns a
 * `WebView` (and therefore a `Context`), and the screen already gets its book
 * from [LibraryViewModel]. This class only translates bridge calls into Compose
 * state.
 *
 * Threading: every bridge call blocks on a JS round trip, so all of them run on
 * [Dispatchers.IO]; state is written back on the caller's (main) dispatcher.
 */
class PdfReaderController(
    private val bridge: PdfHostBridge,
    private val exporter: PdfSnapshotExporter,
    private val pdfFile: File,
    private val pdfUrl: String,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    /**
     * Engine bring-up, run once before the first [PdfHostBridge.open]. The
     * bridge interface has no such call because a pure-JVM host has nothing to
     * boot; the Android host passes `PdfJsHostBridge::bootstrap`.
     */
    private val bootstrap: () -> Unit = {},
    private val engine: PdfSearchEngine = PdfSearchEngine(),
    private val decode: (ByteArray) -> ImageBitmap? = { bytes ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    },
) {

    var state by mutableStateOf(PdfReaderUiState())
        private set

    /** The engine page loads once per screen; retried only on failure. */
    private var bootstrapped = false

    /** Width in px the page is rendered against, from the layout. */
    private var viewportPx = 0

    private var renderJob: Job? = null

    // ── lifecycle ────────────────────────────────────────────────────────────

    /** Bootstrap the engine, open [pdfUrl] and render page 1. */
    fun open(password: String? = null) {
        state = state.copy(opening = true, passwordRequired = false, passwordError = false)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!bootstrapped) {
                        bootstrap()
                        bootstrapped = true
                    }
                    bridge.open(pdfUrl, password)
                }
            }
            result.onSuccess { info ->
                state = state.copy(opening = false, pageCount = info.pageCount, page = 1, error = null)
                loadOutline()
                requestRender(force = true)
            }.onFailure { e ->
                val message = e.message ?: e.toString()
                // pdf.js raises a password error for both "no password given"
                // and "incorrect password", so the host asks and retries.
                if (message.contains("password", ignoreCase = true)) {
                    state = state.copy(
                        opening = false,
                        passwordRequired = true,
                        passwordError = password != null,
                    )
                } else {
                    state = state.copy(opening = false, error = message)
                }
            }
        }
    }

    fun close() {
        renderJob?.cancel()
        runCatching { bridge.close() }
    }

    // ── paging / zoom ────────────────────────────────────────────────────────

    /** Report the layout width (px) the page is drawn into. */
    fun onViewportWidth(px: Int) {
        if (px <= 0 || px == viewportPx) return
        viewportPx = px
        requestRender()
    }

    fun goTo(page: Int) {
        if (state.pageCount <= 0) return
        val target = page.coerceIn(1, state.pageCount)
        if (target == state.page) return
        state = state.copy(page = target)
        requestRender()
    }

    fun nextPage() = goTo(state.page + 1)

    fun previousPage() = goTo(state.page - 1)

    fun zoomIn() = setZoom(state.zoom * ZOOM_STEP)

    fun zoomOut() = setZoom(state.zoom / ZOOM_STEP)

    private fun setZoom(zoom: Float) {
        val clamped = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (clamped == state.zoom) return
        state = state.copy(zoom = clamped)
        requestRender()
    }

    /** Rasterise the current page at `viewport × zoom`. */
    fun requestRender(force: Boolean = false) {
        if (state.pageCount <= 0 || viewportPx <= 0) return
        val page = state.page
        val target = (viewportPx * state.zoom).toInt().coerceIn(MIN_RENDER_PX, MAX_RENDER_PX)
        if (!force && page == renderedPage && target == renderedWidth) return
        renderedPage = page
        renderedWidth = target
        state = state.copy(rendering = true)
        renderJob?.cancel()
        renderJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { decode(bridge.renderPage(page, target)) }.getOrNull()
            }
            // A newer page/zoom superseded this render while it was in flight.
            if (page != state.page || target != renderedWidth) return@launch
            state = state.copy(
                rendering = false,
                pageImage = bitmap ?: state.pageImage,
                error = if (bitmap == null) "render failed (page $page)" else state.error,
            )
        }
    }

    private var renderedPage = -1
    private var renderedWidth = -1

    // ── outline ──────────────────────────────────────────────────────────────

    fun loadOutline() {
        if (state.outlineLoading || state.outlineLoaded || state.pageCount <= 0) return
        state = state.copy(outlineLoading = true)
        scope.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching { OutlineResolver.flatten(OutlineResolver.resolve(bridge.outline())) }
                    .getOrDefault(emptyList())
            }
            state = state.copy(outlineLoading = false, outlineLoaded = true, outline = rows)
        }
    }

    /** Jump to an outline row: no-op for entries the engine could not resolve. */
    fun openOutlineRow(row: OutlineResolver.Row) {
        if (row.entry.pageNumber <= 0) return
        goTo(row.entry.pageNumber)
    }

    // ── search ───────────────────────────────────────────────────────────────

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            state = state.copy(searchQuery = "", hits = emptyList(), hitIndex = -1)
            return
        }
        state = state.copy(searching = true, searchQuery = query)
        scope.launch {
            val hits = withContext(Dispatchers.IO) {
                runCatching { engine.parseHits(bridge.search(trimmed), trimmed) }
                    .getOrDefault(emptyList())
            }
            state = state.copy(
                searching = false,
                hits = hits,
                hitIndex = if (hits.isEmpty()) -1 else 0,
            )
            if (hits.isNotEmpty()) goTo(hits[0].pageNumber)
        }
    }

    /** Advance to the next/previous hit, wrapping (see [PdfSearchEngine.cycleTo]). */
    fun cycleHit(direction: Int) {
        if (state.hits.isEmpty()) return
        val index = engine.cycleTo(state.hits, state.hitIndex, direction)
        if (index < 0) return
        state = state.copy(hitIndex = index)
        goTo(state.hits[index].pageNumber)
    }

    /** Jump to a specific result row (the dialog's list). */
    fun jumpToHit(index: Int) {
        val hit = state.hits.getOrNull(index) ?: return
        state = state.copy(hitIndex = index)
        goTo(hit.pageNumber)
    }

    // ── snapshot export ──────────────────────────────────────────────────────

    /**
     * Rasterise the current page with the framework `PdfRenderer` (zero `.so`
     * cost; see [PdfSnapshotExporter]) into `cacheDir/pdf-shots/` and hand the
     * file to [onReady] so the screen can share it.
     */
    fun exportCurrentPage(onReady: (File) -> Unit) {
        if (state.exporting || state.pageCount <= 0) return
        val page = state.page
        state = state.copy(exporting = true)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val spec = PdfSnapshotExporter.Spec(targetWidthPx = EXPORT_WIDTH_PX)
                    val exported = exporter.export(pdfFile.path, page, spec)
                    val dir = File(cacheDir, SNAPSHOT_DIR)
                    if (!dir.isDirectory && !dir.mkdirs()) {
                        throw IllegalStateException("cannot create ${dir.path}")
                    }
                    val out = File(
                        dir,
                        ReaderFiles.snapshotName(
                            bookKey = pdfFile.nameWithoutExtension,
                            page = page,
                            extension = PdfSnapshotExporter.extension(spec.format),
                        ),
                    )
                    out.writeBytes(exported.data)
                    out
                }
            }
            state = state.copy(exporting = false)
            result.onSuccess(onReady).onFailure { e ->
                state = state.copy(error = e.message ?: e.toString())
            }
        }
    }

    fun dismissError() {
        state = state.copy(error = null)
    }

    /**
     * Rasterise [page] for OCR at a fixed, larger width than the on-screen pass.
     *
     * Runs on IO because it blocks on a pdf.js round trip; the caller (the OCR
     * pass) then hands the bitmap to `feature:ocr`. Returns null when the engine
     * refuses the page — the indexer counts it as a failed page rather than
     * aborting the pass.
     */
    suspend fun rasterForOcr(page: Int, widthPx: Int = PdfOcrController.RASTER_WIDTH_PX): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = bridge.renderPage(page, widthPx)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }

    companion object {
        const val SNAPSHOT_DIR = "pdf-shots"
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 4f
        const val ZOOM_STEP = 1.25f

        /** Render bounds: below ~240 px text is unreadable, above ~3000 px the bridge payload hurts. */
        const val MIN_RENDER_PX = 240
        const val MAX_RENDER_PX = 3000

        /** Share exports are 2× the longest page side (print-quality without bloating the share). */
        const val EXPORT_WIDTH_PX = 2048
    }
}
