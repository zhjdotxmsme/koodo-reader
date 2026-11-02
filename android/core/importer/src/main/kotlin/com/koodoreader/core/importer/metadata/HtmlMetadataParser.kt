package com.koodoreader.core.importer.metadata

import com.koodoreader.core.importer.BookMetadata
import java.io.File

object HtmlMetadataParser {
    fun parse(file: File): BookMetadata {
        val html = readMetadataText(file)
        return BookMetadata(
            name = elementText(html, "title"),
            author = metaText(html, "author", "dc.author", "dc.creator"),
            description = metaText(html, "description", "dc.description"),
            publisher = metaText(html, "publisher", "dc.publisher"),
        )
    }

    private fun metaText(html: String, vararg names: String): String? {
        val metas = Regex("""(?is)<meta\b([^>]*)>""").findAll(html)
        for (meta in metas) {
            val attributes = meta.groupValues[1]
            val name = attribute(attributes, "name")
            if (names.any { it.equals(name, ignoreCase = true) }) {
                return attribute(attributes, "content")?.let(::normalizeMetadataText)
            }
        }
        return null
    }

    private fun attribute(attributes: String, name: String): String? {
        val match = Regex(
            """(?i)(?:^|\s)${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)')""",
        ).find(attributes) ?: return null
        return match.groupValues[1].ifEmpty { match.groupValues[2] }
    }
}
