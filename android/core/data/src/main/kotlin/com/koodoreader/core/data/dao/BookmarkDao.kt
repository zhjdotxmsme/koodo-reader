package com.koodoreader.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.koodoreader.core.data.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bookmarks: List<BookmarkEntity>)

    @Query("SELECT * FROM bookmarks WHERE bookKey = :bookKey")
    fun observeForBook(bookKey: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("DELETE FROM bookmarks WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun count(): Long
}
