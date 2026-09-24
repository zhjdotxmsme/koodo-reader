package com.koodoreader.core.importer

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Streaming MD5 for book files (P1 import dedupe key).
 *
 * Reads in fixed 16 KB chunks so a 2 GB book never materializes as a single
 * buffer (1000-imports-not-OOM acceptance). The output format — 32 lower-case
 * hex chars — is identical to the desktop `calculateFileMD5` (CryptoJS MD5),
 * so `books.md5` rows compare equal between desktop and Android
 * (`BookUtil.getBookByMd5` dedupe semantics).
 */
object FileMd5 {

    /** Chunk size: 16 KB — matches the desktop streaming reader. */
    const val CHUNK_SIZE: Int = 16 * 1024

    private const val HEX: String = "0123456789abcdef"

    /** MD5 hex digest of [input], streaming. Closes [input] when done. */
    fun ofStream(input: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(CHUNK_SIZE)
        try {
            var read = input.read(buffer)
            while (read >= 0) {
                if (read > 0) digest.update(buffer, 0, read)
                if (read < buffer.size) break
                read = input.read(buffer)
            }
        } finally {
            input.close()
        }
        return toHex(digest.digest())
    }

    /** MD5 hex digest of a plain [file], streaming. */
    fun ofFile(file: File): String {
        // RandomAccessFile is equally cheap on Android and JVM; the seek
        // handle avoids buffering the whole file.
        RandomAccessFile(file, "r").use { raf ->
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(CHUNK_SIZE)
            var read = raf.read(buffer)
            while (read >= 0) {
                if (read > 0) digest.update(buffer, 0, read)
                if (read < buffer.size) break
                read = raf.read(buffer)
            }
            return toHex(digest.digest())
        }
    }

    private fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX[(v shr 4) and 0x0F])
            append(HEX[v and 0x0F])
        }
    }
}
