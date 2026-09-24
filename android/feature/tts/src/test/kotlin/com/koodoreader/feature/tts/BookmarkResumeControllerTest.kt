package com.koodoreader.feature.tts

import com.koodoreader.engine.text.ByteArrayTextSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

/**
 * DoD [4] — reading-position resume ("断点续播").
 *
 * The desktop behaviour is a Redux string (`speechStartText`) matched with a fuzzy
 * `includes` in both directions; these tests pin that match plus the Android
 * additions (persisted anchor, `:engine:toc` position, numeric fallback) and the
 * `:engine:text` linkage.
 */
class BookmarkResumeControllerTest {

    private val position = TtsReadingPosition(
        bookKey = "book-1",
        spineIndex = 3,
        cfi = "/4/2:10",
        chapterPercent = 0.5f,
        totalPercent = 0.25f,
    )

    private val sentences = listOf("Alpha beta.", "Gamma delta.", "Epsilon zeta.")

    @Test
    fun `anchor round trips through the engine toc reading position`() {
        val anchor = TtsResumeAnchor.of(position, sentenceIndex = 2, anchorText = "Epsilon zeta.", updatedAt = 99L)
        assertEquals("book-1", anchor.bookKey)
        assertEquals(3, anchor.spineIndex)
        assertEquals("/4/2:10", anchor.cfi)
        assertEquals(0.5f, anchor.chapterPercent, 0.0001f)
        assertEquals(0.25f, anchor.totalPercent, 0.0001f)
        assertEquals(2, anchor.sentenceIndex)
        assertEquals(99L, anchor.updatedAt)

        // The anchor can be handed straight back to the P2 progress model.
        assertEquals(position, anchor.toReadingPosition())
    }

    @Test
    fun `anchor can be imported from the desktop speech start text`() {
        val anchor = TtsResumeAnchor.fromDesktop("book-9", spineIndex = 7, speechStartText = "Gamma delta.")
        assertEquals("book-9", anchor.bookKey)
        assertEquals(7, anchor.spineIndex)
        assertEquals("", anchor.cfi)
        assertEquals(0.0f, anchor.totalPercent, 0.0001f)
        assertEquals("Gamma delta.", anchor.anchorText)
        assertEquals(0, anchor.sentenceIndex)
    }

    @Test
    fun `matcher reproduces the desktop fuzzy both way includes`() {
        // Desktop: item.includes(anchor) || anchor.includes(item)
        assertEquals(1, TtsResumeMatcher.findSentenceIndex(sentences, "Gamma delta."))
        assertEquals(1, TtsResumeMatcher.findSentenceIndex(sentences, "Gamma"))
        assertEquals(0, TtsResumeMatcher.findSentenceIndex(sentences, "Alpha beta. Gamma"))
        assertEquals(2, TtsResumeMatcher.findSentenceIndex(sentences, "  Epsilon zeta.  "))
        assertEquals(-1, TtsResumeMatcher.findSentenceIndex(sentences, "nothing like this"))
        assertEquals(-1, TtsResumeMatcher.findSentenceIndex(sentences, "   "))
        assertEquals(-1, TtsResumeMatcher.findSentenceIndex(emptyList(), "Alpha"))
    }

    @Test
    fun `resolve prefers the text anchor and falls back to the stored index`() {
        val byText = TtsResumeMatcher.resolve(
            sentences,
            TtsResumeAnchor.fromDesktop("book-1", 0, "Gamma delta.", sentenceIndex = 0),
        )
        assertEquals(1, byText.sentenceIndex)
        assertTrue(byText.matchedByText)
        assertTrue(byText.resumes)

        // Text changed → the numeric index still resumes roughly in place.
        val byIndex = TtsResumeMatcher.resolve(
            sentences,
            TtsResumeAnchor.fromDesktop("book-1", 0, "text that no longer exists", sentenceIndex = 2),
        )
        assertEquals(2, byIndex.sentenceIndex)
        assertFalse(byIndex.matchedByText)

        // Neither matches → desktop fallback: start at the beginning of the chapter.
        val fallback = TtsResumeMatcher.resolve(
            sentences,
            TtsResumeAnchor.fromDesktop("book-1", 0, "gone", sentenceIndex = 99),
        )
        assertEquals(0, fallback.sentenceIndex)
        assertFalse(fallback.resumes)

        // No anchor at all → nothing to resume.
        val none = TtsResumeMatcher.resolve(sentences, null)
        assertNull(none.anchor)
        assertEquals(0, none.sentenceIndex)
        assertFalse(none.resumes)
    }

    @Test
    fun `record restore and clear go through the store`() = runBlocking {
        val store = InMemoryTtsResumeStore()
        var now = 1234L
        val controller = BookmarkResumeController(store) { now }

        val stored = controller.record("book-1", utteranceIndex = 1, anchorText = "Gamma delta.", position = position)
        assertEquals(1, stored.sentenceIndex)
        assertEquals(1234L, stored.updatedAt)
        assertEquals(1, store.size)

        val point = controller.restore("book-1", sentences)
        assertEquals(1, point.sentenceIndex)
        assertTrue(point.matchedByText)
        assertEquals("book-1", controller.lastBookKey())

        // A newer anchor for another book wins the "continue listening" slot.
        now = 2000L
        controller.record("book-2", utteranceIndex = 0, anchorText = "Alpha beta.")
        assertEquals("book-2", controller.lastBookKey())

        controller.clear("book-1")
        val cleared = controller.restore("book-1", sentences)
        assertNull(cleared.anchor)
        assertEquals(0, cleared.sentenceIndex)
        assertNull(store.load("book-1"))
    }

    @Test
    fun `impossible anchors are rejected loudly`() {
        assertThrows(IllegalArgumentException::class.java) {
            TtsResumeAnchor("", 0, "", 0f, 0f, 0, "text")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TtsResumeAnchor("book-1", -1, "", 0f, 0f, 0, "text")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TtsResumeAnchor("book-1", 0, "", 1.5f, 0f, 0, "text")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TtsResumeAnchor("book-1", 0, "", 0f, 0f, -3, "text")
        }
    }

    @Test
    fun `engine text source feeds the splitter and the resume matcher`() = runBlocking {
        val text = "Alpha beta. Gamma delta."
        val source = TextSourceSentenceSource(ByteArrayTextSource(text.toByteArray(Charsets.UTF_8))) {
            it.toString(Charsets.UTF_8)
        }
        assertEquals(text, source.chapterText())
        assertEquals(listOf("Alpha beta.", "Gamma delta."), source.sentences())
        val utterances = source.utterances()
        assertEquals(2, utterances.size)
        assertEquals(0, utterances[0].charStart)
        assertEquals(text.indexOf("Gamma"), utterances[1].charStart)

        val store = InMemoryTtsResumeStore()
        val controller = BookmarkResumeController(store) { 7L }
        controller.record("book-3", utteranceIndex = 1, anchorText = utterances[1].text)
        val point = controller.restore("book-3", source)
        assertEquals(1, point.sentenceIndex)
        assertTrue(point.matchedByText)
    }

    @Test
    fun `sink writes the session resume points on the supplied scope`() = runBlocking {
        val store = InMemoryTtsResumeStore()
        val controller = BookmarkResumeController(store) { 55L }
        val sink = controller.sink(this) { position }

        sink.onResumePoint("book-1", 2, "Epsilon zeta.", 55L)
        // Drain the launched write.
        coroutineContext[Job]?.children?.toList()?.forEach { it.join() }

        val anchor = store.load("book-1")
        assertEquals(2, anchor?.sentenceIndex)
        assertEquals("Epsilon zeta.", anchor?.anchorText)
        // The provider supplied the reader position at record time.
        assertEquals(3, anchor?.spineIndex)
        assertEquals(55L, anchor?.updatedAt)
    }
}
