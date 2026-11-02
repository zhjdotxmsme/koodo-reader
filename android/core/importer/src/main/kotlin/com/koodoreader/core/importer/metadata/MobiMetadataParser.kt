package com.koodoreader.core.importer.metadata

import com.koodoreader.core.importer.BookMetadata
import com.koodoreader.engine.mobi.MobiParser
import java.io.File

object MobiMetadataParser {
    fun parse(file: File): BookMetadata {
        val metadata = MobiParser.parse(file)?.metadata
            ?: throw IllegalArgumentException("not a readable MOBI: ${file.path}")
        return BookMetadata(
            name = metadata.title,
            author = metadata.author,
            description = metadata.description,
            publisher = metadata.publisher,
        )
    }
}
