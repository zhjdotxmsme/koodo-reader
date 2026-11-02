package com.koodoreader.core.importer.metadata

import com.koodoreader.core.importer.BookMetadata
import com.koodoreader.core.importer.EpubBook
import java.io.File

object EpubMetadataParser {
    fun parse(file: File): BookMetadata {
        val metadata = EpubBook.parse(file).metadata
        return BookMetadata(
            name = metadata.title,
            author = metadata.creator,
            description = metadata.description,
            publisher = metadata.publisher,
        )
    }
}
