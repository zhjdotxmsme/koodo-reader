package com.koodoreader.feature.ocr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanned-page closed loop: recognise → normalise → index → search. Uses a
 * fake engine plus the in-memory index, so the whole flow is asserted without
 * ML Kit, Android or a database.
 */
class OcrSearchRepositoryTest {

    private object FakeImage : OcrImage

    private class FakeEngine(
        private val lines: List<String>,
        private val durationMillis: Long = 12L,
        private val failure: String? = null,
    ) : OcrEngine {
        var calls = 0
            private set

        override suspend fun recognize(request: OcrRequest, image: OcrImage): OcrPageResult {
            calls++
            failure?.let { throw IllegalStateException(it) }
            return OcrPageResult(
                bookKey = request.bookKey,
                pageIndex = request.pageIndex,
                script = request.script,
                lines = lines.map { OcrLine(it) },
                durationMillis = durationMillis,
            )
        }
    }

    private val store = InMemoryOcrIndexStore()

    private fun repository(
        engine: OcrEngine,
        downloader: OnDemandDownloader? = InMemoryOnDemandDownloader(),
    ) = OcrSearchRepository(
        engine = engine,
        store = store,
        downloader = downloader,
        nowMillis = { 1_700_000_000_000L },
    )

    private fun request(pageIndex: Int, script: OcrScript = OcrScript.LATIN) =
        OcrRequest(bookKey = "book-a", pageIndex = pageIndex, script = script)

    @Test
    fun `indexing a scanned page stores the normalised text`() = runBlocking {
        val engine = FakeEngine(listOf("Scanning", "the page"))
        val receipt = repository(engine).indexPage(request(0), FakeImage)

        assertTrue(receipt is OcrIndexReceipt.Indexed)
        val indexed = receipt as OcrIndexReceipt.Indexed
        assertEquals("book-a#0#LATIN", indexed.key)
        assertEquals(17, indexed.characters)
        assertEquals(3, indexed.tokens)
        assertEquals(12L, indexed.durationMillis)
        assertEquals(1, store.size())
        val row = store.all().single()
        assertEquals("Scanning the page", row.normalizedText)
        assertEquals("Scanning\nthe page", row.text)
        assertEquals(1_700_000_000_000L, row.recognizedAt)
        assertEquals(OcrPageEntity.ENGINE_MLKIT, row.engine)
    }

    @Test
    fun `re-scanning a page replaces its row instead of duplicating it`() = runBlocking {
        val repository = repository(FakeEngine(listOf("first pass")))
        repository.indexPage(request(3), FakeImage)
        repository.indexPage(request(3), FakeImage)

        assertEquals(1, store.size())
        assertEquals("first pass", store.all().single().normalizedText)
    }

    @Test
    fun `an empty page is not indexed`() = runBlocking {
        val repository = repository(FakeEngine(listOf("", "   ")))
        val receipt = repository.indexPage(request(1), FakeImage)

        assertEquals(OcrIndexReceipt.EmptyPage(1), receipt)
        assertEquals(0, store.size())
    }

    @Test
    fun `a page is indexed in the language it was scanned with`() = runBlocking {
        val repository = repository(FakeEngine(listOf("扫描的", "页面文字")))
        val receipt = repository.indexPage(request(2, OcrScript.CHINESE), FakeImage)

        assertTrue(receipt is OcrIndexReceipt.Indexed)
        assertEquals("book-a#2#CHINESE", (receipt as OcrIndexReceipt.Indexed).key)
        assertEquals("扫描的页面文字", store.all().single().normalizedText)
    }

    @Test
    fun `a missing model is reported and nothing is indexed`() = runBlocking {
        val downloader = InMemoryOnDemandDownloader(failWith = "no play services")
        val repository = repository(FakeEngine(listOf("text")), downloader)
        val receipt = repository.indexPage(request(0), FakeImage)

        assertTrue(receipt is OcrIndexReceipt.ModelUnavailable)
        val unavailable = receipt as OcrIndexReceipt.ModelUnavailable
        assertEquals(OcrScript.LATIN, unavailable.script)
        assertEquals("no play services", unavailable.reason)
        assertEquals(0, store.size())
    }

    @Test
    fun `the model is downloaded once for many pages`() = runBlocking {
        val downloader = InMemoryOnDemandDownloader()
        val repository = repository(FakeEngine(listOf("page text")), downloader)
        repository.indexPage(request(0), FakeImage)
        repository.indexPage(request(1), FakeImage)
        repository.indexPage(request(2), FakeImage)

        assertEquals(1, downloader.downloadAttempts.size)
        assertEquals(3, store.size())
    }

    @Test
    fun `an engine failure is returned instead of thrown`() = runBlocking {
        val repository = repository(FakeEngine(listOf("x"), failure = "ml kit exploded"))
        val receipt = repository.indexPage(request(0), FakeImage)

        assertEquals(OcrIndexReceipt.Failed("ml kit exploded"), receipt)
        assertEquals(0, store.size())
    }

    @Test
    fun `search ranks the verbatim page first and filters weak matches`() = runBlocking {
        val repository = repository(FakeEngine(listOf("placeholder")))
        repository.indexPage(OcrRequest("book-a", 1, OcrScript.LATIN), FakeImage) // placeholder
        store.upsertAll(
            listOf(
                page("book-a", 5, "the quiet reader sat by the window"),
                page("book-a", 9, "a quiet morning, the reader left"),
                page("book-a", 12, "totally unrelated page"),
            ),
        )

        val hits = repository.search("quiet reader")
        assertEquals(listOf(5, 9), hits.map { it.pageIndex })
        assertTrue(hits[0].score > hits[1].score)
        assertEquals(2, hits[0].matchedTokens)
        assertEquals(1.0, hits[0].score, 0.0001)
        assertTrue(hits.none { it.pageIndex == 12 })
    }

    @Test
    fun `search matches CJK through the bigram index`() = runBlocking {
        val repository = repository(FakeEngine(listOf("x")))
        store.upsertAll(
            listOf(
                page("book-a", 1, "第一章 阅读的方法", OcrScript.CHINESE),
                page("book-a", 2, "无关的一页", OcrScript.CHINESE),
            ),
        )

        val hits = repository.search("阅读", bookKey = "book-a")
        assertEquals(listOf(1), hits.map { it.pageIndex })
        assertTrue(hits[0].snippet.contains("阅读"))
    }

    @Test
    fun `search can be scoped to one book and limited`() = runBlocking {
        val repository = repository(FakeEngine(listOf("x")))
        store.upsertAll(
            listOf(
                page("book-a", 1, "shared phrase here"),
                page("book-b", 1, "shared phrase here too"),
                page("book-b", 2, "shared phrase here again"),
            ),
        )

        assertEquals(1, repository.search("shared phrase", bookKey = "book-a").size)
        val limited = repository.search("shared phrase", limit = 1)
        assertEquals(1, limited.size)
        assertEquals("book-a", limited[0].bookKey)
    }

    @Test
    fun `snippets carry ellipses and stay near the match`() = runBlocking {
        val repository = repository(FakeEngine(listOf("x")))
        val long = "prefix ".repeat(20) + "needle in the haystack " + "suffix ".repeat(20)
        store.upsertAll(listOf(page("book-a", 4, long.trim())))

        val hit = repository.search("needle").single()
        assertTrue(hit.snippet.startsWith("…"))
        assertTrue(hit.snippet.endsWith("…"))
        assertTrue(hit.snippet.contains("needle in the haystack"))
    }

    @Test
    fun `blank queries and unknown books return nothing`() = runBlocking {
        val repository = repository(FakeEngine(listOf("some text")))
        repository.indexPage(request(0), FakeImage)

        assertTrue(repository.search("   ").isEmpty())
        assertTrue(repository.search("text", bookKey = "missing").isEmpty())
    }

    @Test
    fun `index size and book removal are exposed for the reader`() = runBlocking {
        val repository = repository(FakeEngine(listOf("page text")))
        repository.indexPage(request(0), FakeImage)
        repository.indexPage(request(1), FakeImage)
        assertEquals(2, repository.indexedPages("book-a"))

        repository.forgetBook("book-a")
        assertEquals(0, repository.size())
    }

    private fun page(
        bookKey: String,
        pageIndex: Int,
        normalizedText: String,
        script: OcrScript = OcrScript.LATIN,
    ): OcrPageEntity = OcrPageEntity(
        key = OcrSearchRepository.pageKey(bookKey, pageIndex, script),
        bookKey = bookKey,
        pageIndex = pageIndex,
        script = script.name,
        text = normalizedText,
        normalizedText = normalizedText,
        tokenCount = OcrTextNormalizer.tokens(normalizedText).size,
        recognizedAt = 1L,
    )
}
