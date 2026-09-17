package com.koodoreader.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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

/**
 * Koodo Reader Android host.
 *
 * Loads the bundled web build (`assets/webapp/index.html`) in a [WebView] and
 * exposes a small JS bridge. The bridge mirrors the `window.ReactNativeWebView`
 * surface that the reading engine (`kookit-extra`) already knows how to talk to:
 *   - web -> native: `window.ReactNativeWebView.postMessage(json)`
 *   - native -> web: `window.ReactNativeWebView.onFilePicked(uri)`
 * and a native-only helper `window.AndroidBridge` (openExternal, getInfo, pickFile,
 * pickFolder, listFolder).
 *
 * Folder picking (bulk library import) uses Storage Access Framework
 * (`ACTION_OPEN_DOCUMENT_TREE`) with a persisted read permission. The Kotlin
 * side only enumerates files; the protocol and book-file rules live in
 * `src/utils/android/folderBridge.js` (single source of truth, unit tested).
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var lastFolder: Uri? = null

    /** JS-visible bridge, attached as `window.AndroidBridge`. */
    private val bridge = object : Any() {

        @JavascriptInterface
        fun postMessage(message: String) {
            runOnUiThread {
                var event = "message"
                var payload = ""
                try {
                    val obj = JSONObject(message)
                    event = obj.optString("event", "message")
                    payload = obj.optString("message", "")
                } catch (ignored: Exception) {
                    // Not JSON — treat as a plain log line.
                }
                when (event) {
                    "error" ->
                        Toast.makeText(
                            this@MainActivity,
                            if (payload.isNotEmpty()) "Koodo: $payload" else "Koodo: an error occurred",
                            Toast.LENGTH_LONG
                        ).show()
                    else ->
                        // Future hooks (finish-download, cache, ocr-result) can be added here.
                        {}
                }
            }
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

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                installReactNativeWebViewShim()
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

        val base = "file:///android_asset/webapp/index.html"
        val deepLink = intent?.data
        val target = if (deepLink != null) deepLink.toString() else base
        webView.loadUrl(target)
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
        runCatching {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
            }
            if (pendingFileChooser != null) {
                startActivityForResult(Intent.createChooser(intent, "Choose a book"), REQ_FILE_CHOOSER)
            } else {
                toast("File picking requires the WebView file chooser.")
            }
        }.onFailure { toast("Could not open the file picker.") }
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
    }
}
