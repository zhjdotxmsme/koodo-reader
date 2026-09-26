package com.koodoreader.core.archive

import java.io.File
import java.util.zip.ZipFile

/**
 * Entry points for opening zip archives. All previous naked
 * `java.util.zip.ZipFile(file)` call sites go through here.
 */
object ZipArchives {

    /**
     * ZIP local-file-header magic `PK\x03\x04` — the check BackupBundle used
     * to inline. Note this detects the LOCAL header; a zip with a prepended
     * payload (self-extracting style) still fails it, same as before.
     */
    fun isZip(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        file.inputStream().use {
            val b = ByteArray(4)
            if (it.read(b) < 4) return false
            return b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() &&
                b[2] == 0x03.toByte() && b[3] == 0x04.toByte()
        }
    }

    /**
     * Open [file] as a zip. Throws [ArchiveOpenException] when it is missing,
     * not a zip, or its central directory is corrupt — never a raw IOException.
     */
    fun open(file: File): ZipArchive {
        if (!file.isFile) throw ArchiveOpenException(file.path)
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw ArchiveOpenException(file.path, e)
        }
        return try {
            JavaZipArchive(file, zip)
        } catch (t: Throwable) {
            runCatching { zip.close() }
            throw ArchiveOpenException(file.path, t)
        }
    }

    /** [open] for callers that treat unreadable archives as "no archive". */
    fun openOrNull(file: File): ZipArchive? =
        try {
            open(file)
        } catch (_: ArchiveException) {
            null
        }
}
