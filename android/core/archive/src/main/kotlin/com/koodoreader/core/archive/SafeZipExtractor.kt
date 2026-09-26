package com.koodoreader.core.archive

import java.io.File
import java.io.FileOutputStream

/**
 * Zip-slip-proof extraction of [ZipArchive] contents to a directory.
 *
 * Defences (belt AND suspenders):
 *  1. NAME validation — rejects absolute paths (leading `/` or `\`), Windows
 *     drive letters (`C:\`), any `..` path segment, empty names and NUL
 *     bytes. This is where most zip-slip implementations stop; we don't.
 *  2. CANONICAL-PATH containment — after joining, the destination's
 *     canonical path must remain inside the target directory's canonical
 *     path (catches symlinked dirs and anything the name check missed).
 *  3. RESOURCE caps — per-entry and total uncompressed byte caps plus an
 *     entry-count cap, so a malicious archive cannot exhaust disk or memory
 *     (decompression bombs).
 *
 * Files are streamed (8 KiB buffer); no entry is ever fully materialised.
 */
object SafeZipExtractor {

    /** Resource caps — tighten for untrusted archives, keep defaults for books. */
    data class Limits(
        val maxEntries: Int = 10_000,
        val maxEntryUncompressedBytes: Long = 512L * 1024 * 1024, // 512 MB per entry
        val maxTotalUncompressedBytes: Long = 2L * 1024 * 1024 * 1024, // 2 GB per archive
    )

    /** [name] cannot be safely mapped under any target directory. */
    class UnsafeEntryNameException(name: String) :
        ArchiveException("unsafe archive entry name: $name")

    /** Caps exceeded — the archive is hostile or simply too large for this context. */
    class LimitExceededException(message: String) : ArchiveException(message)

    /**
     * Extract [filter]-selected entries under [targetDir] (created if needed).
     * Returns the written files, in archive order. Directories are never
     * written; nested entry paths (`a/b/c.png`) map to nested directories.
     */
    fun extract(
        archive: ZipArchive,
        targetDir: File,
        limits: Limits = Limits(),
        filter: (ArchiveEntry) -> Boolean = { !it.isDirectory },
    ): List<File> {
        require(targetDir.isDirectory || targetDir.mkdirs()) {
            "cannot create extraction dir: ${targetDir.path}"
        }
        val targetRoot = targetDir.canonicalFile
        val written = ArrayList<File>()
        var totalBytes = 0L
        var count = 0

        for (entry in archive.entries) {
            if (!filter(entry)) continue
            if (++count > limits.maxEntries) {
                throw LimitExceededException("archive exceeds $limits.maxEntries entries")
            }

            val dest = destinationOf(targetRoot, entry.name)
            dest.parentFile?.mkdirs() // nested entry paths (a/b/c.txt)

            archive.openStream(entry.name).use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var entryBytes = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        entryBytes += n
                        totalBytes += n
                        if (entryBytes > limits.maxEntryUncompressedBytes) {
                            throw LimitExceededException(
                                "entry '${entry.name}' exceeds per-entry cap",
                            )
                        }
                        if (totalBytes > limits.maxTotalUncompressedBytes) {
                            throw LimitExceededException("archive exceeds total cap")
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            written.add(dest)
        }
        return written
    }

    /**
     * Map an entry name to a file under [targetRoot] (canonical), enforcing
     * both defences 1 and 2.
     */
    internal fun destinationOf(targetRoot: File, name: String): File {
        if (name.isEmpty()) throw UnsafeEntryNameException(name)
        if (name.indexOf('\u0000') >= 0) throw UnsafeEntryNameException(name)

        // Windows drive letters ("C:\x" or "C:/x").
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) {
            throw UnsafeEntryNameException(name)
        }
        // Absolute-ish paths (unix + windows separator flavour).
        if (name.startsWith('/') || name.startsWith('\\')) {
            throw UnsafeEntryNameException(name)
        }
        // Any ".." segment escapes the target by construction.
        val segments = name.split('/', '\\')
        if (segments.any { it == ".." }) throw UnsafeEntryNameException(name)

        val dest = File(targetRoot, segments.joinToString(File.separator))
        // Defence 2: canonical containment (symlinks, case quirks, anything
        // the textual check missed). startsWith on the canonical root path
        // with the separator suffix so "/target-evil" cannot pass as
        // "/target".
        val rootPath = targetRoot.canonicalPath + File.separator
        val destPath = dest.canonicalPath
        if (destPath != targetRoot.canonicalPath && !destPath.startsWith(rootPath)) {
            throw UnsafeEntryNameException(name)
        }
        return dest
    }

    private const val BUFFER_SIZE = 8 * 1024
}
