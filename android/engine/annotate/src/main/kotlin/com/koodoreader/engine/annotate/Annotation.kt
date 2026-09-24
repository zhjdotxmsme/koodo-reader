package com.koodoreader.engine.annotate

import java.time.Instant
import java.time.ZoneId

/**
 * Desktop `notes.date` payload: a plain `{ year, month, day }` object in the
 * document's local calendar. Mirrors `src/models/Note.ts`, where `date` is
 * built from `new Date()` (`getMonth()` is 0-based upstream, but the STORED
 * value is `getMonth() + 1`, so [month] here is 1-based).
 */
data class DateParts(
    val year: Int,
    val month: Int,
    val day: Int,
)

/**
 * One annotation: a highlight, a note, or a bookmark (see [AnnotationKind]).
 *
 * This is the unified, platform-neutral model the whole module works with.
 * Field semantics are aligned 1:1 with the desktop `notes` / `bookmarks`
 * rows (`src/models/Note.ts`, schema.lock):
 *
 * | Kotlin field | desktop column / meaning |
 * |---|---|
 * | [key] | `key` — desktop uses the epoch-millis timestamp as key |
 * | [bookKey] | `bookKey` |
 * | [createdAt] | epoch millis; source of both `key` and the `date` object |
 * | [chapter] / [chapterIndex] | `chapter` / `chapterIndex` |
 * | [selectedText] | `text` — the selected string |
 * | [cfiStart] / [cfiEnd] | `cfi` (range) / bookmark point CFI |
 * | [range] | `range` — serialized DOM-range complement |
 * | [noteText] | `notes` — non-empty iff kind == [AnnotationKind.NOTE] |
 * | [percentage] | `percentage` (stored as TEXT on desktop) |
 * | [color] | `color` (hex on desktop; integer code on the Room side) |
 * | [tags] | `tag` — JSON array |
 * | [label] | bookmarks.`label` |
 *
 * The constructor is intentionally permissive (every field has a default)
 * because [AnnotationCodec] needs to rebuild instances from loosely-typed
 * desktop rows. Application code should go through the three typed
 * factories ([highlight] / [note] / [bookmark]), which enforce the state
 * invariants and normalize the CFIs through [CfiAnchor].
 */
data class Annotation(
    val key: String,
    val bookKey: String,
    val kind: AnnotationKind,
    /** Anchor point (bookmark) or range start (highlight / note). */
    val cfiStart: String,
    /** Range end; `null` for a point / bookmark. */
    val cfiEnd: String? = null,
    val color: HighlightColor = HighlightColor.DEFAULT,
    val selectedText: String = "",
    /** User-attached text; empty for a plain highlight, ignored for bookmarks. */
    val noteText: String = "",
    val chapter: String = "",
    val chapterIndex: Int = 0,
    /** Stored as TEXT on desktop (e.g. "0.37" or "0"); kept a string to match. */
    val percentage: String = "0",
    /** Serialized DOM-range coordinates complementing the CFI (desktop `range`). */
    val range: String? = null,
    val tags: List<String> = emptyList(),
    /** Bookmark display name (bookmarks.`label`); unused for highlights / notes. */
    val label: String = "",
    val createdAt: Long = 0L,
) {

    /** True when this annotation covers a range rather than a single point. */
    val isRange: Boolean get() = cfiEnd != null

    /**
     * Local-calendar date parts for the desktop `date` object, derived from
     * [createdAt] in the system default zone (matching `new Date()`).
     */
    fun dateParts(zone: ZoneId = ZoneId.systemDefault()): DateParts {
        if (createdAt <= 0L) return DateParts(year = 1970, month = 1, day = 1)
        val local = Instant.ofEpochMilli(createdAt).atZone(zone)
        return DateParts(year = local.year, month = local.monthValue, day = local.dayOfMonth)
    }

    companion object {
        /**
         * Create a [AnnotationKind.HIGHLIGHT]: a selected range with no note.
         *
         * @param cfiStart / cfiEnd raw point CFIs; both are validated and
         *   canonicalized through [CfiAnchor], and the stored range CFI is
         *   rebuilt with the CFI core's `buildRange` so it matches the
         *   desktop bytes exactly.
         */
        fun highlight(
            bookKey: String,
            cfiStart: String,
            cfiEnd: String,
            selectedText: String,
            chapter: String = "",
            chapterIndex: Int = 0,
            percentage: String = "0",
            color: HighlightColor = HighlightColor.DEFAULT,
            range: String? = null,
            tags: List<String> = emptyList(),
            createdAt: Long = 0L,
            key: String = (createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()).toString(),
        ): Annotation {
            val start = CfiAnchor.requireValid(cfiStart)
            val end = CfiAnchor.requireValid(cfiEnd)
            return Annotation(
                key = key,
                bookKey = bookKey,
                kind = AnnotationKind.HIGHLIGHT,
                cfiStart = start,
                cfiEnd = end,
                color = color,
                selectedText = selectedText,
                noteText = "",
                chapter = chapter,
                chapterIndex = chapterIndex,
                percentage = percentage,
                range = range,
                tags = tags,
                createdAt = createdAt.takeIf { it > 0L } ?: key.toLongOrNull() ?: System.currentTimeMillis(),
            )
        }

        /**
         * Create a [AnnotationKind.NOTE]: a highlighted range with text.
         * A blank [noteText] is rejected — a note without text is a highlight.
         */
        fun note(
            bookKey: String,
            cfiStart: String,
            cfiEnd: String,
            selectedText: String,
            noteText: String,
            chapter: String = "",
            chapterIndex: Int = 0,
            percentage: String = "0",
            color: HighlightColor = HighlightColor.DEFAULT,
            range: String? = null,
            tags: List<String> = emptyList(),
            createdAt: Long = 0L,
            key: String = (createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()).toString(),
        ): Annotation {
            require(noteText.isNotBlank()) { "a NOTE needs non-blank noteText (use highlight() otherwise)" }
            val start = CfiAnchor.requireValid(cfiStart)
            val end = CfiAnchor.requireValid(cfiEnd)
            return Annotation(
                key = key,
                bookKey = bookKey,
                kind = AnnotationKind.NOTE,
                cfiStart = start,
                cfiEnd = end,
                color = color,
                selectedText = selectedText,
                noteText = noteText,
                chapter = chapter,
                chapterIndex = chapterIndex,
                percentage = percentage,
                range = range,
                tags = tags,
                createdAt = createdAt.takeIf { it > 0L } ?: key.toLongOrNull() ?: System.currentTimeMillis(),
            )
        }

        /**
         * Create a [AnnotationKind.BOOKMARK]: a single point with no range
         * or selected text. [cfi] must be a valid point CFI.
         */
        fun bookmark(
            bookKey: String,
            cfi: String,
            label: String = "",
            chapter: String = "",
            chapterIndex: Int = 0,
            percentage: String = "0",
            createdAt: Long = 0L,
            key: String = (createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()).toString(),
        ): Annotation {
            val point = CfiAnchor.requirePoint(cfi)
            return Annotation(
                key = key,
                bookKey = bookKey,
                kind = AnnotationKind.BOOKMARK,
                cfiStart = point,
                cfiEnd = null,
                color = HighlightColor.DEFAULT,
                selectedText = "",
                noteText = "",
                chapter = chapter,
                chapterIndex = chapterIndex,
                percentage = percentage,
                range = null,
                tags = emptyList(),
                label = label,
                createdAt = createdAt.takeIf { it > 0L } ?: key.toLongOrNull() ?: System.currentTimeMillis(),
            )
        }
    }
}
