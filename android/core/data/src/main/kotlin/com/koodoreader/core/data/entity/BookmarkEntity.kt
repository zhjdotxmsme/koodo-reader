package com.koodoreader.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Desktop table `bookmarks` (schema.lock). */
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "bookKey") val bookKey: String? = null,
    @ColumnInfo(name = "cfi") val cfi: String? = null,
    @ColumnInfo(name = "label") val label: String? = null,
    @ColumnInfo(name = "percentage") val percentage: String? = null,
    @ColumnInfo(name = "chapter") val chapter: String? = null,
)
