package com.koodoreader.reader.epubhost

/**
 * Layout contract for the visible foliate-js WebView.
 *
 * Chromium does not run page JS (and never emits `host.onEvent('ready')`)
 * until the WebView is attached. A spinner-only composition therefore
 * deadlocks [FoliateHostBridge.bootstrap].
 */
object FoliateReaderLayout {

    fun attachWebView(
        useFoliate: Boolean,
        hasFile: Boolean,
        assetsAvailable: Boolean,
    ): Boolean = useFoliate && hasFile && assetsAvailable

    fun showLoadingOverlay(ready: Boolean, openError: String?): Boolean =
        !ready && openError == null
}
