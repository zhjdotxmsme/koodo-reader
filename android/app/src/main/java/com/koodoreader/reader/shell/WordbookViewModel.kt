package com.koodoreader.reader.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koodoreader.core.data.KoodoDatabase
import com.koodoreader.core.data.KoodoDatabaseProvider
import com.koodoreader.core.data.entity.WordEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One saved word, flattened for the list.
 *
 * Room's `words` table is the desktop's vocabulary-builder table (schema.lock),
 * so this screen is a view over data the desktop already understands — nothing
 * new is persisted and a backup/restore round-trip keeps working.
 */
data class WordItem(
    val key: String,
    val word: String,
    val sentence: String,
    val chapter: String,
    val bookKey: String,
    val bookTitle: String,
    /** Pre-formatted for display; the stored value is a desktop date payload. */
    val dateLabel: String,
) {
    val hasSentence: Boolean get() = sentence.isNotBlank()
}

/**
 * Pure list logic for the word list — no Room, no Compose, so the rules are
 * directly assertable.
 */
object WordbookAggregation {

    /**
     * Newest first.
     *
     * A vocabulary list is consulted while reading, so recency beats alphabet:
     * the word saved a minute ago is the one being studied now. Ties fall back
     * to the word itself so the order is stable across recompositions.
     */
    fun build(words: List<WordEntity>, titles: Map<String, String>): List<WordItem> =
        words.mapNotNull { row ->
            val word = row.word?.trim().orEmpty()
            if (word.isEmpty()) return@mapNotNull null
            WordItem(
                key = row.key,
                word = word,
                sentence = row.sentence.orEmpty(),
                chapter = row.chapter.orEmpty(),
                bookKey = row.bookKey.orEmpty(),
                bookTitle = titles[row.bookKey].orEmpty(),
                dateLabel = normalizeDate(row.date),
            )
        }.sortedWith(
            compareByDescending<WordItem> { it.dateLabel }.thenBy { it.word.lowercase() },
        )

    /**
     * Pulls a `yyyy-MM-dd` out of whatever the row carries.
     *
     * The desktop column is a JSON-ish date payload; rows this app writes are
     * plain `yyyy-MM-dd`. Both must sort chronologically, so the date is
     * extracted rather than compared as raw text.
     */
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    fun normalizeDate(raw: String?): String =
        raw?.let { DATE.find(it)?.value }.orEmpty()

    /**
     * Same AND semantics as the Notes tab: every whitespace-separated term has
     * to match, across the word, its example sentence and the book title.
     */
    fun search(items: List<WordItem>, query: String): List<WordItem> {
        val terms = query.trim().lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (terms.isEmpty()) return items
        return items.filter { item ->
            val haystack = buildString {
                append(item.word).append('\n')
                append(item.sentence).append('\n')
                append(item.bookTitle)
            }.lowercase()
            terms.all { haystack.contains(it) }
        }
    }
}

/** Backs [WordbookScreen]. */
class WordbookViewModel(app: Application) : AndroidViewModel(app) {

    private val db = KoodoDatabaseProvider.get(app)

    val items: StateFlow<List<WordItem>> = combine(
        db.wordDao().observeAll(),
        db.bookDao().observeAll(),
    ) { words, books ->
        WordbookAggregation.build(words, books.associate { it.key to it.name.orEmpty() })
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun delete(key: String) {
        viewModelScope.launch(Dispatchers.IO) { db.wordDao().deleteByKey(key) }
    }
}

/**
 * Writing a vocabulary entry — one implementation, two callers (the reader's
 * dictionary popup and [WordbookViewModel]).
 *
 * The `words` row is desktop-shaped: `key` is a UUID, `date` is a plain
 * `yyyy-MM-dd` (the reader in [WordbookAggregation] normalises whatever payload
 * an imported row carries), and `sentence` keeps the passage the word was met
 * in — that is the whole point of a vocabulary builder.
 */
object WordBank {

    suspend fun add(
        db: KoodoDatabase,
        bookKey: String,
        word: String,
        sentence: String,
        chapter: String? = null,
    ) {
        val headword = word.trim()
        if (headword.isEmpty()) return
        db.wordDao().upsert(
            WordEntity(
                key = java.util.UUID.randomUUID().toString(),
                bookKey = bookKey.takeIf { it.isNotBlank() },
                date = isoDate(),
                word = headword,
                sentence = sentence.trim().takeIf { it.isNotEmpty() },
                chapter = chapter?.takeIf { it.isNotBlank() },
            ),
        )
    }

    fun isoDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
