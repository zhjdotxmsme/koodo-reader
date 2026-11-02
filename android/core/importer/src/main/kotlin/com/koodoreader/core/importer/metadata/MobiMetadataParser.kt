package com.koodoreader.core.importer.metadata

import com.koodoreader.core.importer.BookMetadata
import com.koodoreader.engine.mobi.MobiMetadataReader
import java.io.File

object MobiMetadataParser {
    fun parse(file: File): BookMetadata {
        val metadata = MobiMetadataReader.read(file)
            ?: throw IllegalArgumentException("not a readable MOBI: ${file.path}")
        return BookMetadata(
            name = metadata.title,
            author = metadata.author,
            description = metadata.description,
            publisher = metadata.publisher,
        )
    }
}
