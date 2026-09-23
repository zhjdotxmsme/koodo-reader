package com.koodoreader.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.koodoreader.core.data.entity.PluginEntity

/**
 * Schema-compatibility DAO only: the native client does not run the desktop
 * plugin system (appendix A), but desktop exports contain this table, so P7
 * import needs to be able to persist the rows verbatim.
 */
@Dao
interface PluginDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(plugins: List<PluginEntity>)

    @Query("SELECT * FROM plugins")
    suspend fun getAll(): List<PluginEntity>

    @Query("DELETE FROM plugins WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("SELECT COUNT(*) FROM plugins")
    suspend fun count(): Long
}
