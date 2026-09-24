package com.koodoreader.engine.annotate

/**
 * One column descriptor: its name, its declared SQL type token and whether
 * it is part of the primary key.
 *
 * The [type] strings reproduce schema.lock VERBATIM, including desktop's
 * quirky non-SQLite type names: `notes.date` is declared `"object"` and
 * `notes.tag` is `"array"`. Room normalises those to TEXT, but the frozen
 * lock keeps the original token, so the descriptor keeps it too.
 */
data class ColumnSpec(
    val name: String,
    val type: String,
    val pk: Boolean = false,
)

/** A table: name plus its ordered column list. */
data class TableSpec(
    val name: String,
    val columns: List<ColumnSpec>,
) {
    /** Just the ordered column names. */
    val columnNames: List<String> get() = columns.map { it.name }

    /** Ordered primary-key column names. */
    val primaryKeyNames: List<String> get() = columns.filter { it.pk }.map { it.name }
}

/**
 * The frozen column layouts for the two tables this module produces.
 *
 * Column names, order, type tokens and primary keys are generated HERE,
 * explicitly and in order, to match the desktop tables frozen in
 * schema.lock:
 *
 * ```
 * notes:     key, bookKey, date, chapter, chapterIndex, text, cfi, range,
 *            notes, percentage, color, tag
 * bookmarks: key, bookKey, cfi, label, percentage, chapter
 * ```
 *
 * This object is NOT a Room `@Entity` on purpose: adding an entity for an
 * already-frozen table would trip the "unknown table" guard in
 * `scripts/check-room-schema.js`. The persistence adapter consumes these
 * descriptors to build rows for the EXISTING `NoteEntity` /
 * `BookmarkEntity` tables instead.
 */
object AnnotationSchema {

    const val TABLE_NOTES = "notes"
    const val TABLE_BOOKMARKS = "bookmarks"

    // Column-name constants shared with the codec.
    const val COL_KEY = "key"
    const val COL_BOOK_KEY = "bookKey"
    const val COL_DATE = "date"
    const val COL_CHAPTER = "chapter"
    const val COL_CHAPTER_INDEX = "chapterIndex"
    const val COL_TEXT = "text"
    const val COL_CFI = "cfi"
    const val COL_RANGE = "range"
    const val COL_NOTES = "notes"
    const val COL_PERCENTAGE = "percentage"
    const val COL_COLOR = "color"
    const val COL_TAG = "tag"
    const val COL_LABEL = "label"

    /** Desktop `notes` table — carries highlights and notes. */
    val NOTES: TableSpec = TableSpec(
        name = TABLE_NOTES,
        columns = listOf(
            ColumnSpec(COL_KEY, "TEXT", pk = true),
            ColumnSpec(COL_BOOK_KEY, "TEXT"),
            ColumnSpec(COL_DATE, "object"),
            ColumnSpec(COL_CHAPTER, "TEXT"),
            ColumnSpec(COL_CHAPTER_INDEX, "INTEGER"),
            ColumnSpec(COL_TEXT, "TEXT"),
            ColumnSpec(COL_CFI, "TEXT"),
            ColumnSpec(COL_RANGE, "TEXT"),
            ColumnSpec(COL_NOTES, "TEXT"),
            ColumnSpec(COL_PERCENTAGE, "TEXT"),
            ColumnSpec(COL_COLOR, "INTEGER"),
            ColumnSpec(COL_TAG, "array"),
        ),
    )

    /** Desktop `bookmarks` table — single-point markers. */
    val BOOKMARKS: TableSpec = TableSpec(
        name = TABLE_BOOKMARKS,
        columns = listOf(
            ColumnSpec(COL_KEY, "TEXT", pk = true),
            ColumnSpec(COL_BOOK_KEY, "TEXT"),
            ColumnSpec(COL_CFI, "TEXT"),
            ColumnSpec(COL_LABEL, "TEXT"),
            ColumnSpec(COL_PERCENTAGE, "TEXT"),
            ColumnSpec(COL_CHAPTER, "TEXT"),
        ),
    )

    /** Every table this module defines, in desktop create order. */
    fun all(): List<TableSpec> = listOf(BOOKMARKS, NOTES)

    fun forTable(name: String): TableSpec? = all().firstOrNull { it.name == name }

    /**
     * Compare this table column-by-column against an externally-supplied
     * layout (e.g. a row parsed from schema.lock) and return a human-readable
     * diff. The check covers exactly what `check-room-schema.js` guards —
     * ordered column names and the primary key — PLUS the type token, so a
     * test can assert full equality.
     *
     * @return empty list when the layouts are identical
     */
    fun TableSpec.mismatches(actual: List<ColumnSpec>): List<String> {
        val problems = mutableListOf<String>()

        if (columns.map { it.name } != actual.map { it.name }) {
            problems += "column mismatch\n      expected: [${columnNames.joinToString(", ")}]\n      actual:   [${actual.joinToString(", ") { it.name }}]"
        }
        if (primaryKeyNames != actual.filter { it.pk }.map { it.name }) {
            problems += "primary key mismatch (expected=${primaryKeyNames}, actual=${actual.filter { it.pk }.map { it.name }})"
        }
        columns.zip(actual).forEach { (expected, got) ->
            if (expected.type != got.type) {
                problems += "type mismatch on \"${expected.name}\" (expected=${expected.type}, actual=${got.type})"
            }
        }
        if (columns.size != actual.size) {
            problems += "column count mismatch (expected=${columns.size}, actual=${actual.size})"
        }
        return problems
    }
}
