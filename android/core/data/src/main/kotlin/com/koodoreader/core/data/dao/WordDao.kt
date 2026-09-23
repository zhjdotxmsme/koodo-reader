package com.koodoreader.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.koodoreader.core.data.entity.WordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(word: WordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(words: List<WordEntity>)

    @Query("SELECT * FROM words WHERE bookKey = :bookKey")
    fun observeForBook(bookKey: String): Flow<List<WordEntity>>

    @Query("SELECT * FROM words")
    fun observeAll(): Flow<List<WordEntity>>

    @Query("DELETE FROM words WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("SELECT COUNT(*) FROM words")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM words WHERE bookKey = :bookKey")
    fun observeCountForBook(bookKey: String): Flow<Long>
}
