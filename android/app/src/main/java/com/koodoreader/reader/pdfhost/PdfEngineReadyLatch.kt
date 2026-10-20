package com.koodoreader.reader.pdfhost

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Completes only when the pdfengine page has installed `window.__koodoPdf`.
 *
 * The bootstrap HTML is an ES module (`index.html` imports `engine.mjs` +
 * `pdf.mjs`). Chromium fires `WebViewClient.onPageFinished` after the HTML
 * parse, *before* those modules run, so treating page-finished as "engine
 * ready" lets [PdfJsHostBridge.open] race the module graph. The page signals
 * the real ready point with `host.onEvent('ready', …)`.
 *
 * Pure JVM so unit tests can pin the contract without a WebView.
 */
class PdfEngineReadyLatch {

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var failure: String? = null
        private set

    private val latch = CountDownLatch(1)

    /** HTML parse finished — not sufficient; see class kdoc. */
    fun onPageFinished() = Unit

    fun onHostEvent(name: String) {
        if (name == READY_EVENT) {
            ready = true
            latch.countDown()
        }
    }

    fun fail(reason: String) {
        if (latch.count == 0L) return
        failure = reason
        latch.countDown()
    }

    /**
     * Wait until [onHostEvent] saw `ready`, or until [fail] / timeout.
     * @return true only when the engine actually signalled ready.
     */
    fun await(timeoutMs: Long): Boolean {
        val signalled = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return signalled && ready
    }

    companion object {
        const val READY_EVENT = "ready"
    }
}
