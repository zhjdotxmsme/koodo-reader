package com.koodoreader.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject

/**
 * Koodo Reader Android host.
 *
 * Loads the bundled web build (`assets/webapp/index.html`) in a [WebView] and
 * exposes a small JS bridge. The bridge mirrors the `window.ReactNativeWebView`
 * surface that the reading engine (`kookit-extra`) already knows how to talk to:
 *   - web -> native: `window.ReactNativeWebView.postMessage(json)`
 *   - native -> web: `window.ReactNativeWebView.onFilePicked(uri)`
 * and a native-only helper `window.AndroidBridge` (openExternal, getInfo, pickFile).
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null

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
            ) {
                result?.confirm()
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
                "  onFilePicked: null" +
                "};" +
                "} else {" +
                "  // Ensure the engine's pickFile hook has a default no-op." +
                "  if (window.ReactNativeWebView.onFilePicked === undefined) {" +
                "    window.ReactNativeWebView.onFilePicked = function () {};" +
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

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
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
    }
}
