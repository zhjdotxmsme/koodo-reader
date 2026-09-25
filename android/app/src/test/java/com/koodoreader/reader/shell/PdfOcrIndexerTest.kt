package com.koodoreader.reader.shell

import com.koodoreader.feature.ocr.InMemoryOnDemandDownloader
import com.koodoreader.feature.ocr.InMemoryOcrIndexStore
import com.koodoreader.feature.ocr.OcrEngine
import com.koodoreader.feature.ocr.OcrImage
import com.koodoreader.feature.ocr.OcrLine
import com.koodoreader.feature.ocr.OcrPageResult
import com.koodoreader.feature.ocr.OcrRequest
import com.koodoreader.feature.ocr.OcrScript
import com.koodoreader.feature.ocr.OcrSearchRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [PdfOcrIndexer] — the scan-to-index pass of the PDF reader's OCR sheet.
 *
 * This is the OC⇄PDF seam that can go wrong silently: a stuck pass, a lost
 * progress bar, an aborted run on the first bad page, or minutes of pointless
 * work after the ML Kit model failed to install. All of it is asserted here with
 * the module's own in-memory doubles, so no device, WebView or Play services are
 * involved.
 */
class PdfOcrIndexerTest {

    private class FakeImage : OcrImage

    /** `textFor(page) == null` makes the engine throw, i.e. a recognition failure. */
    private class FakeEngine(private val textFor: (Int) -> String?) : OcrEngine {
        var calls = 0
            private set
        val pages = mutableListOf<Int>()

        override suspend fun recognize(request: OcrRequest, image: OcrImage): OcrPageResult {
            calls++
            pages += request.pageIndex
            val text = textFor(request.pageIndex)
                ?: throw IllegalStateException("engine failure on page ${request.pageIndex}")
            return OcrPageResult(
                bookKey = request.bookKey,
                pageIndex = request.pageIndex,
                script = request.script,
                lines = text.lines().map { OcrLine(it) },
            )
        }
    }

    private fun repository(
        engine: OcrEngine,
        downloader: InMemoryOnDemandDownloader? = null,
    ) = OcrSearchRepository(
        engine = engine,
        store = InMemoryOcrIndexStore(),
        downloader = downloader,
    )

    @Test
    fun `indexes every page of the range and reports progress in order`() = runBlocking {
        val engine = FakeEngine { "page text $it" }
        val progress = mutableListOf<Pair<Int, Int>>()

        val summary = PdfOcrIndexer.run(
            repository = repository(engine),
            bookKey = "book-1",
            script = OcrScript.LATIN,
            pages = 1..3,
            raster = { FakeImage() },
            onProgress = { done, total -> progress += done to total },
        )

        assertEquals(3, summary.indexed)
        assertEquals(0, summary.failed)
        assertTrue(!summary.stoppedEarly)
        assertEquals(listOf(1, 2, 3), engine.pages)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun `a page with no recognised text is counted as empty, not failed`() = runBlocking {
        val engine = FakeEngine { page -> if (page == 2) "" else "text $page" }

        val summary = PdfOcrIndexer.run(
            repository = repository(engine),
            bookKey = "book-2",
            script = OcrScript.LATIN,
            pages = 1..3,
            raster = { FakeImage() },
        )

        assertEquals(2, summary.indexed)
        assertEquals(1, summary.empty)
        assertEquals(0, summary.failed)
        assertEquals(3, summary.attempted)
    }

    @Test
    fun `a page that cannot be rasterised is skipped and the pass continues`() = runBlocking {
        val engine = FakeEngine { "text $it" }

        val summary = PdfOcrIndexer.run(
            repository = repository(engine),
            bookKey = "book-3",
            script = OcrScript.LATIN,
            pages = 1..4,
            // Page 2 failed to render (engine timeout / bad page object).
            raster = { page -> if (page == 2) null else FakeImage() },
        )

        assertEquals(3, summary.indexed)
        assertEquals(1, summary.failed)
        assertTrue(!summary.stoppedEarly)
        // The rasteriser gap must not cost the remaining pages.
        assertEquals(listOf(1, 3, 4), engine.pages)
    }

    @Test
    fun `an engine failure only loses its own page`() = runBlocking {
        val engine = FakeEngine { page -> if (page == 2) null else "text $page" }

        val summary = PdfOcrIndexer.run(
            repository = repository(engine),
            bookKey = "book-4",
            script = OcrScript.LATIN,
            pages = 1..3,
            raster = { FakeImage() },
        )

        assertEquals(2, summary.indexed)
        assertEquals(1, summary.failed)
        assertEquals(3, summary.attempted)
    }

    @Test
    fun `a missing ML Kit model stops the pass instead of grinding through the book`() = runBlocking {
        val engine = FakeEngine { "text $it" }
        val downloader = InMemoryOnDemandDownloader(failWith = "no Play services")

        val summary = PdfOcrIndexer.run(
            repository = repository(engine, downloader),
            bookKey = "book-5",
            script = OcrScript.CHINESE,
            pages = 1..500,
            raster = { FakeImage() },
        )

        assertTrue(summary.stoppedEarly)
        assertEquals("no Play services", summary.modelUnavailableReason)
        // Nothing was recognised and, crucially, page 2 was never attempted.
        assertEquals(0, summary.indexed)
        assertEquals(0, engine.calls)
        assertEquals(0, summary.attempted)
    }

    @Test
    fun `an installed model is not re-downloaded`() = runBlocking {
        val engine = FakeEngine { "text $it" }
        val downloader = InMemoryOnDemandDownloader(
            installed = setOf("mlkit-text-recognition-latin"),
        )

        val summary = PdfOcrIndexer.run(
            repository = repository(engine, downloader),
            bookKey = "book-6",
            script = OcrScript.LATIN,
            pages = 1..2,
            raster = { FakeImage() },
        )

        assertEquals(2, summary.indexed)
        assertTrue(downloader.downloadAttempts.isEmpty())
        assertNull(summary.modelUnavailableReason)
    }

    @Test
    fun `an empty range is a no-op`() = runBlocking {
        val engine = FakeEngine { "text $it" }
        val summary = PdfOcrIndexer.run(
            repository = repository(engine),
            bookKey = "book-7",
            script = OcrScript.LATIN,
            pages = IntRange.EMPTY,
            raster = { FakeImage() },
        )
        assertEquals(0, summary.attempted)
        assertEquals(0, engine.calls)
    }

    @Test
    fun `the pass feeds the same repository the search reads`() = runBlocking {
        val engine = FakeEngine { page -> "scanned needle on page $page" }
        val repo = repository(engine)

        PdfOcrIndexer.run(
            repository = repo,
            bookKey = "book-8",
            script = OcrScript.LATIN,
            pages = 1..3,
            raster = { FakeImage() },
        )

        val hits = repo.search("needle", bookKey = "book-8")
        assertEquals(listOf(1, 2, 3), hits.map { it.pageIndex })
        assertEquals(3, repo.indexedPages("book-8"))
    }
}
