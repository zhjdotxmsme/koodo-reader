package com.koodoreader.engine.annotate

/**
 * The three annotation states behind the reader's text-selection menu
 * ("划词菜单 → 高亮 / 笔记 / 书签").
 *
 * Desktop storage mapping (see schema.lock and `src/models/Note.ts`):
 *  - [HIGHLIGHT] and [NOTE] both live in the `notes` table. They are told
 *    apart by the `notes` column: an EMPTY `notes` value is a plain
 *    highlight, a NON-EMPTY one is a note (highlight + attached text).
 *  - [BOOKMARK] lives in the separate `bookmarks` table.
 *
 * The codec is the single place that applies this mapping, so the rest of
 * the code can branch on the enum directly instead of re-deriving it from
 * column contents.
 */
enum class AnnotationKind {
    /** A marked text span (`notes` row, empty `notes` column). */
    HIGHLIGHT,

    /** A marked text span with attached user text (`notes` row, non-empty `notes`). */
    NOTE,

    /** A single-point marker with no selected text (`bookmarks` row). */
    BOOKMARK,
}
