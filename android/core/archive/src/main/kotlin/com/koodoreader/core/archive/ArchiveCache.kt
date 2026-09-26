package com.koodoreader.core.archive

import java.io.File
import java.util.LinkedHashMap

/**
 * LRU pool of open [ZipArchive] handles keyed by canonical file path.
 *
 * Consumers that touch the same archive repeatedly (BackupBundle's cover /
 * book / font accessors used to REOPEN the zip on every call; the comic
 * viewer holds one for a whole reading session) share one handle instead.
 *
 * Thread-safe: all state access is under [lock]. [maxOpen] bounds the number
 * of simultaneously open archives; the least-recently-used handle is closed
 * on eviction. Because a returned handle may be shared by several callers,
 * **callers must NOT close archives obtained from [get]** — lifecycle is
 * owned by the cache ([close] closes everything).
 */
class ArchiveCache(private val maxOpen: Int = DEFAULT_MAX_OPEN) : AutoCloseable {

    init {
        require(maxOpen >= 1) { "maxOpen must be >= 1 (was $maxOpen)" }
    }

    private val lock = Any()
    private val open = LinkedHashMap<String, ZipArchive>(16, 0.75f, /* accessOrder = */ true)

    /** Open (or reuse) the archive for [file]. Never returns a closed handle. */
    fun get(file: File): ZipArchive {
        val key = file.canonicalFile.path
        synchronized(lock) {
            open.remove(key)?.let { existing ->
                // Handles are never closed while pooled, so reuse is safe…
                open[key] = existing // touch LRU order
                return existing
            }
            val fresh = ZipArchives.open(file)
            open[key] = fresh
            evictIfNeeded()
            return fresh
        }
    }

    /** Currently open handle count (test/telemetry hook). */
    val size: Int
        get() = synchronized(lock) { open.size }

    /** Close every pooled handle. Safe to call twice. */
    override fun close() {
        synchronized(lock) {
            open.values.forEach { runCatching { it.close() } }
            open.clear()
        }
    }

    private fun evictIfNeeded() {
        while (open.size > maxOpen) {
            val eldestKey = open.keys.iterator().next() // accessOrder LinkedHashMap: first = LRU
            val eldest = open.remove(eldestKey)
            runCatching { eldest?.close() }
        }
    }

    companion object {
        const val DEFAULT_MAX_OPEN = 4
    }
}
