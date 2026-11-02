package com.koodoreader.core.importer

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

data class StoredCopy(val file: File, val md5: String)

class AtomicBookStore(private val booksDir: File) {
    fun copy(source: InputStream, finalName: String): StoredCopy {
        require(booksDir.isDirectory || booksDir.mkdirs()) { "cannot create books directory" }
        val part = File(booksDir, "$finalName.part")
        val final = File(booksDir, finalName)
        val digest = MessageDigest.getInstance("MD5")
        try {
            source.use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (final.exists() || !part.renameTo(final)) throw IOException("cannot commit book file")
            return StoredCopy(final, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (t: Throwable) {
            if (part.exists() && !part.delete()) {
                t.addSuppressed(IOException("cannot delete partial book file: ${part.path}"))
            }
            throw t
        }
    }

    fun reconcile(ownedPaths: Collection<String>) {
        if (!booksDir.isDirectory) return
        val owned = ownedPaths.mapTo(HashSet()) { File(it).canonicalPath }
        booksDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if ((file.name.endsWith(".part") || file.canonicalPath !in owned) && !file.delete()) {
                throw IOException("cannot remove unowned book file: ${file.path}")
            }
        }
    }
}
