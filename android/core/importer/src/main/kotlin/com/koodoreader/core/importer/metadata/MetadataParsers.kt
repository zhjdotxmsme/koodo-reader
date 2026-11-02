package com.koodoreader.core.importer.metadata

import com.koodoreader.core.importer.BookFormat
import com.koodoreader.core.importer.BookMetadata
import java.io.File
import java.nio.charset.Charset

/** Best-effort metadata entry point; unreadable metadata never fails an import. */
object MetadataParsers {
    fun parse(format: BookFormat, file: File, fallbackName: String): BookMetadata = runCatching {
        when (format) {
            BookFormat.EPUB -> EpubMetadataParser.parse(file)
            BookFormat.MOBI, BookFormat.AZW, BookFormat.AZW3 -> MobiMetadataParser.parse(file)
            BookFormat.DOCX -> DocxMetadataParser.parse(file)
            BookFormat.FB2 -> Fb2MetadataParser.parse(file)
            BookFormat.HTML, BookFormat.HTM, BookFormat.XHTML, BookFormat.XML, BookFormat.MHTML ->
                HtmlMetadataParser.parse(file)
            else -> BookMetadata(fallbackName, null, null, null)
        }
    }.getOrElse { BookMetadata(fallbackName, null, null, null) }
}

internal const val METADATA_TEXT_LIMIT = 256 * 1024

internal fun readMetadataText(file: File): String {
    val bytes = file.inputStream().use { it.readNBytes(METADATA_TEXT_LIMIT) }
    val ascii = String(bytes.copyOf(minOf(bytes.size, 512)), Charsets.ISO_8859_1)
    val declared = Regex("""(?:encoding|charset)\s*=\s*["']?([^"';\s>]+)""", RegexOption.IGNORE_CASE)
        .find(ascii)?.groupValues?.get(1)
    val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    return String(bytes, charset)
}

internal fun elementBody(text: String, tag: String): String? =
    Regex("""(?is)<${Regex.escape(tag)}\b[^>]*>(.*?)</${Regex.escape(tag)}\s*>""")
        .find(text)?.groupValues?.get(1)

internal fun elementText(text: String, tag: String): String? =
    elementBody(text, tag)?.let(::normalizeMetadataText)

internal fun normalizeMetadataText(raw: String): String? {
    val text = decodeMetadataEntities(raw.replace(Regex("(?s)<[^>]*>"), ""))
        .replace(Regex("[\\s\\u00A0]+"), " ")
        .trim()
    return text.takeIf { it.isNotEmpty() }
}

internal fun decodeMetadataEntities(text: String): String = text
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
        match.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
    }
    .replace(Regex("&#([0-9]+);")) { match ->
        match.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
    }
    .replace("&amp;", "&")
