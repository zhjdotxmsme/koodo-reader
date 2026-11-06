package com.koodoreader.core.reader

import com.koodoreader.core.importer.BookRecord

object ReaderMappers {

    fun toBook(record: BookRecord): Book = Book(
        bookKey = record.key,
        title = record.name.orEmpty(),
        author = record.author,
        filePath = record.path,
        format = record.format,
        coverPath = record.cover,
        fileSize = record.size,
        md5 = record.md5,
    )

    fun toRecord(book: Book): BookRecord = BookRecord(
        key = book.bookKey,
        name = book.title,
        author = book.author,
        description = null,
        md5 = book.md5 ?: "",
        cover = book.coverPath,
        format = book.format,
        publisher = null,
        size = book.fileSize,
        page = null,
        path = book.filePath,
        charset = null,
    )
}
