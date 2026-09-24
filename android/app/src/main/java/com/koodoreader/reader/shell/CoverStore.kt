package com.koodoreader.reader.shell

import android.content.Context
import com.koodoreader.core.importer.BookCover
import java.io.File

/**
 * On-disk cover cache: `filesDir/cover/<bookKey>.<extension>`.
 *
 * Naming mirrors the desktop client exactly (`CoverUtil.addCover` writes
 * `<storageLocation>/cover/<bookKey>.<ext>`), so P7's backup/restore can
 * carry this directory as-is and desktop↔Android rows resolve the same file.
 *
 * Stateless and cheap to create; safe to instantiate per call site.
 * All methods are plain-JVM file IO — call them off the main thread.
 */
class CoverStore(context: Context) {

    private val dir: File = File(context.filesDir, "cover").apply { mkdirs() }

    /**
     * Writes [cover] for [bookKey], replacing any previous cover of that
     * book (the desktop addCover unlinks the existing file first).
     */
    fun save(bookKey: String, cover: BookCover): File {
        listFor(bookKey).forEach { it.delete() }
        val target = File(dir, "$bookKey.${cover.extension}")
        target.writeBytes(cover.bytes)
        return target
    }

    /** The book's cover file, or null when none has been written yet. */
    fun fileFor(bookKey: String): File? = listFor(bookKey).firstOrNull()

    /**
     * Exact key-prefix match with a boundary check: `<key>.<ext>` matches,
     * but a different key merely starting with the same digits does not.
     */
    private fun listFor(bookKey: String): List<File> =
        dir.listFiles { _, name -> keyBoundary(name, bookKey) }?.toList().orEmpty()

    private fun keyBoundary(fileName: String, key: String): Boolean {
        if (!fileName.startsWith(key)) return false
        val rest = fileName.substring(key.length)
        return rest.isEmpty() || rest.startsWith(".")
    }
}
