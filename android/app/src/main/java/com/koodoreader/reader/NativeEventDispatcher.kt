package com.koodoreader.reader

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Consumes the engine's `window.ReactNativeWebView.postMessage` events that
 * the reading engine (kookit) emits from inside the book iframe.
 *
 * PROTOCOL MIRROR — single source of truth on the JS side:
 *   `src/utils/android/nativeBridge.js` (+ Jest tests pin the names).
 * Event names and `__koodoNative` hook names below MUST stay in sync with it.
 *
 * Decoded engine semantics (kookit.min.js, verified):
 *   "right" → next page, "left" → prev page; in "sliding" mode the engine
 *   turns the page itself and does NOT emit the event, so a received
 *   left/right/swipe event always means: the shell must turn the page.
 *
 * Native responsibilities:
 *   - page turns: re-inject `window.__koodoNative.nextPage()/prevPage()`
 *     (registered by PopupMenu → live rendition.next()/prev())
 *   - selection: native menu (copy/highlight/note/share/search); copy, share,
 *     search are fully native, highlight & note delegate to the app's own
 *     selection menu via `window.__koodoNative.openSelectionMenu()`
 *     (reuses the app's proven highlight/note wiring)
 *   - view-image: full-screen dialog (read-only WebView, pinch zoom free)
 *   - link-clicked: https/mailto → system browser; footnote → text dialog
 *   - selection-change: dismiss the native menu
 *   - everything else: observable via logcat (never silently dropped)
 */
class NativeEventDispatcher(
    private val activity: Activity,
    private val webView: WebView
) {

    // ── protocol constants — mirrors nativeBridge.js EVENTS ─────────────────
    private val EVENT_LEFT = "left"
    private val EVENT_RIGHT = "right"
    private val EVENT_SWIPE = "swipe"
    private val EVENT_SCROLL_TOP = "scroll-top"
    private val EVENT_SCROLL_BOTTOM = "scroll-bottom"
    private val EVENT_SELECT_TEXT = "select-text"
    private val EVENT_SELECT_TEXT_AFTER_TOUCH = "select-text-after-touch"
    private val EVENT_SELECTION_CHANGE = "selection-change"
    private val EVENT_VIEW_IMAGE = "view-image"
    private val EVENT_LINK_CLICKED = "link-clicked"
    private val EVENT_ERROR = "error"
    private val EVENT_HOOKS_READY = "hooks-ready"

    // ── __koodoNative hook names — mirrors nativeBridge.js HOOKS ────────────
    private val HOOK_NEXT_PAGE = "nextPage"
    private val HOOK_PREV_PAGE = "prevPage"
    private val HOOK_OPEN_SELECTION_MENU = "openSelectionMenu"

    // Fallback labels (until the app pushes i18n labels via setMenuLabels).
    private val fallbackCopy = "Copy"
    private val fallbackHighlight = "Highlight"
    private val fallbackNote = "Note"
    private val fallbackShare = "Share"
    private val fallbackSearch = "Search"
    private val fallbackFootnoteCopy = "Copy"
    private val fallbackImageTooLarge = "Image too large to open in the app"
    private val fallbackNothingOpens = "Nothing can open this"

    private var labelCopy = ""
    private var labelHighlight = ""
    private var labelNote = ""
    private var labelShare = ""
    private var labelSearch = ""
    private var labelFootnoteCopy = ""
    private var labelImageTooLarge = ""
    private var labelNothingOpens = ""

    /**
     * Invoked when the page reports its import hook is live ("hooks-ready"
     * app-RPC). The shell wires this to [MainActivity.deliverPendingBook] so a
     * pending intent import is delivered immediately instead of waiting out
     * the retry loop.
     */
    var onHooksReady: (() -> Unit)? = null

    private var selectionMenu: PopupWindow? = null
    private var imageDialog: Dialog? = null
    private var footnoteDialog: Dialog? = null

    // ── JS bridge surface (window.AndroidBridge.setMenuLabels) ──────────────
    /**
     * i18n menu labels pushed from the web app (PopupMenu mounts).
     * Shape: `{copy,highlight,note,share,search}` — matches
     * nativeBridge.js buildMenuLabels(). Empty fields keep the fallbacks.
     */
    fun setMenuLabels(raw: String) {
        try {
            val o = JSONObject(raw)
            labelCopy = o.optString("copy", "").ifBlank { fallbackCopy }
            labelHighlight = o.optString("highlight", "").ifBlank { fallbackHighlight }
            labelNote = o.optString("note", "").ifBlank { fallbackNote }
            labelShare = o.optString("share", "").ifBlank { fallbackShare }
            labelSearch = o.optString("search", "").ifBlank { fallbackSearch }
            labelFootnoteCopy = o.optString("copy", "").ifBlank { fallbackFootnoteCopy }
            labelImageTooLarge = o.optString("imageTooLarge", "").ifBlank { fallbackImageTooLarge }
            labelNothingOpens = o.optString("nothingOpens", "").ifBlank { fallbackNothingOpens }
        } catch (ignored: Exception) {
            // Keep fallbacks.
        }
    }

    /**
     * Entry point for `AndroidBridge.postMessage(json)`.
     * All 21 engine events have an explicit disposition; unknown events are
     * logged (never silently dropped).
     */
    fun dispatch(rawJson: String) {
        val event: String = try {
            JSONObject(rawJson).optString("event", "")
        } catch (e: Exception) {
            "message"
        }
        when (event) {
            // App-injected RPC, not an engine event: the page mounted its
            // import hook — hand over any book queued from an intent.
            EVENT_HOOKS_READY -> onHooksReady?.invoke()

            EVENT_RIGHT -> callHook(HOOK_NEXT_PAGE)
            EVENT_LEFT -> callHook(HOOK_PREV_PAGE)
            EVENT_SWIPE -> callHook(HOOK_NEXT_PAGE) // directionless → forward
            EVENT_SCROLL_TOP -> callHook(HOOK_PREV_PAGE)
            EVENT_SCROLL_BOTTOM -> callHook(HOOK_NEXT_PAGE)

            EVENT_SELECT_TEXT,
            EVENT_SELECT_TEXT_AFTER_TOUCH -> showSelectionMenu(rawJson)

            EVENT_SELECTION_CHANGE -> dismissSelectionMenu()

            EVENT_VIEW_IMAGE -> openImage(rawJson)

            EVENT_LINK_CLICKED -> handleLinkClick(rawJson)

            EVENT_ERROR -> {
                val msg = try {
                    JSONObject(rawJson).optString("message", "")
                } catch (e: Exception) {
                    ""
                }
                if (msg.isNotBlank())
                    Toast.makeText(activity, "Koodo: $msg", Toast.LENGTH_LONG).show()
                else
                    Toast.makeText(activity, "Koodo: an error occurred", Toast.LENGTH_LONG).show()
            }

            // Engine self-handles / informational — consume explicitly.
            "pinch-zoom",
            "user-agent" -> Unit

            else -> Log.i(TAG, "unhandled engine event: $event")
        }
    }

    // ── selection menu ──────────────────────────────────────────────────────
    private fun showSelectionMenu(payloadRaw: String) {
        runOnUiThread {
            val ctx = activity
            val text = runOrNull { JSONObject(payloadRaw).optString("selectedText", "") } ?: ""
            val (x, y) = anchorPoint(payloadRaw)

            val layout = buildMenuLayout(
                ctx,
                labelCopy, {
                    dismissSelectionMenu()
                    if (text.isNotBlank()) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("koodo-text", text))
                        Toast.makeText(ctx, "✓", Toast.LENGTH_SHORT).show()
                    }
                },
                labelHighlight, { dismissSelectionMenu(); callHook(HOOK_OPEN_SELECTION_MENU) },
                labelNote, { dismissSelectionMenu(); callHook(HOOK_OPEN_SELECTION_MENU) },
                labelShare, { dismissSelectionMenu(); shareText(text) },
                labelSearch, { dismissSelectionMenu(); webSearch(text) },
            )

            val pw = PopupWindow(
                layout,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                true
            )
            selectionMenu = pw
            val w = ctx.resources.displayMetrics.widthPixels
            val h = ctx.resources.displayMetrics.heightPixels
            pw.showAtLocation(webView, Gravity.NO_GRAVITY, x.coerceIn(0, w), y.coerceIn(0, h))
        }
    }

    private fun dismissSelectionMenu() {
        val pw = selectionMenu
        selectionMenu = null
        if (pw != null) runOnUiThread { runCatching { pw.dismiss() } }
    }

    private fun buildMenuLayout(
        ctx: Context,
        l1: String, a1: () -> Unit,
        l2: String, a2: () -> Unit,
        l3: String, a3: () -> Unit,
        l4: String, a4: () -> Unit,
        l5: String, a5: () -> Unit,
    ): View {
        val density = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()
        fun menuItem(label: String, onClick: () -> Unit): View = Button(ctx).apply {
            text = label
            isSingleLine = true
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(px(16), px(13), px(16), px(13))
            gravity = Gravity.START
            background = null
            setOnClickListener { onClick() }
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = px(150)
        }
        column.addView(menuItem(l1, a1))
        column.addView(menuItem(l2, a2))
        column.addView(menuItem(l3, a3))
        column.addView(menuItem(l4, a4))
        column.addView(menuItem(l5, a5))
        column.background = GradientDrawable().apply {
            cornerRadius = px(12).toFloat()
            setColor(0xF2202124.toInt())
            setStroke(px(1), Color.parseColor("#3C3F44"))
        }
        return column
    }

    /** Anchor the menu near the engine's selection position, else upper-middle. */
    private fun anchorPoint(payloadRaw: String): Pair<Int, Int> {
        val w = activity.resources.displayMetrics.widthPixels
        val h = activity.resources.displayMetrics.heightPixels
        try {
            val pos = JSONObject(payloadRaw).optJSONObject("position")
            if (pos != null) {
                val px = pos.optDouble("x", -1.0)
                val py = pos.optDouble("y", -1.0)
                if (px >= 0 && py >= 0) return Pair(px.toInt(), py.toInt())
                val left = pos.optDouble("left", -1.0)
                val top = pos.optDouble("top", -1.0)
                if (left >= 0 && top >= 0) return Pair(left.toInt(), top.toInt())
            }
        } catch (ignored: Exception) {
            // fall through to the default anchor
        }
        return Pair(w / 2, h / 3)
    }

    private fun shareText(text: String) {
        if (text.isBlank()) {
            Toast.makeText(activity, labelNothingOpens, Toast.LENGTH_SHORT).show()
            return
        }
        runOnUiThread {
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                })
            }.onFailure {
                Toast.makeText(activity, labelNothingOpens, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun webSearch(text: String) {
        if (text.isBlank()) return
        runOnUiThread {
            runCatching {
                // ACTION_WEB_SEARCH's extra key is the literal "query" (no constant).
                activity.startActivity(Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra("query", text)
                })
            }.onFailure {
                runCatching {
                    activity.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.bing.com/search?q=${Uri.encode(text)}")
                        )
                    )
                }.onFailure {
                    Toast.makeText(
                        activity,
                        labelNothingOpens,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ── image viewer ────────────────────────────────────────────────────────
    private val IMAGE_SIZE_LIMIT = 15L * 1024 * 1024 // bytes (data URI text length bound)

    private fun openImage(payloadRaw: String) {
        val imgSrc = runOrNull { JSONObject(payloadRaw).optString("imgSrc", "") } ?: ""
        if (!imgSrc.startsWith("data:")) {
            runOnUiThread {
                Toast.makeText(activity, labelNothingOpens, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (imgSrc.length.toLong() > IMAGE_SIZE_LIMIT) {
            runOnUiThread {
                Toast.makeText(activity, labelImageTooLarge, Toast.LENGTH_LONG).show()
            }
            return
        }
        runOnUiThread {
            if (imageDialog?.isShowing == true) imageDialog?.dismiss()
            // Escape html attributes in the src — value is engine-produced data: URI.
            val safeSrc = imgSrc.replace("\"", "%22")
            val innerWebView = WebView(activity)
            innerWebView.setBackgroundColor(Color.parseColor("#101014"))
            val dialog = Dialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.window?.let { win ->
                win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val lp = WindowManager.LayoutParams()
                lp.width = WindowManager.LayoutParams.MATCH_PARENT
                lp.height = WindowManager.LayoutParams.MATCH_PARENT
                win.attributes = lp
                win.setDimAmount(0.96f)
            }
            innerWebView.webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): Boolean = true
            }
            innerWebView.settings.javaScriptEnabled = false
            innerWebView.loadDataWithBaseURL(
                null,
                "<!doctype html><html><head><meta charset=\"utf-8\"/>" +
                    "<style>html,body{margin:0;height:100%;overflow:auto;background:#101014;}" +
                    "img{display:block;margin:0 auto;max-height:88vh;}</style></head>" +
                    "<body><img src=\"$safeSrc\" oncontextmenu=\"return false\"/></body></html>",
                "text/html",
                "utf-8",
                null
            )
            val closeBtn = Button(activity).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#202124"))
                setOnClickListener { dialog.dismiss() }
            }
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(innerWebView, LinearLayout.LayoutParams(MATCH_PARENT_PX, 0, 1f))
                addView(closeBtn, LinearLayout.LayoutParams(MATCH_PARENT_PX, WRAP_CONTENT_PX))
            }
            dialog.setContentView(root)
            dialog.setOnDismissListener { imageDialog = null }
            imageDialog = dialog
            dialog.show()
        }
    }

    // ── link / footnote ─────────────────────────────────────────────────────
    private fun handleLinkClick(payloadRaw: String) {
        val o = runOrNull { JSONObject(payloadRaw) } ?: JSONObject()
        val href = o.optString("href", "")
        val footnote = o.optString("footnote", "")
        if (footnote.isNotBlank()) {
            runOnUiThread { showFootnoteDialog(footnote) }
            return
        }
        if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) {
            runOnUiThread {
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(href))) }
                    .onFailure {
                        Toast.makeText(activity, labelNothingOpens, Toast.LENGTH_SHORT).show()
                    }
            }
            return
        }
        // In-app / unknown scheme: nothing native to do.
    }

    private fun showFootnoteDialog(text: String) {
        if (footnoteDialog?.isShowing == true) footnoteDialog?.dismiss()
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.parseColor("#B0000000")))
            val lp = WindowManager.LayoutParams()
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            win.attributes = lp
        }
        val container = ScrollView(activity).apply {
            setBackgroundColor(Color.parseColor("#1B1D22"))
            setPadding(density(16), density(16), density(16), density(8))
        }
        val tv = TextView(activity).apply {
            this.text = text
            setTextColor(Color.parseColor("#D7DCE4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 0, 0, density(12))
        }
        val copyBtn = Button(activity).apply {
            // Explicit receiver: the outer `text` parameter would otherwise win
            // the assignment resolution and fail with "val cannot be reassigned".
            this.text = labelFootnoteCopy
            isSingleLine = true
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("koodo-footnote", text))
                Toast.makeText(activity, "✓", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv)
            addView(copyBtn)
        })
        dialog.setContentView(container)
        dialog.setOnDismissListener { footnoteDialog = null }
        footnoteDialog = dialog
        dialog.show()
    }

    // ── helpers ─────────────────────────────────────────────────────────────
    private fun callHook(fn: String) {
        // Guard the hook itself: contributors register/unregister independently,
        // so a page may legitimately not expose every hook at any given moment.
        val expr = "window.__koodoNative && window.__koodoNative.$fn && window.__koodoNative.$fn()"
        runOnUiThread {
            runCatching { webView.evaluateJavascript(expr, null) }
                .onFailure { Log.w(TAG, "hook $fn failed", it) }
        }
    }

    private inline fun <T> runOrNull(block: () -> T?): T? =
        try { block() } catch (e: Exception) { null }

    /** The dispatcher is not an Activity; hop to the UI thread via the host. */
    private fun runOnUiThread(block: () -> Unit) {
        activity.runOnUiThread(block)
    }

    private fun density(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "NativeEventDispatcher"
        private const val MATCH_PARENT_PX = -1
        private const val WRAP_CONTENT_PX = -2
    }
}
