package com.koodoreader.reader.epubhost

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.ActionMode
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import com.koodoreader.core.designsystem.ThemeKind
import com.koodoreader.reader.pdfhost.PdfEngineReadyLatch
import com.koodoreader.reader.shell.ReaderFeatureState
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Visible-WebView host for vendored foliate-js (`assets/foliate/`).
 *
 * Thread model matches [com.koodoreader.reader.pdfhost.PdfJsHostBridge]: the
 * host activity may call from `Dispatchers.IO`, but every WebView mutation
 * hops through `webView.post`. Readiness is `host.onEvent('ready')`, not
 * `onPageFinished`.
 */
class FoliateHostBridge(
    context: Context,
    private val baseUrl: String,
    private val eventSink: EventSink = EventSink.NONE,
) {

    private val webView: WebView = object : WebView(context) {
        override fun startActionMode(callback: ActionMode.Callback?): ActionMode? = null
        override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? = null
    }.apply { configure() }

    /** The engine WebView — attach it to fill the reader pane. */
    val view: android.view.View get() = webView

    private val pending: MutableMap<String, PendingCall> = HashMap()
    private val readyLatch = PdfEngineReadyLatch()

    init {
        webView.addJavascriptInterface(JsShim(), "__koodoFoliateHost")
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    data class TocItem(
        val label: String,
        val href: String,
        val cfi: String,
        val depth: Int,
    )

    data class SearchHit(
        val excerpt: String,
        val href: String,
        val cfi: String,
    )

    data class OpenResult(
        val title: String,
        val sectionCount: Int,
    )

    data class SelectedRange(
        val cfi: String,
        val excerpt: String,
    )

    fun bootstrap() {
        val client = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                readyLatch.onPageFinished()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    readyLatch.fail("engine page error: ${error?.description}")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    readyLatch.fail("engine page HTTP ${errorResponse?.statusCode}")
                }
            }
        }
        val posted = webView.post {
            webView.webViewClient = client
            webView.loadUrl("$baseUrl/$BOOTSTRAP_PATH")
        }
        if (!posted) {
            throw RuntimeException("Foliate engine bootstrap failed: WebView already destroyed")
        }
        if (!readyLatch.await(BOOTSTRAP_TIMEOUT_MS)) {
            val reason = readyLatch.failure ?: "timed out waiting for ready event"
            throw RuntimeException("Foliate engine bootstrap failed ($baseUrl/$BOOTSTRAP_PATH): $reason")
        }
    }

    fun open(bookUrl: String, format: String): OpenResult {
        val raw = runJs(
            method = "open",
            args = listOf(JSONObject.quote(bookUrl), JSONObject.quote(format)),
            timeoutMs = OPEN_TIMEOUT_MS,
        )
        val o = JSONObject(raw)
        return OpenResult(
            title = o.optString("title", ""),
            sectionCount = o.optInt("sectionCount", 0),
        )
    }

    fun toc(): List<TocItem> {
        val raw = runJs(method = "toc", args = emptyList())
        val arr = JSONArray(if (raw.isBlank()) "[]" else raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TocItem(
                label = o.optString("label", ""),
                href = o.optString("href", ""),
                cfi = o.optString("cfi", ""),
                depth = o.optInt("depth", 0),
            )
        }
    }

    fun goTo(target: String) {
        if (target.isBlank()) return
        runJs(method = "goTo", args = listOf(JSONObject.quote(target)))
    }

    fun nextPage() {
        runJsFireAndForget(method = "next", args = emptyList())
    }

    fun prevPage() {
        runJsFireAndForget(method = "prev", args = emptyList())
    }

    fun search(query: String): List<SearchHit> {
        val raw = runJs(
            method = "search",
            args = listOf(JSONObject.quote(query)),
            timeoutMs = SEARCH_TIMEOUT_MS,
        )
        val arr = JSONArray(if (raw.isBlank()) "[]" else raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SearchHit(
                excerpt = o.optString("excerpt", ""),
                href = o.optString("href", ""),
                cfi = o.optString("cfi", ""),
            )
        }
    }

    fun setAppearance(bg: String, fg: String, fontSizePx: Int, fontFamily: String = "") {
        setAppearance(
            FoliateAppearance.opts(
                bg = bg,
                fg = fg,
                fontSizePx = fontSizePx,
                dark = false,
                fontFamily = fontFamily,
            ),
        )
    }

    fun setAppearance(opts: FoliateAppearance.Opts, bgArgb: Int? = null) {
        val kind = if (opts.colorScheme == "dark") ThemeKind.NIGHT else ThemeKind.DEFAULT
        val features = ReaderFeatureState(
            fontScale = 1.0f,
            lineHeightRatio = opts.lineHeight.toFloat(),
            marginHorizontalPx = opts.marginPx.toFloat(),
            fontKey = opts.fontFamily,
            scrolled = true,
        )
        // Keep this overload for leftover callers; NativeEpubScreen uses setViewSettings.
        setViewSettings(
            ReadestViewSettings.from(kind, features).copy(
                bg = opts.bg,
                fg = opts.fg,
                defaultFontSize = opts.fontSizePx / 1.25,
                isDarkMode = opts.colorScheme == "dark",
                colorScheme = opts.colorScheme,
            ),
            bgArgb,
        )
    }

    fun setViewSettings(snapshot: ReadestViewSettings.Snapshot, bgArgb: Int? = null) {
        if (bgArgb != null) {
            webView.post { webView.setBackgroundColor(bgArgb) }
        }
        val payload = JSONObject()
            .put("bg", snapshot.bg)
            .put("fg", snapshot.fg)
            .put("defaultFontSize", snapshot.defaultFontSize)
            .put("lineHeight", snapshot.lineHeight)
            .put("marginPx", snapshot.marginPx)
            .put("maxColumnCount", snapshot.maxColumnCount)
            .put("scrolled", snapshot.scrolled)
            .put("overrideColor", snapshot.overrideColor)
            .put("isDarkMode", snapshot.isDarkMode)
            .put("colorScheme", snapshot.colorScheme)
            .put("defaultFont", snapshot.defaultFont)
        runJs(method = "setViewSettings", args = listOf(payload.toString()))
    }

    fun selectedRange(): SelectedRange? {
        val raw = runJs(method = "selectedRange", args = emptyList())
        if (raw.isBlank() || raw == "null") return null
        return try {
            val o = JSONObject(raw)
            SelectedRange(
                cfi = o.optString("cfi", ""),
                excerpt = o.optString("excerpt", ""),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun addHighlight(cfi: String, colorCss: String) {
        if (cfi.isBlank()) return
        runJs(
            method = "addHighlight",
            args = listOf(JSONObject.quote(cfi), JSONObject.quote(colorCss)),
        )
    }

    fun removeHighlight(cfi: String) {
        if (cfi.isBlank()) return
        runJs(method = "removeHighlight", args = listOf(JSONObject.quote(cfi)))
    }

    fun close() {
        runJsFireAndForget(method = "close", args = emptyList())
        webView.post { webView.destroy() }
    }

    private fun WebView.configure() {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.textZoom = 100
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun runJs(method: String, args: List<String>, timeoutMs: Long = JS_CALL_TIMEOUT_MS): String {
        val callId = java.util.UUID.randomUUID().toString()
        val argList = args.joinToString(",")
        val pending = PendingCall().also { pending[callId] = it }
        webView.post {
            webView.evaluateJavascript(
                "window.__koodoFoliate.call('$callId', '$method', [$argList])",
                null,
            )
        }
        if (!pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            this.pending.remove(callId)
            throw RuntimeException("Foliate engine JS call '$method' timed out")
        }
        this.pending.remove(callId)
        pending.error?.let { throw RuntimeException("Foliate engine: $it") }
        return pending.payload
    }

    private fun runJsFireAndForget(method: String, args: List<String>) {
        val callId = java.util.UUID.randomUUID().toString()
        val argList = args.joinToString(",")
        webView.post {
            webView.evaluateJavascript(
                "window.__koodoFoliate && window.__koodoFoliate.call('$callId', '$method', [$argList])",
                null,
            )
        }
    }

    private inner class JsShim {
        @JavascriptInterface
        fun onResult(callId: String, payload: String, error: String?) {
            val slot = pending[callId] ?: return
            slot.error = FoliateJsResult.errorOrNull(error)
            slot.payload = payload
            slot.latch.countDown()
        }

        @JavascriptInterface
        fun onEvent(name: String, payload: String) {
            readyLatch.onHostEvent(name)
            try {
                eventSink.onEvent(name, JSONObject(if (payload.isBlank()) "{}" else payload))
            } catch (e: Exception) {
                Log.w(TAG, "event $name parse failed: $payload", e)
            }
        }

        @JavascriptInterface
        fun onLog(level: String, message: String) {
            if ("error" == level) Log.w(TAG, message) else Log.i(TAG, message)
        }
    }

    interface EventSink {
        fun onEvent(name: String, payload: JSONObject)
        object NONE : EventSink {
            override fun onEvent(name: String, payload: JSONObject) = Unit
        }
    }

    private class PendingCall {
        val latch = CountDownLatch(1)
        var payload: String = ""
        var error: String? = null
    }

    companion object {
        private const val BOOTSTRAP_TIMEOUT_MS = 20_000L
        private const val JS_CALL_TIMEOUT_MS = 15_000L
        private const val OPEN_TIMEOUT_MS = 60_000L
        private const val SEARCH_TIMEOUT_MS = 30_000L
        private const val BOOTSTRAP_PATH = "foliate/index.html"
        private const val TAG = "FoliateHostBridge"

        @Suppress("unused")
        private val uiThreadCheck = Looper.getMainLooper()
    }
}
