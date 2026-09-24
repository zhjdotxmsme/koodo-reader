package com.koodoreader.engine.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfHostBridgeTest {

    /**
     * In-memory bridge used to exercise the wire protocol without a real
     * WebView. The production host wires a WebView-backed implementation
     * (see `:app` — `PdfJsHostBridge`); this fake lives only in the tests
     * so the protocol can be locked down.
     */
    private class FakeBridge : PdfHostBridge {
        var openedWith: Pair<String, String?>? = null
        var lastSearch: String? = null
        var lastHighlights: List<CfiPdfMapper.PdfRange> = emptyList()
        var closed = false
        var nextRender: ByteArray = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

        override fun open(pdfPath: String, password: String?): PdfHostBridge.OpenResult {
            openedWith = pdfPath to password
            return PdfHostBridge.OpenResult("deadbeef", 250, "1.7", 612f, 792f)
        }

        override fun renderPage(pageNumber: Int, targetWidthPx: Int): ByteArray = nextRender

        override fun search(query: String): String {
            lastSearch = query
            return """[{"pageNumber":1,"hits":[{"rects":[{"x":1,"y":1,"width":2,"height":3}],"text":"fox"}]}]"""
        }

        override fun outline(): String = """[{"title":"Chapter 1","pageNumber":1,"children":[]}]"""

        override fun selectedRect(): CfiPdfMapper.PdfRange =
            CfiPdfMapper.PdfRange(fingerprint = "ff", page = 1, x = 1f, y = 1f, width = 2f, height = 3f)

        override fun paintHighlights(ranges: List<CfiPdfMapper.PdfRange>) {
            lastHighlights = ranges
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun openPropagatesPathAndPassword() {
        val b = FakeBridge()
        val res = b.open("/data/x.pdf", null)
        assertNotNull(res)
        assertEquals("deadbeef", res.fingerprint)
        assertEquals(250, res.pageCount)
        assertEquals("/data/x.pdf" to null, b.openedWith)
    }

    @Test
    fun renderPageReturnsBytes() {
        val b = FakeBridge()
        val bytes = b.renderPage(1, 512)
        assertEquals(4, bytes.size)
    }

    @Test
    fun searchHitParseRoundTrips() {
        val b = FakeBridge()
        val hits = PdfSearchEngine().parseHits(b.search("fox"), "fox")
        assertEquals(1, hits.size)
        assertEquals(1, hits[0].pageNumber)
    }

    @Test
    fun outlineParseRoundTrips() {
        val b = FakeBridge()
        val tree = OutlineResolver.resolve(b.outline())
        assertEquals(1, tree.entries.size)
        assertEquals("Chapter 1", tree.entries[0].title)
    }

    @Test
    fun selectedRectIsPdfRange() {
        val b = FakeBridge()
        val r = b.selectedRect()
        assertEquals(1, r!!.page)
        assertEquals(2f, r.width)
    }

    @Test
    fun paintHighlightsDelivers() {
        val b = FakeBridge()
        b.paintHighlights(
            listOf(
                CfiPdfMapper.PdfRange(fingerprint = "ff", page = 2),
                CfiPdfMapper.PdfRange(fingerprint = "ff", page = 5),
            )
        )
        assertEquals(2, b.lastHighlights.size)
        assertEquals(5, b.lastHighlights[1].page)
    }

    @Test
    fun closeIsHonoured() {
        val b = FakeBridge()
        b.close()
        assertTrue(b.closed)
    }

    @Test
    fun loadScriptEncodesPath() {
        val s = PdfHostBridge.loadScript("/data/x.pdf", "pw", "http://127.0.0.1:8080")
        assertTrue(s.startsWith("window.__koodoPdf.open("))
        assertTrue(s.contains("/data/x.pdf"))
        assertTrue(s.contains("\"pw\""))
    }

    @Test
    fun loadScriptHandlesNullPassword() {
        val s = PdfHostBridge.loadScript("/x", null, "http://localhost")
        assertTrue(s.contains("\"\""), "got: $s")
    }
}