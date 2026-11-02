package com.koodoreader.core.importer.metadata

import com.koodoreader.core.archive.ZipArchives
import com.koodoreader.core.importer.BookMetadata
import java.io.File

object DocxMetadataParser {
    fun parse(file: File): BookMetadata = ZipArchives.open(file).use { archive ->
        val xml = archive.entry("docProps/core.xml")
            ?.let { archive.readBytes(it.name).toString(Charsets.UTF_8) }
            ?: return@use BookMetadata(null, null, null, null)
        BookMetadata(
            name = elementText(xml, "dc:title"),
            author = elementText(xml, "dc:creator"),
            description = elementText(xml, "dc:description"),
            publisher = elementText(xml, "dc:publisher"),
        )
    }
}
