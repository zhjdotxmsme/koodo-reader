package com.koodoreader.reader.shell

import com.koodoreader.feature.ocr.OcrImage
import com.koodoreader.feature.ocr.OcrIndexReceipt
import com.koodoreader.feature.ocr.OcrRequest
import com.koodoreader.feature.ocr.OcrScript
import com.koodoreader.feature.ocr.OcrSearchRepository

/** Outcome of one scan-to-index pass over a page range. */
data class OcrIndexSummary(
    val indexed: Int,
    /** Pages the engine found no text on — a blank or pure-image page. */
    val empty: Int,
    val failed: Int,
    /** Non-null when the pass stopped early because the model could not be installed. */
    val modelUnavailableReason: String? = null,
) {
    val attempted: Int get() = indexed + empty + failed
    val stoppedEarly: Boolean get() = modelUnavailableReason != null
}

/**
 * The scan-to-index pass for the native PDF reader (P6 OCR).
 *
 * Pure Kotlin on purpose: the loop is the part that can be wrong (progress,
 * early stop, cancelled passes) and it is driven here by an injected
 * [OcrSearchRepository] and rasteriser, so `:app`'s JVM tests pin it without a
 * device, a WebView or Google Play services. The Android halves are
 * [PdfOcrController] (state machine) and the rasteriser in
 * [PdfReaderController].
 *
 * Behaviour that matters:
 *  - **never throws per page**: a page that cannot be rasterised or recognised
 *    is counted as failed and the pass continues (one bad page must not lose the
 *    rest of a manual indexing run);
 *  - **stops at the first model failure**: every later page would fail the same
 *    way, and on a 900-page scan that is minutes of pointless work;
 *  - **cancellation is cooperative**: the caller cancels the coroutine between
 *    pages, and the summary of what was already indexed is still returned by the
 *    caller's own bookkeeping (indexed rows are persisted per page).
 */
object PdfOcrIndexer {

    /**
     * @param pages inclusive 1-based page range to walk
     * @param raster returns the page image, or null when it cannot be produced
     * @param onProgress `(done, total)` after every page, so the dialog can show
     *   a determinate bar
     */
    suspend fun run(
        repository: OcrSearchRepository,
        bookKey: String,
        script: OcrScript,
        pages: IntRange,
        raster: suspend (Int) -> OcrImage?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): OcrIndexSummary {
        val total = pages.count()
        var done = 0
        var indexed = 0
        var empty = 0
        var failed = 0

        for (page in pages) {
            val image = raster(page)
            if (image == null) {
                failed++
            } else {
                when (val receipt = repository.indexPage(OcrRequest(bookKey, page, script), image)) {
                    is OcrIndexReceipt.Indexed -> indexed++
                    is OcrIndexReceipt.EmptyPage -> empty++
                    is OcrIndexReceipt.Failed -> failed++
                    is OcrIndexReceipt.ModelUnavailable -> {
                        done++
                        onProgress(done, total)
                        return OcrIndexSummary(
                            indexed = indexed,
                            empty = empty,
                            failed = failed,
                            modelUnavailableReason = receipt.reason,
                        )
                    }
                }
            }
            done++
            onProgress(done, total)
        }

        return OcrIndexSummary(indexed = indexed, empty = empty, failed = failed)
    }
}
