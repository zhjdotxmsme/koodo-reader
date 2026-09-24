package com.koodoreader.feature.stats

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accounting tests for [ReadingSessionRepository] — the part of P6 that decides
 * how many seconds a day gets. They run against [InMemoryReadingSessionStore]
 * with an injected clock, so the whole state machine (idle clamping, midnight
 * splitting, buffered flush, idempotent keys) is exercised without Room,
 * SQLite or an Android device.
 */
class ReadingSessionRepositoryTest {

    private val store = InMemoryReadingSessionStore()
    private var now = 0L

    private fun repository(
        zoneOffsetMinutes: Int = 0,
        idleTimeoutMillis: Long = ReadingSessionRepository.DEFAULT_IDLE_TIMEOUT_MILLIS,
        flushIntervalMillis: Long = ReadingSessionRepository.DEFAULT_FLUSH_INTERVAL_MILLIS,
    ) = ReadingSessionRepository(
        store = store,
        zoneOffsetMinutes = { zoneOffsetMinutes },
        nowMillis = { now },
        idleTimeoutMillis = idleTimeoutMillis,
        flushIntervalMillis = flushIntervalMillis,
    )

    @Test
    fun `ticks are credited to the book and day and flushed on end`() = runBlocking {
        val repository = repository()
        now = 1_000L
        repository.begin("book-a", cfi = "epubcfi(/6/4)")
        now += 1_000L
        repository.tick()
        now += 1_000L
        repository.tick(wordsDelta = 42L)
        repository.end()

        val rows = store.loadAll()
        assertEquals(1, rows.size)
        assertEquals("book-a", rows[0].bookKey)
        assertEquals("1970-01-01", rows[0].day)
        assertEquals(2L, rows[0].seconds)
        assertEquals(42L, rows[0].words)
        assertEquals("epubcfi(/6/4)", rows[0].cfi)
        assertFalse(repository.isActive)
    }

    @Test
    fun `an idle gap is not credited and restarts the session`() = runBlocking {
        val repository = repository(idleTimeoutMillis = 120_000L)
        now = 0L
        repository.begin("book-a")
        now += 60_000L
        repository.tick() // credited
        now += 5 * 60_000L
        repository.tick() // 5 min idle: dropped
        now += 1_000L
        repository.tick() // credited again
        repository.end()

        assertEquals(61L, store.loadAll().sumOf { it.seconds })
    }

    @Test
    fun `a tick crossing midnight is split between the two days`() = runBlocking {
        val repository = repository()
        // 2024-01-01T23:59:59Z
        now = 1_704_153_599_000L
        repository.begin("book-a")
        now += 3_000L // 2024-01-02T00:00:02Z
        repository.tick()
        repository.end()

        val rows = store.loadAll().associateBy { it.day }
        assertEquals(setOf("2024-01-01", "2024-01-02"), rows.keys)
        assertEquals(1L, rows.getValue("2024-01-01").seconds)
        assertEquals(2L, rows.getValue("2024-01-02").seconds)
    }

    @Test
    fun `sub-second ticks are carried instead of lost`() = runBlocking {
        val repository = repository()
        now = 0L
        repository.begin("book-a")
        repeat(4) {
            now += 500L
            repository.tick()
        }
        repository.end()
        assertEquals(2L, store.loadAll().sumOf { it.seconds })
    }

    @Test
    fun `flushing twice keeps a single row per book and day`() = runBlocking {
        val repository = repository()
        now = 0L
        repository.begin("book-a")
        now += 1_000L
        repository.tick()
        repository.flush()
        repository.flush()
        repository.end()

        assertEquals(1, store.size())
        assertEquals(1L, store.loadAll()[0].seconds)
    }

    @Test
    fun `long sessions flush every interval and survive a restart`() = runBlocking {
        val repository = repository(flushIntervalMillis = 3_000L)
        now = 0L
        repository.begin("book-a")
        // Four consecutive 1 s ticks: the 3rd one hits the flush interval.
        repeat(4) {
            now += 1_000L
            repository.tick()
        }
        assertEquals(3L, store.loadAll().sumOf { it.seconds })

        repository.end() // flushes the remaining second
        assertEquals(4L, store.loadAll().sumOf { it.seconds })
    }

    @Test
    fun `switching books closes the previous session`() = runBlocking {
        val repository = repository()
        now = 0L
        repository.begin("book-a")
        now += 1_000L
        repository.tick()
        repository.begin("book-b")
        now += 2_000L
        repository.tick()
        repository.end()

        val byBook = store.loadAll().groupBy { it.bookKey }
        assertEquals(1L, byBook.getValue("book-a").sumOf { it.seconds })
        assertEquals(2L, byBook.getValue("book-b").sumOf { it.seconds })
    }

    @Test
    fun `total seconds per book mirrors the desktop readingTime blob`() = runBlocking {
        val repository = repository()
        now = 0L
        repository.begin("book-a")
        now += 10_000L
        repository.tick()
        repository.begin("book-b")
        now += 5_000L
        repository.tick()
        repository.end()

        assertEquals(10L, repository.totalSecondsFor("book-a"))
        assertEquals(5L, repository.totalSecondsFor("book-b"))
        assertEquals(0L, repository.totalSecondsFor("missing"))
    }

    @Test
    fun `snapshot reuses the aggregator over the stored rows`() = runBlocking {
        val repository = repository()
        now = 0L
        repository.begin("book-a")
        now += 30_000L
        repository.tick()
        repository.end()

        val snapshot = repository.snapshot(
            booksRead = 1,
            progressByBook = mapOf("book-a" to 0.5),
            today = IsoDay(1970, 1, 1),
        )
        assertEquals(30L, snapshot.totalSeconds)
        assertEquals(1, snapshot.longestStreak)
        assertEquals(30L, snapshot.avgDailySeconds)
        assertEquals(50.0, snapshot.averageProgress, 0.0001)
    }

    @Test
    fun `a backwards clock jump credits nothing`() = runBlocking {
        val repository = repository()
        now = 100_000L
        repository.begin("book-a")
        now = 50_000L
        repository.tick()
        now = 51_000L
        repository.tick()
        repository.end()

        assertEquals(1L, store.loadAll().sumOf { it.seconds })
    }

    // ── day splitting is a pure function, tested directly ────────────────────

    @Test
    fun `splitAcrossDays returns one part inside a single day`() {
        val parts = ReadingSessionRepository.splitAcrossDays(0L, 5_000L) { 0 }
        assertEquals(listOf(IsoDay(1970, 1, 1) to 5_000L), parts)
    }

    @Test
    fun `splitAcrossDays honours the local offset`() {
        val midnightUtc = 1_704_067_200_000L // 2024-01-01T00:00:00Z
        // UTC+2 → local midnight of 2024-01-02 is 22:00Z on 2024-01-01.
        val localMidnight = midnightUtc + 79_200_000L
        val parts = ReadingSessionRepository.splitAcrossDays(localMidnight - 3_000L, localMidnight + 3_000L) { 120 }
        assertEquals(
            listOf(IsoDay(2024, 1, 1) to 3_000L, IsoDay(2024, 1, 2) to 3_000L),
            parts,
        )
    }

    @Test
    fun `splitAcrossDays ignores empty and reversed spans`() {
        assertTrue(ReadingSessionRepository.splitAcrossDays(10L, 10L) { 0 }.isEmpty())
        assertTrue(ReadingSessionRepository.splitAcrossDays(20L, 10L) { 0 }.isEmpty())
    }
}
