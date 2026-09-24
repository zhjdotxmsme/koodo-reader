package com.koodoreader.reader.shell

import java.io.File

/**
 * Pure file/URL helpers for the native reader hosts (P3).
 *
 * Kept free of Android types so `:app`'s JVM unit tests pin them (see
 * `ReaderFilesTest`); the reader screens that use them are Android-only.
 */
object ReaderFiles {

    /** Virtual-path prefix the loopback server publishes books under. */
    const val BOOKS_PREFIX = "__books__"

    /**
     * Resolve the on-device file for [bookKey].
     *
     * Resolution order:
     *  1. [recordedPath] — what `ImportPipeline` wrote into Room
     *     (`<booksDir>/<key>.<ext>`, see `BookRecord.path`);
     *  2. the conventional `<booksDir>/<key>.<ext>`;
     *  3. any `<booksDir>/<key>.*` — a book imported from a desktop database
     *     can carry a different extension than its recorded `format`.
     *
     * The scan in (3) is sorted so the result does not depend on directory
     * iteration order. Returns null when nothing matches: callers must show an
     * error instead of opening a path that does not exist.
     */
    fun resolveBookFile(
        booksDir: File,
        bookKey: String,
        format: String?,
        recordedPath: String?,
    ): File? {
        val recorded = recordedPath?.takeIf { it.isNotBlank() }?.let(::File)
        if (recorded != null && recorded.isFile) return recorded

        val ext = (format ?: "").lowercase()
        if (ext.isNotEmpty()) {
            val conventional = File(booksDir, "$bookKey.$ext")
            if (conventional.isFile) return conventional
        }
        return booksDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$bookKey.") }
            ?.sortedBy { it.name }
            ?.firstOrNull()
    }

    /**
     * Virtual path to publish [file] at on the loopback server.
     *
     * The name is sanitised because the path is used in a URL: a book called
     * `My Book #1.pdf` must not produce a request the server cannot route. The
     * native importer writes `<key>.<ext>` names, so the mapping stays unique.
     */
    fun virtualPath(file: File): String {
        val safe = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "book" }
        return "$BOOKS_PREFIX/$safe"
    }

    /** Export file name for the "share current page" snapshot. */
    fun snapshotName(bookKey: String, page: Int, extension: String): String {
        val safe = bookKey.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "book" }
        return "$safe-p$page.$extension"
    }
}
