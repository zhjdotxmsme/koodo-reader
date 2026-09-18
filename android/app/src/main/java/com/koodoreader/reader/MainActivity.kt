package com.koodoreader.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Koodo Reader Android host.
 *
 * Loads the bundled web build in a [WebView] and exposes a small JS bridge. The
 * page is served by [LocalAssetServer] over `http://127.0.0.1:<port>/index.html`
 * (real origin), with a `file:///android_asset/webapp/index.html` fallback when
 * the loopback server cannot start. The bridge mirrors the
 * `window.ReactNativeWebView` surface that the reading engine (`kookit-extra`)
 * already knows how to talk to:
 *   - web -> native: `window.ReactNativeWebView.postMessage(json)`
 *     (consumed by [NativeEventDispatcher])
 *   - native -> web: `window.ReactNativeWebView.onFilePicked(uri)`
 * and a native-only helper `window.AndroidBridge` (openExternal, getInfo,
 * pickFile, pickFolder, listFolder, setMenuLabels).
 *
 * Folder picking (bulk library import) uses Storage Access Framework
 * (`ACTION_OPEN_DOCUMENT_TREE`) with a persisted read permission. The Kotlin
 * side only enumerates files; the protocol and book-file rules live in
 * `src/utils/android/folderBridge.js` (single source of truth, unit tested).
 *
 * Files opened from other apps (`VIEW`/`SEND` intents) are copied into the cache,
 * exposed through the loopback server and handed to the web app via the
 * `__koodoNative.openLocalFile` hook (see `src/utils/android/nativeBridge.js`).
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var lastFolder: Uri? = null

    /**
     * Loopback HTTP server serving `assets/webapp` (see [LocalAssetServer]).
     * Lazily built so `webView` exists first; stopped in [onDestroy].
     */
    private val assetServer by lazy { LocalAssetServer(assets) }

    /** A book pushed by VIEW/SEND awaiting delivery to the web app. */
    private class PendingBook(val url: String, val name: String) {
        var attempts: Int = 0
    }

    private var pendingBook: PendingBook? = null

    /**
     * Engine event consumer (21-event protocol). Lazily built so the WebView
     * exists at construction; the protocol mirror is
     * `src/utils/android/nativeBridge.js`.
     */
    private val eventDispatcher by lazy { NativeEventDispatcher(this, webView) }

    /** JS-visible bridge, attached as `window.AndroidBridge`. */
    private val bridge = object : Any() {

        @JavascriptInterface
        fun postMessage(message: String) {
            runOnUiThread {
                if (message.isEmpty()) return@runOnUiThread
                eventDispatcher.dispatch(message)
            }
        }

        /**
         * i18n labels for the native selection menu, pushed by the web app
         * (PopupMenu mount). Shape: `{copy,highlight,note,share,search}`.
         */
        @JavascriptInterface
        fun setMenuLabels(labels: String) {
            runOnUiThread { eventDispatcher.setMenuLabels(labels) }
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            if (url.isBlank()) return
            runOnUiThread {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }.onFailure {
                    Toast.makeText(this@MainActivity, "Nothing can open this link", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        @JavascriptInterface
        fun getInfo(): String {
            return JSONObject()
                .put("appName", "Koodo Reader")
                .put("packageName", packageName)
                .put("versionName", packageManager.getPackageInfo(packageName, 0).versionName)
                .put("platform", "android")
                .toString()
        }

        @JavascriptInterface
        fun pickFile(accept: String) {
            runOnUiThread { startFileChooser() }
        }

        /**
         * Open the SAF folder picker (bulk library import). The chosen folder's
         * files are delivered to the page via
         * `window.ReactNativeWebView.onFolderPicked(json)` and a `document`
         * "message" event (payload shape: `src/utils/android/folderBridge.js`).
         */
        @JavascriptInterface
        fun pickFolder() {
            runOnUiThread { chooseFolder() }
        }

        /** Re-list a previously picked folder (persisted read permission). */
        @JavascriptInterface
        fun listFolder(uri: String) {
            if (uri.isBlank()) return
            val u = Uri.parse(uri)
            runOnUiThread { refreshFolderList(u) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        webView = WebView(this)
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Respect the viewport meta (mobile layout) instead of desktop sizing.
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                installReactNativeWebViewShim()
                deliverPendingBook()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                pendingFileChooser = filePathCallback
                startFileChooser()
                return true
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                result?.confirm()
                return true
            }
        }

        webView.addJavascriptInterface(bridge, "AndroidBridge")
        // Event-driven intent delivery: the page notifies "hooks-ready" once
        // its import hook is registered (the retry loop stays as a fallback).
        eventDispatcher.onHooksReady = { deliverPendingBook() }
        // Remote debugging (chrome://inspect) for debuggable builds only.
        WebView.setWebContentsDebuggingEnabled(
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        )

        // Leftover intent copies from a previous session are stale by now;
        // drop them before the next import copies its own payload.
        cleanupIntentBooks()
        // Prefer the loopback HTTP origin (real origin: IndexedDB/localStorage/
        // CORS behave) and fall back to file:// when the server cannot start.
        webView.loadUrl(pageUrl())
        handleIntent(intent)
    }

    /** URL the web app is loaded from (loopback HTTP when available). */
    private fun pageUrl(): String {
        if (!assetServer.isRunning) {
            val started = runCatching { assetServer.start() }.getOrNull()
            if (started == null) {
                Log.w(TAG, "loopback server failed to start; falling back to file://")
                return FALLBACK_URL
            }
            Log.i(TAG, "loopback server on ${assetServer.baseUrl()}")
        }
        return "${assetServer.baseUrl()}/index.html"
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Act on a VIEW/SEND intent carrying a book (file manager / share sheet).
     * The stream is copied into the app cache and exposed through the loopback
     * server, then handed to the web app's import pipeline via the
     * `__koodoNative.openLocalFile` hook (see src/utils/android/nativeBridge.js).
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            val stream = getParcelableUri(intent, Intent.EXTRA_STREAM)
            if (stream != null) queueBook(stream, intent.type)
            return
        }
        val data = intent.data ?: return
        val scheme = data.scheme?.lowercase() ?: return
        when (scheme) {
            "content", "file" -> queueBook(data, intent.type)
            // App scheme (koodo-reader://) and plain web links: nothing to import.
            else -> Unit
        }
    }

    /**
     * Copy the incoming document into the cache and remember it for delivery.
     * The copy runs off the UI thread (a large book must not risk an ANR); the
     * hand-over then resumes on the UI thread.
     */
    private fun queueBook(uri: Uri, mimeType: String?) {
        if (!assetServer.isRunning && runCatching { assetServer.start() }.isFailure) {
            Log.w(TAG, "loopback server unavailable; cannot hand over $uri")
            toast("Cannot import: the local service failed to start.")
            return
        }
        val thread = Thread {
            val copied = runCatching { copyToCache(uri, mimeType) }.getOrNull()
            runOnUiThread {
                if (copied == null) {
                    Log.w(TAG, "could not read intent payload: $uri")
                    // `content://` carries a system read grant; a bare `file://`
                    // has none, so scoped storage usually blocks the direct read.
                    if (uri.scheme == "file") {
                        toast(
                            "This file cannot be opened directly. " +
                                "Please share it to Koodo Reader or import it from inside the app."
                        )
                    } else {
                        toast("Could not read the shared file.")
                    }
                    return@runOnUiThread
                }
                val exposedPath = assetServer.exposeFile("$BOOKS_PREFIX/${copied.name}", copied)
                pendingBook = PendingBook("${assetServer.baseUrl()}/$exposedPath", copied.name)
                deliverPendingBook()
            }
        }
        thread.name = "koodo-intent-copy"
        thread.isDaemon = true
        thread.start()
    }

    /**
     * Delete documents copied for earlier intent imports. Safe at `onCreate`
     * time: nothing is exposed to the loopback server yet, and every new
     * import copies its own payload before delivery.
     */
    private fun cleanupIntentBooks() {
        runCatching {
            File(cacheDir, "intent-books").listFiles()?.forEach { it.deleteRecursively() }
        }.onFailure { Log.w(TAG, "intent-books cleanup failed", it) }
    }

    /**
     * Hand the queued book to the page once the import hook exists.
     * Retries briefly: on a cold start the React tree (which registers the hook)
     * may still be mounting when the page finishes loading.
     */
    private fun deliverPendingBook() {
        val book = pendingBook ?: return
        if (book.attempts >= MAX_DELIVER_ATTEMPTS) {
            pendingBook = null
            return
        }
        book.attempts += 1
        val js = "window.__koodoNative && window.__koodoNative.openLocalFile" +
            " && window.__koodoNative.openLocalFile(${JSONObject.quote(book.url)}," +
            " ${JSONObject.quote(book.name)})"
        webView.evaluateJavascript(js) { result ->
            // null/undefined/false → the hook is not registered yet; retry.
            val handled = result != null && result != "null" &&
                result != "undefined" && result != "false"
            if (handled) {
                pendingBook = null
            } else {
                webView.postDelayed({ deliverPendingBook() }, DELIVER_RETRY_MS)
            }
        }
    }

    /** Copy a content:// (or file://) document into `cacheDir/intent-books/`. */
    private fun copyToCache(uri: Uri, mimeType: String?): File? {
        val name = displayName(uri, mimeType)
        val dir = File(cacheDir, "intent-books")
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, name)
        val input = when (uri.scheme?.lowercase()) {
            "content" -> contentResolver.openInputStream(uri)
            else -> File(uri.path ?: return null).inputStream()
        } ?: return null
        input.use { source ->
            target.outputStream().use { sink -> source.copyTo(sink) }
        }
        return target
    }

    /** Best-effort display name for an incoming document URI. */
    private fun displayName(uri: Uri, mimeType: String?): String {
        val fromProvider = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        val raw = fromProvider
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "book"
        val safe = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim()
        val withName = if (safe.isEmpty()) "book" else safe
        return if (withName.contains('.')) withName else "$withName.${extensionFor(mimeType)}"
    }

    private fun extensionFor(mimeType: String?): String {
        return when (mimeType?.lowercase()) {
            "application/epub+zip" -> "epub"
            "application/pdf" -> "pdf"
            "text/plain" -> "txt"
            "text/html", "application/xhtml+xml" -> "html"
            "text/xml", "application/xml" -> "xml"
            "text/markdown" -> "md"
            "application/x-mobipocket-ebook" -> "mobi"
            "application/x-fictionbook+xml" -> "fb2"
            "application/x-cbz" -> "cbz"
            "application/x-cbr" -> "cbr"
            "application/x-cbt" -> "cbt"
            "application/x-cb7" -> "cb7"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            else -> "epub"
        }
    }

    @Suppress("DEPRECATION")
    private fun getParcelableUri(intent: Intent, key: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key) as? Uri
        }
    }

    /**
     * Emulate the `window.ReactNativeWebView` global the reading engine expects,
     * forwarding `postMessage` to the native [bridge].
     */
    private fun installReactNativeWebViewShim() {
        val script =
            "if (window.ReactNativeWebView === undefined) {" +
                "window.ReactNativeWebView = {" +
                "  postMessage: function (m) {" +
                "     if (window.AndroidBridge && window.AndroidBridge.postMessage) {" +
                "       window.AndroidBridge.postMessage(typeof m === 'string' ? m : JSON.stringify(m));" +
                "     }" +
                "  }," +
                "  pickFolder: function () {" +
                "     if (window.AndroidBridge && window.AndroidBridge.pickFolder) {" +
                "       window.AndroidBridge.pickFolder();" +
                "     }" +
                "  }," +
                "  listFolder: function (u) {" +
                "     if (window.AndroidBridge && window.AndroidBridge.listFolder) {" +
                "       window.AndroidBridge.listFolder(u);" +
                "     }" +
                "  }," +
                "  onFilePicked: null," +
                "  onFolderPicked: null" +
                "};" +
                "} else {" +
                "  // Ensure the engine's pickFile hook has a default no-op." +
                "  if (window.ReactNativeWebView.onFilePicked === undefined) {" +
                "    window.ReactNativeWebView.onFilePicked = function () {};" +
                "  }" +
                "  if (window.ReactNativeWebView.onFolderPicked === undefined) {" +
                "    window.ReactNativeWebView.onFolderPicked = function () {};" +
                "  }" +
                "  if (window.ReactNativeWebView.pickFolder === undefined) {" +
                "    window.ReactNativeWebView.pickFolder = function () {" +
                "      if (window.AndroidBridge && window.AndroidBridge.pickFolder) window.AndroidBridge.pickFolder();" +
                "    };" +
                "  }" +
                "  if (window.ReactNativeWebView.listFolder === undefined) {" +
                "    window.ReactNativeWebView.listFolder = function (u) {" +
                "      if (window.AndroidBridge && window.AndroidBridge.listFolder) window.AndroidBridge.listFolder(u);" +
                "    };" +
                "  }" +
                "}"
        webView.evaluateJavascript(script, null)
    }

    private fun startFileChooser() {
        val callback = pendingFileChooser
        if (callback == null) {
            // onShowFileChooser always sets the callback first; reaching this
            // branch means a stale chooser was already consumed.
            toast("File picking requires the WebView file chooser.")
            return
        }
        runCatching {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // `*/*` + OPENABLE: document providers report real MIME types
                // (application/epub+zip, application/pdf, ...); an
                // octet-stream filter greys out exactly those books.
                type = "*/*"
            }
            startActivityForResult(Intent.createChooser(intent, "Choose a book"), REQ_FILE_CHOOSER)
        }.onFailure {
            // Never leave the WebView waiting on a chooser that never opened.
            pendingFileChooser = null
            callback.onReceiveValue(null)
            toast("Could not open the file picker.")
        }
    }

    // ------------------------------------------------------------------
    // SAF folder picking (bulk library import). Kotlin only enumerates; the
    // protocol and book-file rules live in src/utils/android/folderBridge.js.
    // ------------------------------------------------------------------

    /** One regular file inside a picked folder tree. */
    private data class FolderFile(
        val name: String,
        val uri: Uri,
        val mime: String?,
        val size: Long?,
    )

    private fun chooseFolder() {
        runCatching {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                lastFolder?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
                // Request a persistable read grant so the folder access survives
                // app restarts (takePersistableUriPermission is applied on result).
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
            startActivityForResult(intent, REQ_FOLDER_PICKER)
        }.onFailure { toast("Could not open the folder picker.") }
    }

    private fun refreshFolderList(treeUri: Uri) {
        val files = runCatching { listDirectory(treeUri, FOLDER_DEPTH, MAX_FILES) }.getOrElse {
            toast("Failed to read the folder.")
            emptyList()
        }
        lastFolder = treeUri
        deliverFolderResult(treeUri, files)
    }

    /**
     * Enumerate regular files under the SAF tree (root + [FOLDER_DEPTH]
     * sub-directory levels), capped at [MAX_FILES]. Directories are never
     * delivered.
     */
    private fun listDirectory(treeUri: Uri, depth: Int, limit: Int): List<FolderFile> {
        val files = mutableListOf<FolderFile>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        fun walk(docId: String, remainingDepth: Int) {
            if (files.size >= limit) return
            val authority = treeUri.authority ?: return
            val childrenUri = DocumentsContract.buildChildDocumentsUri(authority, docId)
            val cursor = contentResolver.query(childrenUri, projection, null, null, null) ?: return
            cursor.use { c ->
                val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (idCol < 0 || nameCol < 0) return@use
                while (c.moveToNext() && files.size < limit) {
                    val id = c.getString(idCol)
                    val name = c.getString(nameCol)
                    val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                    val size = if (sizeCol >= 0 && !c.isNull(sizeCol)) {
                        c.getString(sizeCol)?.toLongOrNull()
                    } else {
                        null
                    }
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    if (isDirectory) {
                        if (remainingDepth > 0) walk(id, remainingDepth - 1)
                    } else {
                        files.add(FolderFile(name, fileUri, mime, size))
                    }
                }
            }
        }

        walk(DocumentsContract.getDocumentId(treeUri), depth)
        return files
    }

    /**
     * Deliver the folder listing to the page:
     *   1. a `document` "message" event (engine style: `JSON.parse(event.data)`)
     *   2. `window.ReactNativeWebView.onFolderPicked(jsonString)`
     * The payload is passed as a JSON **string** literal (JSONObject.quote), so
     * `event.data` stays a string that `JSON.parse` accepts — engine style.
     */
    private fun deliverFolderResult(folder: Uri, files: List<FolderFile>) {
        val arr = JSONArray()
        for (f in files) {
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("uri", f.uri.toString())
                    .put("size", if (f.size != null) f.size else JSONObject.NULL)
                    .put("mime", f.mime ?: "")
            )
        }
        val payload = JSONObject()
            .put("event", "folder-picked")
            .put("folder", folder.toString())
            .put("count", files.size)
            .put("files", arr)
        val script =
            "(function (j) {" +
                "try {" +
                "  var ev = document.createEvent('CustomEvent');" +
                "  ev.initCustomEvent('message', true, true);" +
                "  ev.data = j;" +
                "  document.dispatchEvent(ev);" +
                "} catch (e) {}" +
                "try {" +
                "  if (window.ReactNativeWebView && window.ReactNativeWebView.onFolderPicked) {" +
                "    window.ReactNativeWebView.onFolderPicked(j);" +
                "  }" +
                "} catch (e) {}" +
                "})(" + JSONObject.quote(payload.toString()) + ")"
        webView.evaluateJavascript(script, null)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FOLDER_PICKER) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) {
                    // Keep the read permission across app restarts.
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    refreshFolderList(uri)
                } else {
                    toast("No folder selected.")
                }
            }
            return
        }
        if (requestCode != REQ_FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val callback = pendingFileChooser
        pendingFileChooser = null
        if (callback == null) return

        if (resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                callback.onReceiveValue(arrayOf(uri))
                notifyWebFilePicked(uri)
            } else {
                callback.onReceiveValue(null)
            }
        } else {
            callback.onReceiveValue(null)
        }
    }

    private fun notifyWebFilePicked(uri: Uri) {
        // JSON-escape the URI before embedding it in JS.
        val quoted = JSONObject.quote(uri.toString())
        val script =
            "window.ReactNativeWebView && " +
                "window.ReactNativeWebView.onFilePicked && " +
                "window.ReactNativeWebView.onFilePicked($quoted)"
        webView.evaluateJavascript(script, null)
    }

    @Suppress("DEPRECATION", "InlinedApi")
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        assetServer.stop()
        if (this::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_FILE_CHOOSER = 0x4F4B // "OK"
        private const val REQ_FOLDER_PICKER = 0x504B // "PK"
        // Keep in sync with src/utils/android/folderBridge.js (MAX_FILES / FOLDER_DEPTH).
        private const val MAX_FILES = 1000
        private const val FOLDER_DEPTH = 2
        private const val TAG = "KoodoReader"
        private const val FALLBACK_URL = "file:///android_asset/webapp/index.html"
        private const val BOOKS_PREFIX = "__books__"
        private const val MAX_DELIVER_ATTEMPTS = 20
        private const val DELIVER_RETRY_MS = 400L
    }
}
