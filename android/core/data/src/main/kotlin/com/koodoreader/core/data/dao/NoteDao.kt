package com.koodoreader.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.koodoreader.core.data.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(notes: List<NoteEntity>)

    @Query("SELECT * FROM notes WHERE bookKey = :bookKey ORDER BY chapterIndex, `key`")
    fun observeForBook(bookKey: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE `key` = :key")
    suspend fun getByKey(key: String): NoteEntity?

    /**
     * All highlights (rows with an empty `notes` column) of one book.
     *
     * A highlight is distinguished from a note by an empty `notes` text —
     * see AnnotationKind — so the selection sheet can tell "already
     * highlighted" from "only annotated".
     */
    @Query(
        "SELECT * FROM notes WHERE bookKey = :bookKey AND (notes IS NULL OR notes = '') " +
            "AND cfi IS NOT NULL",
    )
    suspend fun getHighlightsForBook(bookKey: String): List<NoteEntity>

    @Query("DELETE FROM notes WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM notes WHERE bookKey = :bookKey")
    suspend fun deleteForBook(bookKey: String)

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM notes WHERE bookKey = :bookKey")
    fun observeCountForBook(bookKey: String): Flow<Long>
}
