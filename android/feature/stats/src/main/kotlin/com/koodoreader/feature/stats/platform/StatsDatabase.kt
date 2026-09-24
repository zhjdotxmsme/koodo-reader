// feature/stats — Android-only persistence host.
//
// Room needs a `@Database` class to generate DAO implementations, and that class
// must live in an Android module (RoomDatabase is an Android type). The module
// therefore owns a small private database today; merging `reading_sessions`
// into :core:data's `koodo.db` is a main-thread decision because it changes the
// Room schema version (see docs/p6-stats-ocr-design.md §4.2, which contains the
// exact patch).
package com.koodoreader.feature.stats.platform

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.koodoreader.feature.stats.IsoDay
import com.koodoreader.feature.stats.ReadingSessionDao
import com.koodoreader.feature.stats.ReadingSessionEntity
import com.koodoreader.feature.stats.ReadingSessionRepository
import com.koodoreader.feature.stats.ReadingSessionStore
import com.koodoreader.feature.stats.RoomReadingSessionStore
import java.util.TimeZone

@Database(
    entities = [ReadingSessionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class StatsDatabase : RoomDatabase() {
    abstract fun readingSessionDao(): ReadingSessionDao

    companion object {
        const val NAME = "koodo-stats.db"

        @Volatile
        private var instance: StatsDatabase? = null

        fun get(context: Context): StatsDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    StatsDatabase::class.java,
                    NAME,
                ).build().also { instance = it }
            }
    }
}

/**
 * Production wiring for the reader: same store interface the JVM tests use, so
 * only this file (10 lines) is untested by the JVM suite.
 *
 * The zone offset is resolved per instant through [TimeZone] so a DST jump does
 * not shift the day attribution of the seconds around the switch.
 */
object ReadingSessionWiring {

    fun store(context: Context): ReadingSessionStore =
        RoomReadingSessionStore(StatsDatabase.get(context).readingSessionDao())

    fun repository(
        context: Context,
        nowMillis: () -> Long = { System.currentTimeMillis() },
    ): ReadingSessionRepository = ReadingSessionRepository(
        store = store(context),
        zoneOffsetMinutes = { millis -> zoneOffsetMinutesAt(millis) },
        nowMillis = nowMillis,
    )

    /** Offset of the device zone at [millis], in minutes (DST-correct). */
    fun zoneOffsetMinutesAt(millis: Long): Int {
        val zone = TimeZone.getDefault()
        return zone.getOffset(millis) / 60_000
    }

    /** Local "today" of the device, used by the stats screen. */
    fun localToday(nowMillis: Long): IsoDay =
        IsoDay.ofMillis(nowMillis, zoneOffsetMinutesAt(nowMillis))
}
