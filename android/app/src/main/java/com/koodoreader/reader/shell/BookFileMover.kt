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
        val rawPath = book.path?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Book path missing")
        require(!rawPath.startsWith("content://")) { "SAF books must be imported before moving" }
        val source = if (booksDir != null) {
            ReaderFiles.resolveBookFile(booksDir, book.key, book.format, book.path)
                ?: throw IllegalArgumentException("Book file missing")
        } else {
            File(rawPath)
        }
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
        if (!source.renameTo(target)) throw IOException("Cannot move book")
        return book.copy(path = target.path)
    }

    /** Moves the file and applies the Room update; on update failure the file
     *  is moved back so the database and disk never disagree. */
    fun moveAndUpdate(
        book: BookEntity,
        targetDir: File,
        booksDir: File? = null,
        update: (BookEntity) -> Unit,
    ): BookEntity {
        val moved = move(book, targetDir, booksDir)
        try {
            update(moved)
        } catch (t: Throwable) {
            File(checkNotNull(moved.path)).renameTo(File(checkNotNull(book.path)))
            throw t
        }
        return moved
    }
}
