package com.koodoreader.reader.pdfhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the PDF engine bootstrap contract.
 *
 * `index.html` is an ES-module page: `onPageFinished` fires after HTML parse,
 * *before* `engine.mjs` / `pdf.mjs` install `window.__koodoPdf`. Waiting on
 * page-finished makes `open()` race the module graph — on a packaged APK the
 * race almost always loses, so the native PDF reader never opens.
 *
 * The page signals readiness with `host.onEvent('ready', …)`.
 */
class PdfEngineReadyLatchTest {

    @Test
    fun `page finished is not a ready signal`() {
        val gate = PdfEngineReadyLatch()
        gate.onPageFinished()
        assertFalse(gate.ready)
        assertFalse("onPageFinished must not release the latch", gate.await(20))
    }

    @Test
    fun `ready host event completes bootstrap`() {
        val gate = PdfEngineReadyLatch()
        gate.onHostEvent(PdfEngineReadyLatch.READY_EVENT)
        assertTrue(gate.ready)
        assertTrue(gate.await(20))
    }

    @Test
    fun `unrelated host events do not complete bootstrap`() {
        val gate = PdfEngineReadyLatch()
        gate.onHostEvent("log")
        gate.onHostEvent("error")
        assertFalse(gate.ready)
        assertFalse(gate.await(20))
    }

    @Test
    fun `a main-frame load error fails bootstrap instead of hanging`() {
        val gate = PdfEngineReadyLatch()
        gate.fail("HTTP 404")
        assertFalse(gate.ready)
        assertEquals("HTTP 404", gate.failure)
        assertFalse(gate.await(20))
    }
}
