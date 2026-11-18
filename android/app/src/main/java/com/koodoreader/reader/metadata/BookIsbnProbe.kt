package com.koodoreader.reader.metadata

import com.koodoreader.core.data.entity.BookEntity
import com.koodoreader.core.importer.EpubBook
import com.koodoreader.engine.mobi.MobiMetadataReader
import java.io.File

/** 从电子书文件读取 ISBN，供在线元数据检索使用（不入库）。 */
object BookIsbnProbe {

    fun probe(book: BookEntity): String? {
        val path = book.path?.trim().orEmpty()
        if (path.isEmpty()) return null
        val file = File(path)
        if (!file.isFile) return null
        return when (book.format?.lowercase()) {
            "epub" -> runCatching { EpubBook.parse(file).metadata.isbn }.getOrNull()
            "mobi", "azw", "azw3" -> MobiMetadataReader.read(file)?.isbn
            else -> null
        }
    }
}