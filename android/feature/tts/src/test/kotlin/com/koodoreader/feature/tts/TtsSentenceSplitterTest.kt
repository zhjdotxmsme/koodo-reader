package com.koodoreader.feature.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port fidelity of the desktop listening-text pipeline
 * (`src/utils/common.ts`: `detectLocalLanguage` + `splitSentences`).
 *
 * These are the tests that protect the 150 / 50 chunking, the greedy long-sentence
 * merge and the "must contain a letter or digit" filter — i.e. the parts of the
 * desktop behaviour the Android engine sees as *utterance boundaries*, and
 * therefore the parts that decide how highlighting and resume points line up.
 */
class TtsSentenceSplitterTest {

    @Test
    fun `language detection mirrors the desktop CJK ratio rule`() {
        assertEquals("en", TtsLanguageDetector.detect("Hello world. This is a book."))
        assertEquals("en", TtsLanguageDetector.detect(""))
        assertEquals("zh", TtsLanguageDetector.detect("这是一本中文书。"))
        assertEquals("ja", TtsLanguageDetector.detect("これはにほんごのほんです。"))
        assertEquals("ko", TtsLanguageDetector.detect("이것은 한국어 책입니다."))
        // 30% threshold: mostly Latin text with a few CJK characters stays "en".
        val mixed = "Hello world, " + "中".repeat(2) + " and more English words here to dilute the ratio."
        assertEquals("en", TtsLanguageDetector.detect(mixed))
    }

    @Test
    fun `default chunk length follows the detected language`() {
        assertEquals(150, TtsSentenceSplitter.defaultMaxLength("en"))
        assertEquals(50, TtsSentenceSplitter.defaultMaxLength("zh"))
        assertEquals(50, TtsSentenceSplitter.defaultMaxLength("ja"))
        assertEquals(50, TtsSentenceSplitter.defaultMaxLength("ko"))
    }

    @Test
    fun `splits on terminators and keeps closing quotes attached`() {
        val chunks = TtsSentenceSplitter.split("Hello world. \"How are you?\" Fine!", 150)
        assertEquals(listOf("Hello world.", "\"How are you?\"", "Fine!"), chunks)
    }

    @Test
    fun `does not split decimals ellipses or unpunctuated text`() {
        assertEquals(listOf("Pi is 3.14 exactly?"), TtsSentenceSplitter.split("Pi is 3.14 exactly?", 150))
        assertEquals(listOf("Wait... really?"), TtsSentenceSplitter.split("Wait... really?", 150))
        assertEquals(listOf("no terminator here"), TtsSentenceSplitter.split("no terminator here", 150))
    }

    @Test
    fun `drops whitespace-only and symbol-only chunks like the desktop filter`() {
        val chunks = TtsSentenceSplitter.split("First.   ---   . Second.", 150)
        assertEquals(listOf("First.", "Second."), chunks)
        assertTrue(TtsSentenceSplitter.split("   \n\n  ", 150).isEmpty())
        assertFalse(chunks.any { it.isBlank() })
    }

    @Test
    fun `long sentences are split on soft punctuation and merged greedily`() {
        // Desktop `splitLongSentence`: parts come from /(?<=[,，;；:：、…])/, then are
        // merged while they still fit into maxLength.
        val text = "aa, bb, cc, dd."
        val chunks = TtsSentenceSplitter.split(text, 8)
        assertEquals(listOf("aa,bb,", "cc,dd."), chunks)
        assertTrue(chunks.all { it.length <= 8 })
        // The greedy merge keeps every part: nothing is dropped.
        assertEquals("aa,bb,cc,dd.", chunks.joinToString(""))
    }

    @Test
    fun `a part longer than the limit is kept whole like the desktop does`() {
        val sentence = "one two three four five, six seven eight nine ten."
        val chunks = TtsSentenceSplitter.split(sentence, 20)
        // Each soft part alone already exceeds 20, so nothing can be merged — but the
        // parts are still emitted, exactly as `splitLongSentence` does on the desktop.
        assertEquals(
            listOf("one two three four five,", "six seven eight nine ten."),
            chunks,
        )
    }

    @Test
    fun `a single unbreakable chunk longer than the limit is kept whole`() {
        val word = "a".repeat(80)
        assertEquals(listOf(word), TtsSentenceSplitter.split(word, 20))
    }

    @Test
    fun `splitWithOffsets reports offsets into the original text`() {
        val text = "Alpha beta. Gamma delta."
        val utterances = TtsSentenceSplitter.splitWithOffsets(text, 150)
        assertEquals(2, utterances.size)
        assertEquals("Alpha beta.", utterances[0].text)
        assertEquals(0, utterances[0].charStart)
        assertEquals(11, utterances[0].charEnd)
        assertEquals("Gamma delta.", utterances[1].text)
        assertEquals(text.indexOf("Gamma"), utterances[1].charStart)
        assertEquals(text.length, utterances[1].charEnd)
        assertEquals(utterances[1].index, 1)
        assertTrue(utterances[1].length > 0)
    }

    @Test
    fun `chinese text chunks at fifty characters`() {
        val sentence = "这是一句中文。"  // 7 chars
        val text = sentence.repeat(20)   // 140 chars, 20 sentences
        val chunks = TtsSentenceSplitter.split(text)
        assertEquals(20, chunks.size)
        assertEquals(sentence, chunks.first())

        // 4 × 16 = 64 chars with no sentence terminator: one segment for the
        // segmenter, longer than the 50-char limit, so `splitLongSentence` cuts it at
        // the soft punctuation (，) and merges greedily into [48, 16].
        val clause = "这是一个很长很长的中文句子之一，"
        assertEquals(16, clause.length)
        val longZh = clause.repeat(4)
        assertEquals(64, longZh.length)
        val split = TtsSentenceSplitter.split(longZh)
        assertEquals(2, split.size)
        assertTrue("chunks must respect the CJK limit: $split", split.all { it.length <= 50 })
        assertEquals(longZh, split.joinToString(""))
    }
}
