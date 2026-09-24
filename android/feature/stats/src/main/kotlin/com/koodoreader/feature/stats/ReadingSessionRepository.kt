// feature/stats — reading-time persistence (P6).
//
// The desktop keeps reading time in two config blobs (`readingStats` per-day
// sessions written by kookit's `ReadingTimeUtil`, and `readingTime` per-book
// totals). Room is the native home for the same data: one row per contiguous
// reading session, already attributed to ONE local calendar day, which makes
// the /stats aggregation a pure fold over rows (see [StatsAggregator]).
//
// The repository is split in two halves:
//   * `ReadingSessionEntity` / `ReadingSessionDao` / `ReadingSessionStore` —
//     persistence (testable on the JVM: `androidx.room:room-common` is a plain
//     annotation JAR, and the DAO is only implemented by Room at build time);
//   * [ReadingSessionRepository] — the accounting state machine (idle gap
//     clamping, midnight splitting, buffered flush). Unit-tested against
//     [InMemoryReadingSessionStore] with an injected clock, so no test needs a
//     real database, coroutine dispatcher or Android device.
package com.koodoreader.feature.stats

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One reading session.
 *
 * Column naming follows the P1 `core/data` convention (camelCase columns, `key`
 * as the primary key) so a later move into `koodo.db` needs no rename. `day` is
 * the desktop `dateToKey` string (`YYYY-MM-DD`) — the join key with the desktop
 * `readingStats` blob during the P7 import/export round-trip.
 */
@Entity(tableName = "reading_sessions")
data class ReadingSessionEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "bookKey") val bookKey: String,
    @ColumnInfo(name = "day") val day: String,
    @ColumnInfo(name = "startMillis") val startMillis: Long,
    @ColumnInfo(name = "endMillis") val endMillis: Long,
    @ColumnInfo(name = "seconds") val seconds: Long,
    @ColumnInfo(name = "words") val words: Long = 0L,
    @ColumnInfo(name = "cfi") val cfi: String? = null,
) {
    fun toSession(): ReadingSession = ReadingSession(
        key = key,
        bookKey = bookKey,
        day = IsoDay.parse(day),
        seconds = seconds,
        words = words,
        startMillis = startMillis,
        endMillis = endMillis,
    )
}

@Dao
interface ReadingSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ReadingSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ReadingSessionEntity>)

    @Query("SELECT * FROM reading_sessions ORDER BY startMillis ASC")
    suspend fun loadAll(): List<ReadingSessionEntity>

    @Query("SELECT * FROM reading_sessions WHERE day = :day ORDER BY startMillis ASC")
    suspend fun loadDay(day: String): List<ReadingSessionEntity>

    @Query("SELECT * FROM reading_sessions WHERE bookKey = :bookKey ORDER BY startMillis ASC")
    suspend fun loadForBook(bookKey: String): List<ReadingSessionEntity>

    @Query("SELECT COALESCE(SUM(seconds), 0) FROM reading_sessions WHERE day = :day")
    suspend fun secondsOn(day: String): Long

    @Query("SELECT COALESCE(SUM(seconds), 0) FROM reading_sessions WHERE bookKey = :bookKey")
    suspend fun secondsForBook(bookKey: String): Long

    @Query("DELETE FROM reading_sessions WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM reading_sessions WHERE bookKey = :bookKey")
    suspend fun deleteForBook(bookKey: String)

    @Query("DELETE FROM reading_sessions")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM reading_sessions")
    suspend fun count(): Long
}

/**
 * Storage seam of [ReadingSessionRepository]. Keeping the repository behind this
 * interface is what makes the accounting unit-testable without Room/SQLite.
 */
interface ReadingSessionStore {
    suspend fun upsertAll(rows: List<ReadingSessionEntity>)
    suspend fun loadAll(): List<ReadingSessionEntity>
    suspend fun deleteByKey(key: String)
    suspend fun clear()
}

/** Room-backed store; the only production implementation. */
class RoomReadingSessionStore(private val dao: ReadingSessionDao) : ReadingSessionStore {
    override suspend fun upsertAll(rows: List<ReadingSessionEntity>) {
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    override suspend fun loadAll(): List<ReadingSessionEntity> = dao.loadAll()

    override suspend fun deleteByKey(key: String) = dao.deleteByKey(key)

    override suspend fun clear() = dao.clear()
}

/**
 * Deterministic in-memory store: used by JVM unit tests, Compose previews and
 * the offline/no-database fallback of the shell.
 */
class InMemoryReadingSessionStore : ReadingSessionStore {
    private val rows = LinkedHashMap<String, ReadingSessionEntity>()

    override suspend fun upsertAll(rows: List<ReadingSessionEntity>) {
        rows.forEach { this.rows[it.key] = it }
    }

    override suspend fun loadAll(): List<ReadingSessionEntity> =
        rows.values.sortedBy { it.startMillis }

    override suspend fun deleteByKey(key: String) {
        rows.remove(key)
    }

    override suspend fun clear() = rows.clear()

    fun size(): Int = rows.size
}

/**
 * Reading-time accounting for the native reader.
 *
 * Rules (each one has a unit test in `ReadingSessionRepositoryTest`):
 *  1. Wall-clock time is only credited for gaps between ticks that are at most
 *     [idleTimeoutMillis]; a longer gap means the reader was backgrounded, so
 *     the session is closed at the last tick and a fresh one starts now
 *     (desktop `ReadingTimeUtil.start/stop` behaves the same way).
 *  2. A tick that crosses local midnight is SPLIT between the two days, so the
 *     calendar/streak maths never double-books a day.
 *  3. Buffered time is flushed on session close, on a day boundary, every
 *     [flushIntervalMillis] of credited reading, and on explicit [flush] —
 *     a process kill therefore loses at most one flush interval.
 *  4. Row keys are `bookKey|day|sessionStart`, so re-flushing is idempotent
 *     (`OnConflictStrategy.REPLACE`).
 */
class ReadingSessionRepository(
    private val store: ReadingSessionStore,
    /** Zone offset (minutes) that applies at a given instant; `0` for tests. */
    private val zoneOffsetMinutes: (Long) -> Int = { 0 },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val flushIntervalMillis: Long = DEFAULT_FLUSH_INTERVAL_MILLIS,
) {
    private var bookKey: String? = null
    private var cfi: String? = null
    private var lastTickMillis: Long = 0L
    private var sessionStartMillis: Long = 0L

    /**
     * Accumulated session rows of this process, keyed by `bookKey|day|sessionStart`.
     * Rows stay here after a flush: they are the running total, and re-writing
     * only the dirty ones is what keeps a flush from truncating accumulated
     * seconds (Room's REPLACE would otherwise drop the previously stored value).
     */
    private val buffered = LinkedHashMap<String, ReadingSessionEntity>()
    private val dirtyKeys = LinkedHashSet<String>()
    private var unflushedSeconds: Long = 0L

    /** Sub-second carry, so a 1.5 s tick still credits 1 s and never drifts. */
    private var pendingMillis: Long = 0L

    val isActive: Boolean get() = bookKey != null

    /** Opens a session for [bookKey] (no-op when the same book is already open). */
    fun begin(bookKey: String, cfi: String? = null) {
        if (this.bookKey == bookKey) {
            this.cfi = cfi ?: this.cfi
            return
        }
        closeActiveSession()
        val now = nowMillis()
        this.bookKey = bookKey
        this.cfi = cfi
        sessionStartMillis = now
        lastTickMillis = now
    }

    /**
     * Credits the time elapsed since the previous tick to the open session.
     *
     * @param wordsDelta words read since the previous tick
     *                    (`StatsAggregator.wordCount(text)` for the rendered chunk)
     */
    suspend fun tick(wordsDelta: Long = 0L) {
        val activeBook = bookKey ?: return
        val now = nowMillis()
        val gap = now - lastTickMillis
        if (gap < 0L) {
            // Clock moved backwards (NTP / user change) — re-anchor, credit nothing.
            lastTickMillis = now
            return
        }
        if (gap > idleTimeoutMillis) {
            closeActiveSession()
            bookKey = activeBook
            sessionStartMillis = now
            lastTickMillis = now
            maybeFlush(force = true)
            return
        }
        // Distribute the elapsed millis over the local days it spans, carrying
        // the sub-second remainder forward into the newest part.
        var remainder = pendingMillis
        splitAcrossDays(lastTickMillis, now, zoneOffsetMinutes).forEach { (day, millis) ->
            val total = millis + remainder
            val seconds = total / 1000L
            remainder = total - seconds * 1000L
            credit(activeBook, day, seconds, wordsDelta = 0L, at = lastTickMillis)
        }
        pendingMillis = remainder
        if (wordsDelta != 0L) {
            creditWords(activeBook, IsoDay.ofMillis(now, zoneOffsetMinutes(now)), wordsDelta, at = now)
        }
        lastTickMillis = now
        maybeFlush(force = false)
    }

    /** Closes the open session and flushes everything buffered. */
    suspend fun end() {
        closeActiveSession()
        flush()
    }

    /**
     * Writes every row touched since the previous flush. Safe to call at any
     * time (idempotent keys, running totals kept in memory).
     */
    suspend fun flush() {
        if (dirtyKeys.isEmpty()) return
        val rows = dirtyKeys.mapNotNull { buffered[it] }
        dirtyKeys.clear()
        unflushedSeconds = 0L
        store.upsertAll(rows)
    }

    /** Aggregated snapshot for the /stats screen (desktop metric parity). */
    suspend fun snapshot(
        booksRead: Int,
        progressByBook: Map<String, Double> = emptyMap(),
        today: IsoDay = IsoDay.ofMillis(nowMillis(), zoneOffsetMinutes(nowMillis())),
        aggregator: StatsAggregator = StatsAggregator(),
    ): StatsSnapshot = aggregator.aggregate(
        sessions = store.loadAll().map { it.toSession() },
        booksRead = booksRead,
        progressByBook = progressByBook,
        today = today,
    )

    /** Total credited seconds for one book (`readingTime` blob parity). */
    suspend fun totalSecondsFor(bookKey: String): Long {
        flush()
        return store.loadAll().filter { it.bookKey == bookKey }.sumOf { it.seconds }
    }

    private fun closeActiveSession() {
        // Nothing extra to persist: every credited second was already attributed
        // to a (book, day) bucket by tick().
        bookKey = null
        cfi = null
    }

    private fun credit(bookKey: String, day: IsoDay, seconds: Long, wordsDelta: Long, at: Long) {
        if (seconds <= 0L && wordsDelta == 0L) return
        // One row per (book, day, session): the session start keeps the key
        // stable across ticks, the day keeps the midnight split apart.
        val key = rowKey(bookKey, day, sessionStartMillis)
        val existing = buffered[key]
        buffered[key] = (existing ?: ReadingSessionEntity(
            key = key,
            bookKey = bookKey,
            day = day.toString(),
            startMillis = sessionStartMillis,
            endMillis = sessionStartMillis,
            seconds = 0L,
            words = 0L,
            cfi = cfi,
        )).copy(
            seconds = (existing?.seconds ?: 0L) + seconds,
            words = (existing?.words ?: 0L) + wordsDelta,
            endMillis = maxOf(existing?.endMillis ?: 0L, at + seconds * 1000L),
        )
        dirtyKeys.add(key)
        if (seconds > 0L) unflushedSeconds += seconds
    }

    /** Words are attributed to the day of the current instant (`at`). */
    private fun creditWords(bookKey: String, day: IsoDay, wordsDelta: Long, at: Long) {
        credit(bookKey, day, seconds = 0L, wordsDelta = wordsDelta, at = at)
    }

    private suspend fun maybeFlush(force: Boolean) {
        if (force || unflushedSeconds >= flushIntervalMillis / 1000L) flush()
    }

    private fun rowKey(bookKey: String, day: IsoDay, startMillis: Long): String =
        "$bookKey|$day|$startMillis"

    companion object {
        /** Desktop stops counting after ~2 minutes without interaction. */
        const val DEFAULT_IDLE_TIMEOUT_MILLIS: Long = 120_000L

        /** Flush at most every 30 s of credited reading. */
        const val DEFAULT_FLUSH_INTERVAL_MILLIS: Long = 30_000L

        /**
         * Splits `[startMillis, endMillis)` into per-local-day MILLISECOND
         * durations using [zoneOffsetMinutes] at each instant (DST-correct).
         * Empty when the span is empty or reversed; a span inside one day yields
         * exactly one entry.
         */
        fun splitAcrossDays(
            startMillis: Long,
            endMillis: Long,
            zoneOffsetMinutes: (Long) -> Int,
        ): List<Pair<IsoDay, Long>> {
            if (endMillis <= startMillis) return emptyList()
            val parts = ArrayList<Pair<IsoDay, Long>>(2)
            var cursor = startMillis
            while (cursor < endMillis) {
                val offset = zoneOffsetMinutes(cursor)
                val day = IsoDay.ofMillis(cursor, offset)
                // First instant of the NEXT local day (offset at the cursor applies).
                val nextDayStart = (day.epochDay + 1L) * 86_400_000L - offset * 60_000L
                val boundary =
                    if (nextDayStart <= cursor) endMillis else minOf(nextDayStart, endMillis)
                parts.add(day to (boundary - cursor))
                cursor = boundary
            }
            return parts
        }
    }
}
