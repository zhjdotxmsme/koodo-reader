package com.koodoreader.reader.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koodoreader.core.data.KoodoDatabaseProvider
import com.koodoreader.core.data.entity.BookmarkEntity
import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.engine.annotate.AnnotationCodec
import com.koodoreader.engine.annotate.AnnotationKind
import com.koodoreader.engine.annotate.BookmarkRow
import com.koodoreader.engine.annotate.NoteRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Which slice of the annotation store the Notes tab shows. */
enum class NotesFilter(val labelKey: String) {
    ALL("All"),
    /** `notes` rows: highlights and notes both live there (see AnnotationKind). */
    HIGHLIGHTS("Highlights"),
    BOOKMARKS("Bookmarks"),
}

/**
 * One annotation, detached from storage so the list logic can be tested without
 * Room. Produced from [com.koodoreader.engine.annotate.Annotation], which is the
 * single owner of the three-state rule.
 */
data class NoteListItem(
    val key: String,
    val bookKey: String,
    val kind: AnnotationKind,
    val selectedText: String,
    val noteText: String,
    /** Bookmark label. Empty for `notes` rows — see [AnnotationCodec]. */
    val label: String = "",
    val chapter: String,
    val percentage: String,
    val cfi: String?,
) {
    /**
     * What the row shows: the highlight, else the note body, else the bookmark
     * label, else the chapter.
     *
     * The label step is load-bearing: a bookmark has no selected text and no note
     * body, so without it EVERY bookmark row would render blank. Caught by
     * `NotesAggregationTest`, which is why that test asserts the label survives
     * the mapping.
     */
    val displayText: String
        get() = selectedText.ifBlank { noteText }.ifBlank { label }.ifBlank { chapter }

    val hasNote: Boolean get() = noteText.isNotBlank()
}

/** All annotations of one book, in the order they were produced. */
data class NoteBookSection(
    val bookKey: String,
    val bookTitle: String,
    val items: List<NoteListItem>,
)

/**
 * Pure list logic for the Notes tab.
 *
 * Split out from the ViewModel deliberately: this is the part with rules worth
 * asserting (what each filter means, how rows group, that nothing is dropped),
 * and it needs no Android, no Room and no coroutines to test.
 */
object NotesAggregation {

    /**
     * A highlight and a note are the SAME storage row type (`notes`), separated
     * only by whether `notes` is blank — that rule belongs to
     * [AnnotationCodec.noteRowToAnnotation] and is not re-derived here. So the
     * "Highlights" filter means "everything in the notes table", which is how
     * the desktop groups them too.
     */
    fun matches(item: NoteListItem, filter: NotesFilter): Boolean = when (filter) {
        NotesFilter.ALL -> true
        NotesFilter.HIGHLIGHTS ->
            item.kind == AnnotationKind.HIGHLIGHT || item.kind == AnnotationKind.NOTE
        NotesFilter.BOOKMARKS -> item.kind == AnnotationKind.BOOKMARK
    }

    fun filter(items: List<NoteListItem>, filter: NotesFilter): List<NoteListItem> =
        items.filter { matches(it, filter) }

    /**
     * Group by book. Books are ordered by title (case-insensitive) so the list is
     * stable as annotations are added; items keep their incoming order.
     *
     * Books with no matching item are omitted rather than rendered as empty
     * headers — an empty section reads as "this book has nothing" when the truth
     * may be "the filter excludes it".
     */
    fun group(items: List<NoteListItem>, titles: Map<String, String>): List<NoteBookSection> =
        items.groupBy { it.bookKey }
            .map { (bookKey, list) ->
                NoteBookSection(
                    bookKey = bookKey,
                    // A deleted book can still own annotations; never render a
                    // blank header for it.
                    bookTitle = titles[bookKey]?.takeIf { it.isNotBlank() }
                        ?: bookKey.takeIf { it.isNotBlank() }
                        // `bookKey` is nullable in Room, so an imported row may
                        // have none. Dropping it would hide real data and
                        // printing the empty key would show a blank header, so
                        // use a neutral marker.
                        ?: "\u2014",
                    items = list,
                )
            }
            .sortedBy { it.bookTitle.lowercase() }

    /**
     * Totals for the reconciliation check: the number of rows the list is
     * showing, and how they split. Callers assert this equals the rows the DAOs
     * produced, so a bug that silently drops annotations cannot pass as "clean".
     */
    fun totalCount(sections: List<NoteBookSection>): Int = sections.sumOf { it.items.size }

    fun countOf(items: List<NoteListItem>, filter: NotesFilter): Int = items.count { matches(it, filter) }
}

/** State rendered by [NotesScreen]. */
data class NotesUiState(
    val items: List<NoteListItem> = emptyList(),
    val titles: Map<String, String> = emptyMap(),
    val loaded: Boolean = false,
)

/**
 * Cross-book aggregation of highlights, notes and bookmarks.
 *
 * NO schema change and no new DAO: `NoteDao.observeAll()`, `BookmarkDao.observeAll()`
 * and `BookDao.observeAll()` all already exist, so this is a `combine` plus a
 * title lookup. Rows go through `engine:annotate`'s codec so the three-state
 * rule and the CFI handling have exactly one owner.
 */
class NotesViewModel(app: Application) : AndroidViewModel(app) {

    private val db = KoodoDatabaseProvider.get(app)

    val state: StateFlow<NotesUiState> = combine(
        db.noteDao().observeAll(),
        db.bookmarkDao().observeAll(),
        db.bookDao().observeAll(),
    ) { notes, bookmarks, books ->
        NotesUiState(
            items = noteRowsToItems(notes, bookmarks),
            titles = books.associate { it.key to it.name.orEmpty() },
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NotesUiState(),
    )

}

/**
 * Room rows → list items, via `engine:annotate`'s codec so the three-state rule
 * (a `notes` row is a HIGHLIGHT when its `notes` column is blank, a NOTE
 * otherwise) and the CFI handling keep exactly one owner.
 *
 * Top-level and pure on purpose: constructing [NoteEntity]/[BookmarkEntity] in a
 * JVM test needs no Android, so "N note rows + M bookmark rows produce exactly
 * N + M items with the right kinds" is directly assertable — which is the
 * reconciliation the card asks for.
 */
internal fun noteRowsToItems(
    notes: List<NoteEntity>,
    bookmarks: List<BookmarkEntity>,
): List<NoteListItem> = notes.map { note ->
    val a = AnnotationCodec.noteRowToAnnotation(
        NoteRow(
            key = note.key,
            bookKey = note.bookKey,
            date = note.date,
            chapter = note.chapter,
            chapterIndex = note.chapterIndex,
            text = note.text,
            cfi = note.cfi,
            range = note.range,
            notes = note.notes,
            percentage = note.percentage,
            color = note.color,
            tag = note.tag,
        ),
    )
    NoteListItem(
        key = a.key,
        bookKey = a.bookKey,
        kind = a.kind,
        selectedText = a.selectedText,
        noteText = a.noteText,
        label = a.label,
        chapter = a.chapter,
        percentage = a.percentage,
        cfi = a.cfiStart.takeIf { it.isNotBlank() },
    )
} + bookmarks.map { bookmark ->
    val a = AnnotationCodec.bookmarkRowToAnnotation(
        BookmarkRow(
            key = bookmark.key,
            bookKey = bookmark.bookKey,
            cfi = bookmark.cfi,
            label = bookmark.label,
            percentage = bookmark.percentage,
            chapter = bookmark.chapter,
        ),
    )
    NoteListItem(
        key = a.key,
        bookKey = a.bookKey,
        kind = a.kind,
        selectedText = a.selectedText,
        noteText = a.noteText,
        label = a.label,
        chapter = a.chapter,
        percentage = a.percentage,
        cfi = a.cfiStart.takeIf { it.isNotBlank() },
    )
}
