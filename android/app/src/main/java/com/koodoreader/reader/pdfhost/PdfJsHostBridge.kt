package com.koodoreader.reader.pdfhost

import android.content.Context
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import com.koodoreader.engine.pdf.CfiPdfMapper
import com.koodoreader.engine.pdf.PdfHostBridge
import com.koodoreader.engine.pdf.PdfLayerAnnotation
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebView-backed [PdfHostBridge] (P3).
 *
 * Loads the staged `assets/pdfengine/index.html` (a small bootstrap that
 * imports `pdf.mjs` + `pdf.worker.mjs` from the staged `assets/pdfengine/`
 * directory) into a dedicated WebView and exchanges JSON messages via
 * `window.__koodoPdf`. The Kotlin side uses `evaluateJavascript` and a
 * `JavascriptInterface` shim to:
 *   - call into the engine (`open`, `search`, `outline`, `selectedRect`);
 *   - receive events back (`window.__koodoPdf.onEvent(json)`).
 *
 * Why its own WebView, not the MainActivity one? The PDF reader needs the
 * pdf.js worker thread and a persistent document handle across rotations;
 * the MainActivity WebView is rebuilt with the React host and resetting
 * it would tear down the PDF state. The host must start
 * [com.koodoreader.reader.LocalAssetServer] before instantiating this
 * class so the staged assets are reachable over the loopback origin (the
 * document proxy's `fetch` uses absolute URLs).
 *
 * Thread model: calls hop to the WebView thread via `post` /
 * `evaluateJavascript` and block on a `CountDownLatch` for the JS result.
 * The host activity calls this from a coroutine on
 * `Dispatchers.IO`. We never block the UI thread.
 */
class PdfJsHostBridge(
    private val context: Context,
    private val baseUrl: String,
    private val eventSink: PdfEventSink = PdfEventSink.NONE,
) : PdfHostBridge {

    private val webView: WebView = WebView(context).apply { configure() }

    /** Pending JS promise handlers keyed by call id. */
    private val pending: MutableMap<String, PendingCall> = HashMap()

    init {
        webView.addJavascriptInterface(JsShim(), "__koodoPdfHost")
    }

    // ── PdfHostBridge ───────────────────────────────────────────────────────

    override fun open(pdfPath: String, password: String?): PdfHostBridge.OpenResult {
        val raw = runJs(
            method = "open",
            args = listOf(JSONObject.quote(pdfPath), JSONObject.quote(password ?: "")),
        )
        val o = JSONObject(raw)
        return PdfHostBridge.OpenResult(
            fingerprint = o.optString("fingerprint", ""),
            pageCount = o.optInt("pageCount", 0),
            pdfVersion = o.optString("pdfVersion", "1.7"),
            pageWidthPt = o.optDouble("pageWidthPt", 612.0).toFloat(),
            pageHeightPt = o.optDouble("pageHeightPt", 792.0).toFloat(),
        )
    }

    override fun renderPage(pageNumber: Int, targetWidthPx: Int): ByteArray {
        val raw = runJs(
            method = "renderPage",
            args = listOf(pageNumber.toString(), targetWidthPx.toString()),
        )
        // renderPage returns a base64 PNG string; the WebView side does the encode.
        return android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
    }

    override fun search(query: String): String =
        runJs(method = "search", args = listOf(JSONObject.quote(query)))

    override fun outline(): String = runJs(method = "outline", args = emptyList())

    override fun selectedRect(): CfiPdfMapper.PdfRange? {
        val raw = runJs(method = "selectedRect", args = emptyList())
        if (raw.isBlank() || raw == "null") return null
        return try {
            val o = JSONObject(raw)
            CfiPdfMapper.PdfRange(
                fingerprint = o.optString("fingerprint", ""),
                page = o.optInt("pageNumber", 1),
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                width = o.optDouble("width", 0.0).toFloat(),
                height = o.optDouble("height", 0.0).toFloat(),
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun paintHighlights(ranges: List<CfiPdfMapper.PdfRange>) {
        val payload = PdfLayerAnnotation.serialiseRects(ranges)
        runJsFireAndForget("paintHighlights($payload)")
    }

    override fun close() {
        runCatching { runJsFireAndForget("close(") }
        webView.post { webView.destroy() }
    }

    // ── bootstrap ───────────────────────────────────────────────────────────

    /**
     * Load the engine bootstrap HTML; resume on completion. Call once
     * after the host server is reachable.
     */
    fun bootstrap() {
        val latch = CountDownLatch(1)
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                latch.countDown()
            }
        }
        webView.post { webView.loadUrl("$baseUrl/assets/pdfengine/index.html") }
        if (!latch.await(BOOTSTRAP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("PDF engine bootstrap timed out")
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun WebView.configure() {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.textZoom = 100
    }

    private fun runJs(method: String, args: List<String>): String {
        val callId = java.util.UUID.randomUUID().toString()
        val argList = args.joinToString(",")
        val pending = PendingCall().also { pending[callId] = it }
        webView.post {
            // The bootstrap installs a `call(method, args)` helper that
            // returns a Promise; the shim resolves it back to onResult.
            webView.evaluateJavascript(
                "window.__koodoPdf.call('$callId', '$method', [$argList])",
                null,
            )
        }
        if (!pending.latch.await(JS_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            this.pending.remove(callId)
            throw RuntimeException("PDF engine JS call '$method' timed out")
        }
        this.pending.remove(callId)
        if (pending.error != null) throw RuntimeException("PDF engine: ${pending.error}")
        return pending.payload
    }

    private fun runJsFireAndForget(snippet: String) {
        webView.post { webView.evaluateJavascript(snippet, null) }
    }

    /** Shim installed as `window.__koodoPdfHost`; the bootstrap HTML calls it. */
    private inner class JsShim {
        @JavascriptInterface
        fun onResult(callId: String, payload: String, error: String?) {
            val slot = pending[callId] ?: return
            slot.error = error
            slot.payload = payload
            slot.latch.countDown()
        }

        @JavascriptInterface
        fun onEvent(name: String, payload: String) {
            try {
                eventSink.onEvent(name, JSONObject(payload))
            } catch (e: Exception) {
                Log.w(TAG, "event $name parse failed: $payload", e)
            }
        }

        @JavascriptInterface
        fun onLog(level: String, message: String) {
            if ("error" == level) Log.w(TAG, message) else Log.i(TAG, message)
        }
    }

    /** Receives `window.__koodoPdf.onEvent` notifications from the engine. */
    interface PdfEventSink {
        fun onEvent(name: String, payload: JSONObject)
        object NONE : PdfEventSink {
            override fun onEvent(name: String, payload: JSONObject) = Unit
        }
    }

    private class PendingCall {
        val latch = CountDownLatch(1)
        var payload: String = ""
        var error: String? = null
    }

    companion object {
        private const val BOOTSTRAP_TIMEOUT_MS = 10_000L
        private const val JS_CALL_TIMEOUT_MS = 10_000L
        private const val TAG = "PdfJsHostBridge"

        @Suppress("unused")
        private val uiThreadCheck = Looper.getMainLooper()
    }
}