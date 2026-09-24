package com.koodoreader.engine.annotate

/**
 * A field-for-field Kotlin projection of one `notes` row.
 *
 * Field names and types mirror the EXISTING Room `NoteEntity` exactly
 * (`String?` for every desktop TEXT/object/array column, `Long?` for the
 * INTEGER columns), so the persistence layer can convert with a trivial
 * `NoteEntity(row.key, row.bookKey, ...)` without this module depending on
 * Room or Android. This is why no new `@Entity` is declared here.
 *
 *  - [date] desktop "object": JSON text `{"year","month","day"}`
 *  - [tag] desktop "array": JSON text of a string array
 *  - [color] Room INTEGER: the stable [HighlightColor.code]
 *  - [cfi] canonical range CFI (see [AnnotationCodec] for the desktop
 *    recordLocation-JSON unwrap on import)
 */
data class NoteRow(
    val key: String,
    val bookKey: String? = null,
    val date: String? = null,
    val chapter: String? = null,
    val chapterIndex: Long? = null,
    val text: String? = null,
    val cfi: String? = null,
    val range: String? = null,
    val notes: String? = null,
    val percentage: String? = null,
    val color: Long? = null,
    val tag: String? = null,
)

/**
 * A field-for-field Kotlin projection of one `bookmarks` row. Field names
 * and types mirror the existing Room `BookmarkEntity` exactly.
 */
data class BookmarkRow(
    val key: String,
    val bookKey: String? = null,
    val cfi: String? = null,
    val label: String? = null,
    val percentage: String? = null,
    val chapter: String? = null,
)
