package com.koodoreader.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Desktop table `words` (schema.lock): vocabulary-builder entries.
 * `sentence` exists because the desktop migration
 * `ALTER TABLE words ADD COLUMN "sentence" text` is part of the shipped DDL
 * set (recorded in schema.lock provenance.migrations).
 */
@Entity(tableName = "words")
data class WordEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "bookKey") val bookKey: String? = null,
    /** Desktop type "object": JSON-serialised date payload. */
    @ColumnInfo(name = "date") val date: String? = null,
    @ColumnInfo(name = "word") val word: String? = null,
    @ColumnInfo(name = "sentence") val sentence: String? = null,
    @ColumnInfo(name = "chapter") val chapter: String? = null,
)
