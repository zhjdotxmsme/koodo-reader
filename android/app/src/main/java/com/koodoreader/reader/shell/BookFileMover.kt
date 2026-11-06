package com.koodoreader.reader.shell

import com.koodoreader.core.data.entity.BookEntity
import java.io.File
import java.io.IOException

/**
 * Moves a book's private file and returns the updated entity. Only the path
 * changes — the Room key (and therefore progress/annotations) is preserved.
 * SAF `content://` books cannot be moved as files; import them first.
 */
object BookFileMover {

    fun move(book: BookEntity, targetDir: File, booksDir: File? = null): BookEntity {
        val source = resolveSource(book, booksDir)
        return moveFrom(book, source, targetDir, booksDir)
    }

    /**
     * Renames the private book file to [title] (extension kept). Display-only
     * when the file is missing or outside [booksDir]. Refuses to overwrite.
     */
    fun renameFile(book: BookEntity, title: String, booksDir: File): BookEntity {
        val source = try {
            resolveSource(book, booksDir)
        } catch (_: IllegalArgumentException) {
            return book
        }
        if (!source.isFile) return book
        val stem = title.trim()
            .replace(Regex("""[\\/:*?"<>|\u0000]"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
        if (stem.isEmpty()) return book
        val ext = source.extension
        val target = File(source.parentFile, if (ext.isEmpty()) stem else "$stem.$ext")
        if (target.canonicalFile == source.canonicalFile) return book
        if (target.exists()) throw IOException("Target exists: ${target.name}")
        if (!relocate(source, target)) throw IOException("Cannot rename book")
        return book.copy(path = target.path)
    }

    /** Moves the file and applies the Room update; on update failure the file
     *  is moved back to the resolved source, not the raw `path` string. */
    fun moveAndUpdate(
        book: BookEntity,
        targetDir: File,
        booksDir: File? = null,
        update: (BookEntity) -> Unit,
    ): BookEntity {
        val source = resolveSource(book, booksDir)
        val moved = moveFrom(book, source, targetDir, booksDir)
        try {
            update(moved)
        } catch (t: Throwable) {
            val dest = File(checkNotNull(moved.path))
            if (!relocate(dest, source)) {
                t.addSuppressed(IOException("Cannot roll back ${dest.path} to ${source.path}"))
            }
            throw t
        }
        return moved
    }

    private fun moveFrom(
        book: BookEntity,
        source: File,
        targetDir: File,
        booksDir: File?,
    ): BookEntity {
        require(source.isFile) { "Source missing: ${source.path}" }
        if (booksDir != null) {
            val root = booksDir.canonicalFile
            require(source.canonicalFile.toPath().startsWith(root.toPath())) {
                "Book is outside the private library"
            }
        }
        require(targetDir.isDirectory || targetDir.mkdirs()) { "Cannot create target" }
        val target = File(targetDir, source.name)
        require(!target.exists()) { "Target exists: ${target.path}" }
        if (!relocate(source, target)) throw IOException("Cannot move book")
        return book.copy(path = target.path)
    }

    private fun resolveSource(book: BookEntity, booksDir: File?): File {
        val rawPath = book.path?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Book path missing")
        require(!rawPath.startsWith("content://")) { "SAF books must be imported before moving" }
        return if (booksDir != null) {
            ReaderFiles.resolveBookFile(booksDir, book.key, book.format, book.path)
                ?: throw IllegalArgumentException("Book file missing")
        } else {
            File(rawPath)
        }
    }

    /** `renameTo`, then copy+delete when the volume does not support rename. */
    private fun relocate(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        return try {
            from.copyTo(to, overwrite = false)
            if (!from.delete()) {
                to.delete()
                false
            } else {
                true
            }
        } catch (_: IOException) {
            if (to.exists()) to.delete()
            false
        }
    }
}
