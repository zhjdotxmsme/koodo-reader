package com.koodoreader.core.importer.metadata

import com.koodoreader.core.importer.BookMetadata
import java.io.File

object Fb2MetadataParser {
    fun parse(file: File): BookMetadata {
        val xml = readMetadataText(file)
        val description = elementBody(xml, "description")
            ?: throw IllegalArgumentException("FB2 without description: ${file.path}")
        val titleInfo = elementBody(description, "title-info")
            ?: throw IllegalArgumentException("FB2 without title-info: ${file.path}")
        val author = elementBody(titleInfo, "author")?.let { block ->
            listOf("first-name", "middle-name", "last-name")
                .mapNotNull { elementText(block, it) }
                .joinToString(" ")
                .takeIf { it.isNotEmpty() }
        }
        val publisher = elementBody(description, "publish-info")
            ?.let { elementText(it, "publisher") }
        return BookMetadata(
            name = elementText(titleInfo, "book-title"),
            author = author,
            description = elementText(titleInfo, "annotation"),
            publisher = publisher,
        )
    }
}
