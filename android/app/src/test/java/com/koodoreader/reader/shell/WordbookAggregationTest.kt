package com.koodoreader.reader.shell

import com.koodoreader.core.data.entity.WordEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Word list rules: what is kept, how it is ordered, how it is searched.
 *
 * These are pure JVM assertions on purpose — the row shape comes straight from
 * the desktop `words` table, so any regression here silently loses vocabulary
 * the user collected over months.
 */
class WordbookAggregationTest {

    private fun word(
        key: String,
        word: String,
        sentence: String? = null,
        book: String? = "b1",
        date: String? = "2026-11-19",
        chapter: String? = null,
    ) = WordEntity(
        key = key,
        bookKey = book,
        date = date,
        word = word,
        sentence = sentence,
        chapter = chapter,
    )

    private val titles = mapOf("b1" to "Analects", "b2" to "Dao De Jing")

    @Test
    fun `rows without a word are dropped rather than rendered blank`() {
        val items = WordbookAggregation.build(
            listOf(word("1", "仁"), word("2", "   "), word("3", "")),
            titles,
        )

        assertEquals(1, items.size)
        assertEquals("仁", items.single().word)
    }

    @Test
    fun `newest words come first`() {
        val items = WordbookAggregation.build(
            listOf(
                word("1", "一", date = "2026-11-01"),
                word("2", "二", date = "2026-11-19"),
                word("3", "三", date = "2026-11-10"),
            ),
            titles,
        )

        assertEquals(listOf("二", "三", "一"), items.map { it.word })
    }

    @Test
    fun `desktop date payloads are normalised so sorting stays chronological`() {
        val items = WordbookAggregation.build(
            listOf(
                word("1", "一", date = """{"date":"2026-11-01T09:00:00.000Z"}"""),
                word("2", "二", date = "2026-11-19"),
            ),
            titles,
        )

        assertEquals(listOf("二", "一"), items.map { it.word })
        assertEquals("2026-11-01", items.last().dateLabel)
    }

    @Test
    fun `book title and chapter are carried for the row subtitle`() {
        val items = WordbookAggregation.build(
            listOf(word("1", "仁", book = "b1", chapter = "Ch 4")),
            titles,
        )

        assertEquals("Analects", items.single().bookTitle)
        assertEquals("Ch 4", items.single().chapter)
    }

    @Test
    fun `search covers the word, the sentence and the book title`() {
        val items = WordbookAggregation.build(
            listOf(
                word("1", "仁", sentence = "仁者爱人", book = "b1"),
                word("2", "道", sentence = "道可道", book = "b2"),
            ),
            titles,
        )

        assertEquals(listOf("仁"), WordbookAggregation.search(items, "爱人").map { it.word })
        assertEquals(listOf("道"), WordbookAggregation.search(items, "dao de").map { it.word })
        assertEquals(listOf("仁"), WordbookAggregation.search(items, "仁").map { it.word })
    }

    @Test
    fun `search requires every term to match`() {
        val items = WordbookAggregation.build(
            listOf(
                word("1", "仁", sentence = "仁者爱人"),
                word("2", "礼", sentence = "克己复礼"),
            ),
            titles,
        )

        assertEquals(listOf("仁"), WordbookAggregation.search(items, "仁 爱人").map { it.word })
        assertTrue(WordbookAggregation.search(items, "仁 复礼").isEmpty())
    }

    @Test
    fun `blank query returns everything`() {
        val items = WordbookAggregation.build(listOf(word("1", "仁")), titles)

        assertEquals(items, WordbookAggregation.search(items, ""))
        assertEquals(items, WordbookAggregation.search(items, "  "))
    }
}
