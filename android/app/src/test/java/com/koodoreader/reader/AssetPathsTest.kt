package com.koodoreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [AssetPaths] — the pure part of [LocalAssetServer].
 *
 * Regression context (P3): the native PDF reader never had a working engine URL.
 * `assets/pdfengine/index.html` was requested from a server rooted at
 * `assets/webapp`, so every request 404'd; nothing in the build could see it
 * because both paths look plausible in isolation.
 */
class AssetPathsTest {

    @Test
    fun `root request maps to the index file`() {
        assertEquals("index.html", AssetPaths.normalize("/"))
        assertEquals("index.html", AssetPaths.normalize(""))
        assertEquals("index.html", AssetPaths.normalize("///"))
    }

    @Test
    fun `query string and traversal segments are dropped`() {
        assertEquals("index.html", AssetPaths.normalize("/index.html?v=2"))
        assertEquals("assets/main.js", AssetPaths.normalize("/assets/main.js"))
        assertEquals("secret", AssetPaths.normalize("/../../secret"))
        assertEquals("a/b.js", AssetPaths.normalize("a\\b.js"))
    }

    @Test
    fun `default roots cover the react island and the pdf engine`() {
        assertEquals(listOf("webapp", "pdfengine"), AssetPaths.DEFAULT_ROOTS)
    }

    @Test
    fun `candidates are tried in order across every root`() {
        val roots = listOf("webapp", "pdfengine")
        assertEquals(
            listOf("webapp/index.html", "pdfengine/index.html"),
            AssetPaths.candidates("index.html", roots),
        )
        assertEquals(
            listOf("webapp/pdfengine/index.html", "pdfengine/pdfengine/index.html"),
            AssetPaths.candidates("pdfengine/index.html", roots),
        )
    }

    @Test
    fun `a blank root serves the request path unchanged`() {
        assertEquals(
            listOf("index.html", "pdfengine/index.html"),
            AssetPaths.candidates("index.html", listOf("", "pdfengine")),
        )
    }

    @Test
    fun `the pdf engine request resolves inside the pdfengine root`() {
        // The exact request PdfJsHostBridge makes for its bootstrap page.
        val path = AssetPaths.normalize("/pdfengine/index.html")
        assertEquals("pdfengine/index.html", path)
        assertEquals(
            listOf("webapp/pdfengine/index.html", "pdfengine/pdfengine/index.html"),
            AssetPaths.candidates(path, AssetPaths.DEFAULT_ROOTS),
        )
        // …and the module/worker/cmaps the page imports resolve the same way.
        for (asset in listOf("engine.mjs", "pdf.mjs", "pdf.worker.mjs", "cmaps/UniGB-UCS2-H.bcmap")) {
            val candidates = AssetPaths.candidates(AssetPaths.normalize("pdfengine/$asset"))
            assertTrue(
                "$asset must be reachable under the pdfengine root",
                candidates.contains("pdfengine/pdfengine/$asset"),
            )
        }
    }

    @Test
    fun `only extension-less requests are eligible for the SPA fallback`() {
        assertTrue(AssetPaths.isClientRoute("library"))
        assertTrue(AssetPaths.isClientRoute("reader/abc"))
        assertFalse(AssetPaths.isClientRoute("pdfengine/index.html"))
        assertFalse(AssetPaths.isClientRoute("pdfengine/pdf.mjs"))
        assertFalse(AssetPaths.isClientRoute("assets/icon.png"))
    }
}
