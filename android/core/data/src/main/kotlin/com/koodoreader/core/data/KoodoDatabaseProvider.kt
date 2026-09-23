package com.koodoreader.core.data

import android.content.Context
import androidx.room.Room

/**
 * Process-wide [KoodoDatabase] singleton. Deliberately tiny (no DI framework
 * yet — the P1 shell is the only consumer; introduce Hilt/koin only if the
 * module graph actually needs it).
 */
object KoodoDatabaseProvider {

    @Volatile
    private var instance: KoodoDatabase? = null

    fun get(context: Context): KoodoDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                KoodoDatabase::class.java,
                KoodoDatabase.NAME,
            ).build().also { instance = it }
        }
}
