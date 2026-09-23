package com.koodoreader.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Desktop table `notes` (schema.lock): highlights / notes / bookmarks' rich
 * payload. `cfi` is the cross-end position anchor (see ADR-002);
 * `date` and `tag` carry desktop JSON payloads ("object" / "array" columns).
 */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "bookKey") val bookKey: String? = null,
    /** Desktop type "object": JSON-serialised date payload. */
    @ColumnInfo(name = "date") val date: String? = null,
    @ColumnInfo(name = "chapter") val chapter: String? = null,
    @ColumnInfo(name = "chapterIndex") val chapterIndex: Long? = null,
    @ColumnInfo(name = "text") val text: String? = null,
    /** EPUB CFI — the single source of truth for positions (ADR-002). */
    @ColumnInfo(name = "cfi") val cfi: String? = null,
    /** Serialised DOM range complementing `cfi`. */
    @ColumnInfo(name = "range") val range: String? = null,
    @ColumnInfo(name = "notes") val notes: String? = null,
    @ColumnInfo(name = "percentage") val percentage: String? = null,
    @ColumnInfo(name = "color") val color: Long? = null,
    /** Desktop type "array": JSON-serialised tag list. */
    @ColumnInfo(name = "tag") val tag: String? = null,
)
