package com.koodoreader.core.archive

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * A readable zip archive — the single abstraction every in-repo zip reader
 * goes through (EPUB OPF, CBZ pages, desktop backup bundles, and the future
 * DOCX/OOXML readers).
 *
 * Contract:
 *  - [entries] is materialised ONCE at open (cheap; zips in this app's size
 *    range) and re-used for every lookup — no repeated central-directory
 *    walks.
 *  - [entry] resolves exact first, then case-insensitively — the shared
 *    policy previously duplicated in EpubBook / ComicCover / ZipExtractor
 *    (some packagers mangle entry case).
 *  - [openStream] streams; callers decide how much to buffer. Nothing here
 *    loads a whole entry except the explicit [readBytes] convenience.
 *  - AutoCloseable: long-lived consumers (backup bundle, comic viewer)
 *    hold the handle; short-lived ones use `use { }`.
 */
interface ZipArchive : AutoCloseable {

    /** The backing file (canonical-path key for [ArchiveCache]). */
    val file: File

    /** All entries, in archive order (directories included, flag on the entry). */
    val entries: List<ArchiveEntry>

    /** Entry lookup: exact name first, then case-insensitive; null when absent. */
    fun entry(name: String): ArchiveEntry?

    /** All entry names (directories included) — convenience for filters. */
    fun names(): Sequence<String> = entries.asSequence().map { it.name }

    /**
     * Stream an entry's uncompressed bytes. Throws [ArchiveEntryNotFoundException]
     * when the entry is absent (exact AND case-insensitive lookup).
     */
    fun openStream(name: String): InputStream

    /** Stream an entry into [out] without materialising it (book/font files). */
    fun copyTo(name: String, out: OutputStream) {
        openStream(name).use { it.copyTo(out) }
    }

    /** Read a whole entry (covers, OPF XML, small assets). Callers keep size in mind. */
    fun readBytes(name: String): ByteArray = openStream(name).use { it.readBytes() }
}
