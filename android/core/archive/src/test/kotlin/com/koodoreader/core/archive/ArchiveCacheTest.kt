package com.koodoreader.core.archive

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class ArchiveCacheTest {

    @TempDir
    lateinit var dir: File

    private fun zip(name: String): File =
        TestZips.write(File(dir, name), mapOf("x.txt" to TestZips.bytes(name)))

    @Test
    fun `repeated get reuses the same handle`() {
        val f = zip("a.zip")
        ArchiveCache().use { cache ->
            val first = cache.get(f)
            assertEquals(first, cache.get(f))
            assertEquals(first, cache.get(f))
            assertEquals(1, cache.size)
        }
    }

    @Test
    fun `distinct files get distinct handles`() {
        ArchiveCache().use { cache ->
            val a = cache.get(zip("b1.zip"))
            val b = cache.get(zip("b2.zip"))
            assertTrue(a !== b)
            assertEquals(2, cache.size)
        }
    }

    @Test
    fun `lru eviction closes the least recently used handle`() {
        val z1 = zip("c1.zip")
        val z2 = zip("c2.zip")
        val z3 = zip("c3.zip")
        ArchiveCache(maxOpen = 2).use { cache ->
            val h1 = cache.get(z1)
            val h2 = cache.get(z2)
            cache.get(z3) // evicts h1 (LRU)
            assertEquals(2, cache.size)
            // h1 was closed by eviction; reopening the same file yields a
            // FRESH, working handle (the old object stays closed).
            assertClosed(h1)
            val h1b = cache.get(z1)
            assertTrue(h1 !== h1b)
            assertEquals("c1.zip", h1b.readBytes("x.txt").decodeToString())
        }
    }

    @Test
    fun `touching a handle protects it from eviction`() {
        val z1 = zip("d1.zip")
        val z2 = zip("d2.zip")
        ArchiveCache(maxOpen = 2).use { cache ->
            val h1 = cache.get(z1)
            cache.get(z2)
            cache.get(z1) // touch: z2 becomes the LRU now
            cache.get(zip("d3.zip")) // evicts z2's handle, keeps h1
            assertEquals("d1.zip", h1.readBytes("x.txt").decodeToString())
        }
    }

    @Test
    fun `close closes every pooled handle`() {
        val cache = ArchiveCache()
        val h1 = cache.get(zip("e1.zip"))
        val h2 = cache.get(zip("e2.zip"))
        cache.close()
        assertClosed(h1)
        assertClosed(h2)
        assertEquals(0, cache.size)
        // Second close is a no-op.
        cache.close()
    }

    @Test
    fun `get after close reopens a fresh handle`() {
        val f = zip("f1.zip")
        val cache = ArchiveCache()
        val h1 = cache.get(f)
        cache.close()
        val h2 = cache.get(f)
        assertTrue(h1 !== h2)
        cache.close()
    }

    @Test
    fun `concurrent get on the same file shares one handle`() {
        val f = zip("g1.zip")
        val cache = ArchiveCache()
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val futures = (1..8).map {
            pool.submit<ZipArchive> {
                ready.countDown()
                go.await()
                cache.get(f)
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        go.countDown()
        val results = futures.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(results.all { it === results[0] })
        assertEquals(1, cache.size)
        cache.close()
    }

    @Test
    fun `maxOpen below one is rejected`() {
        val e = assertThrows<IllegalArgumentException> { ArchiveCache(maxOpen = 0) }
        assertTrue(e.message!!.contains("maxOpen"))
    }

    /** A closed JavaZipArchive must refuse further work (ZipFile contract). */
    private fun assertClosed(archive: ZipArchive) {
        val e = assertThrows<IllegalStateException> { archive.readBytes("x.txt") }
        assertTrue(e.message!!.contains("closed"))
    }
}

