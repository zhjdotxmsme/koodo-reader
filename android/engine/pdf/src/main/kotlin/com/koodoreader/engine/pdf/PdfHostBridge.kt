package com.koodoreader.engine.pdf

/**
 * The Kotlin ↔ JavaScript bridge contract for the PDF engine WebView
 * (P3). The WebView side (see `pdfengine.html` shipped by
 * `scripts/stage-pdf-engine-assets.js`) loads `pdf.mjs` + `pdf.worker.mjs`
 * from the staged `assets/pdfengine/` directory and exposes a small JS
 * surface under `window.__koodoPdf`. This interface is what the Compose
 * host calls into.
 *
 * Wire format: every payload is a JSON string. We keep the wire JSON
 * minimal — the JVM module serialises/deserialises its own data classes
 * via `org.json` (the same library the Android host ships).
 *
 * Error model: every method either returns the documented type, or
 * throws an exception whose message is human-readable. The host surfaces
 * the message in a toast; the engine never swallows errors.
 */
interface PdfHostBridge {

    /**
     * Open a PDF file. Returns the document fingerprint, page count and
     * PDF version. The WebView side calls `pdfjs.getDocument(...)`,
     * resolves the `Promise`, and serialises the result.
     *
     * @param pdfPath absolute path on the device filesystem.
     * @param password null when the file is not password-protected; the
     *   [PasswordGate] supplies candidates otherwise.
     */
    fun open(pdfPath: String, password: String? = null): OpenResult

    /**
     * Render [pageNumber] (1-based) at the given size. Returns the PNG
     * bytes. The Compose layer may call this from a coroutine; the host is
     * responsible for marshalling to the WebView thread.
     *
     * @param targetWidthPx the requested render width; the engine clamps
     *   to a safe range.
     */
    fun renderPage(pageNumber: Int, targetWidthPx: Int): ByteArray

    /**
     * Run a forward-only text search. Returns the JSON shape consumed by
     * [PdfSearchEngine.parseHits]. Empty string when the document is not
     * open.
     */
    fun search(query: String): String

    /**
     * Resolve the outline (bookmarks) tree. Returns the JSON consumed by
     * [OutlineResolver.resolve]. Empty when the document has no
     * `/Outlines`.
     */
    fun outline(): String

    /**
     * Resolve the rectangle the user just selected on the page. The
     * WebView side turns the DOM Range into a PDF bbox via pdf.js's
     * `getTextContent({includeMarkedContent:false,disableNormalization:false,
     * disableCombineTextItems:false})` + the `transform` matrix. The
     * result is a JSON object with `pageNumber`, `x`, `y`, `width`,
     * `height` in PDF user space.
     */
    fun selectedRect(): CfiPdfMapper.PdfRange?

    /**
     * Repaint the highlight layer with [ranges] for the currently-visible
     * pages. Called after a Room write so the highlight is visible
     * without reloading the document.
     */
    fun paintHighlights(ranges: List<CfiPdfMapper.PdfRange>)

    /**
     * Dispose: tear down the WebView's pdf.js document proxy and release
     * worker threads. Call on `onCleared` / screen detach.
     */
    fun close()

    data class OpenResult(
        val fingerprint: String,
        val pageCount: Int,
        val pdfVersion: String,
        val pageWidthPt: Float,
        val pageHeightPt: Float,
    )

    companion object {
        /** JavaScript global the WebView installs; mirrored in pdfengine.html. */
        const val JS_GLOBAL = "__koodoPdf"

        /** Helper that builds the document load call the WebView expects. */
        fun loadScript(pdfPath: String, password: String?, baseUrl: String): String {
            val payload = org.json.JSONObject()
                .put("path", pdfPath)
                .put("password", password ?: "")
                .put("baseUrl", baseUrl)
            return "window.$JS_GLOBAL.open(${payload})"
        }
    }
}