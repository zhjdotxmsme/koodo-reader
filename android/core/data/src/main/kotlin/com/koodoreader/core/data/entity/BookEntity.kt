package com.koodoreader.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Desktop table `books` (schema.lock). Column names are frozen by
 * `scripts/check-room-schema.js`; do not rename without updating the desktop
 * DDL and schema.lock in the same change.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "name") val name: String? = null,
    @ColumnInfo(name = "author") val author: String? = null,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "md5") val md5: String? = null,
    @ColumnInfo(name = "cover") val cover: String? = null,
    @ColumnInfo(name = "format") val format: String? = null,
    @ColumnInfo(name = "publisher") val publisher: String? = null,
    @ColumnInfo(name = "size") val size: Long? = null,
    @ColumnInfo(name = "page") val page: Long? = null,
    @ColumnInfo(name = "path") val path: String? = null,
    @ColumnInfo(name = "charset") val charset: String? = null,
)
