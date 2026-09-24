package com.koodoreader.feature.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P6 acceptance #4: 翻译历史持久化与检索. */
class HistoryRepositoryTest {

    private val sink = RecordingLogger()
    private val dao = FakeTranslationHistoryDao()

    private fun repository(maxEntries: Int = TranslationHistoryRepository.DEFAULT_MAX_ENTRIES) =
        TranslationHistoryRepository(dao, sink, FakeClock(), maxEntries = maxEntries)

    @Test
    fun `records are listed newest first`() = runBlocking {
        val repository = repository()

        repository.record("first text", "第一条", "zh-CN", TranslationSourceId.GOOGLE)
        repository.record("second text", "第二条", "zh-CN", TranslationSourceId.GOOGLE)
        repository.record("third text", "第三条", "zh-CN", TranslationSourceId.DEEPL)

        val recent = repository.recent(2)

        assertEquals(listOf("third text", "second text"), recent.map { it.sourceText })
        assertEquals(3L, repository.count())
        assertTrue(recent.first().createdAt > recent.last().createdAt)
    }

    @Test
    fun `the same selection translated again updates the existing row`() = runBlocking {
        val repository = repository()

        repository.record("Hello world", "你好世界", "zh-CN", TranslationSourceId.GOOGLE)
        repository.record("Hello world", "你好，世界", "zh-CN", TranslationSourceId.GOOGLE)

        assertEquals(1L, repository.count())
        assertEquals("你好，世界", repository.recent(1).single().translatedText)
        assertEquals(2, dao.upsertCount)
    }

    @Test
    fun `schema matches the documented table`() = runBlocking {
        val repository = repository()

        val entry = repository.record(
            sourceText = "Hello",
            translatedText = "你好",
            targetLang = "zh-CN",
            provider = TranslationSourceId.MICROSOFT,
            sourceLang = "en",
            bookKey = "bk-9",
            cfi = "epubcfi(/6/2)",
        )

        assertEquals("azure-translate-plugin", entry.provider)
        assertEquals("en", entry.sourceLang)
        assertEquals("bk-9", entry.bookKey)
        assertEquals("epubcfi(/6/2)", entry.cfi)
        assertEquals(repository.stableKey("Hello", "zh-CN", TranslationSourceId.MICROSOFT), entry.key)
        assertTrue(dao.findByKey(entry.key) != null)
    }

    @Test
    fun `different provider or target language keeps separate rows`() = runBlocking {
        val repository = repository()

        repository.record("Hello", "你好", "zh-CN", TranslationSourceId.GOOGLE)
        repository.record("Hello", "你好", "zh-CN", TranslationSourceId.DEEPL)
        repository.record("Hello", "Hello", "en", TranslationSourceId.GOOGLE)

        assertEquals(3L, repository.count())
    }

    @Test
    fun `search matches both source and translated text case insensitively`() = runBlocking {
        val repository = repository()
        repository.record("The Lighthouse Keeper", "灯塔守护者", "zh-CN", TranslationSourceId.GOOGLE)
        repository.record("Another chapter", "另一章", "zh-CN", TranslationSourceId.GOOGLE)

        assertEquals(1, repository.search("lighthouse").size)
        assertEquals(1, repository.search("守护").size)
        assertTrue(repository.search("nothing here").isEmpty())
        assertEquals(2, repository.search("   ").size)
    }

    @Test
    fun `history can be filtered by book`() = runBlocking {
        val repository = repository()
        repository.record("a", "甲", "zh-CN", TranslationSourceId.GOOGLE, bookKey = "bk-1")
        repository.record("b", "乙", "zh-CN", TranslationSourceId.GOOGLE, bookKey = "bk-2")

        assertEquals(listOf("a"), repository.forBook("bk-1").map { it.sourceText })
    }

    @Test
    fun `retention keeps only the newest entries`() = runBlocking {
        val repository = repository(maxEntries = 3)

        (1..5).forEach { index ->
            repository.record("text $index", "译文 $index", "zh-CN", TranslationSourceId.GOOGLE)
        }

        val remaining = repository.recent(10)
        assertEquals(3, remaining.size)
        assertEquals(listOf("text 5", "text 4", "text 3"), remaining.map { it.sourceText })
        assertTrue(repository.search("text 1").isEmpty())
    }

    @Test
    fun `explicit prune removes everything beyond the newest n`() = runBlocking {
        val repository = repository(maxEntries = 100)
        (1..4).forEach { index -> repository.record("text $index", "t$index", "zh-CN", TranslationSourceId.GOOGLE) }

        val removed = repository.prune(keep = 2)

        assertEquals(2, removed)
        assertEquals(listOf("text 4", "text 3"), repository.recent(10).map { it.sourceText })
        assertTrue(sink.infos.any { it.contains("translation history pruned: removed=2 keep=2") })
    }

    @Test
    fun `clear empties the table`() = runBlocking {
        val repository = repository()
        repository.record("Hello", "你好", "zh-CN", TranslationSourceId.GOOGLE)

        repository.clear()

        assertEquals(0L, repository.count())
        assertTrue(repository.recent(10).isEmpty())
        assertTrue(sink.infos.any { it.contains("translation history cleared") })
    }

    @Test
    fun `stable key is deterministic, content free and provider aware`() {
        val repository = repository()

        val first = repository.stableKey("Hello world", "zh-CN", TranslationSourceId.GOOGLE)
        val again = repository.stableKey("Hello world", "zh-CN", TranslationSourceId.GOOGLE)
        val otherTarget = repository.stableKey("Hello world", "en", TranslationSourceId.GOOGLE)
        val otherProvider = repository.stableKey("Hello world", "zh-CN", TranslationSourceId.DEEPL)

        assertEquals(first, again)
        assertNotEquals(first, otherTarget)
        assertNotEquals(first, otherProvider)
        assertFalse(first.contains("hello"))
        assertTrue(first.startsWith("google-trans"))
    }

    @Test
    fun `in memory matcher mirrors the sql like semantics`() {
        val repository = repository()
        val entry = TranslationHistoryEntity(
            key = "k",
            sourceText = "The Lighthouse Keeper",
            translatedText = "灯塔守护者",
            targetLang = "zh-CN",
            provider = "google-translate-plugin",
            createdAt = 0L,
        )

        assertTrue(repository.matches(entry, "lighthouse"))
        assertTrue(repository.matches(entry, "守护"))
        assertTrue(repository.matches(entry, "  "))
        assertFalse(repository.matches(entry, "harbour"))
    }

    @Test
    fun `history logging carries lengths only, never the selected text`() = runBlocking {
        val repository = repository()

        repository.record("Very private sentence about the author", "非常私密的句子", "zh-CN", TranslationSourceId.GOOGLE)

        val selected = "Very private sentence about the author"
        val log = sink.text()
        assertTrue(log.contains("translation history recorded: provider=google-translate-plugin"))
        assertTrue(log.contains("sourceChars=${selected.length}"))
        assertFalse(log.contains(selected))
        assertFalse(log.contains("非常私密的句子"))
    }
}
