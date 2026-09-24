package com.koodoreader.feature.translate

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database holding [TranslationHistoryEntity].
 *
 * Design note (see docs/p6-translate-architecture.md §5): the table lives in its
 * own database file so `:feature:translate` stays self-contained and does not
 * require editing `core/data`'s `KoodoDatabase` (P6 is not allowed to touch the
 * P1 data layer). Because `KoodoDatabase` is a Room database with a fixed entity
 * list, merging this table into it is an additive `@Database(entities = [...,
 * TranslationHistoryEntity::class], version = n + 1)` change plus a
 * `Migration(n, n + 1)` containing the same `CREATE TABLE` Room derives here —
 * the DAO compiles against either database.
 */
@Database(entities = [TranslationHistoryEntity::class], version = 1, exportSchema = false)
abstract class TranslationHistoryDatabase : RoomDatabase() {

    abstract fun historyDao(): TranslationHistoryDao

    companion object {
        const val NAME = "koodo_translation_history.db"

        @Volatile
        private var instance: TranslationHistoryDatabase? = null

        fun get(context: Context): TranslationHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TranslationHistoryDatabase::class.java,
                    NAME,
                ).build().also { instance = it }
            }
    }
}
