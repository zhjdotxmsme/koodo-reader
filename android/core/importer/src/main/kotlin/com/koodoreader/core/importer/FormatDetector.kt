package com.koodoreader.core.importer

import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

internal object FormatDetector {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private val fb2Root = Regex("<(?:[\\w.-]+:)?FictionBook(?=[\\s>/])", RegexOption.IGNORE_CASE)
    private val htmlRoot = Regex(
        "<!DOCTYPE\\s+html\\b|<(?:[\\w.-]+:)?html(?=[\\s>])",
        RegexOption.IGNORE_CASE,
    )
    private val mhtmlRoot = Regex("Content-Type:\\s*multipart/related\\b", RegexOption.IGNORE_CASE)

    fun detect(name: String?, mime: String?, openStream: () -> InputStream): FormatDetection {
        val header = openStream().use { it.readNBytes(8192) }
        detectContent(header, openStream)?.let { return it }

        val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        val normalizedMime = mime?.substringBefore(';')?.trim()
        if (ext == "cbr" || normalizedMime.equals("application/x-cbr", true)) {
            return FormatDetection.Unsupported("CBR")
        }
        val byName = BookFormat.entries.firstOrNull { it.extension == ext }
        val byMime = BookFormat.entries.firstOrNull { it.mime.equals(normalizedMime, true) }
        return (byName ?: byMime)?.let(FormatDetection::Supported) ?: FormatDetection.Unknown
    }

    private fun detectContent(header: ByteArray, openStream: () -> InputStream): FormatDetection? {
        if (header.startsWith(byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), '!'.code.toByte()))) {
            return FormatDetection.Unsupported("CBR")
        }
        if (header.startsWith("%PDF".toByteArray())) return FormatDetection.Supported(BookFormat.PDF)
        if (header.startsWith(byteArrayOf(0x50, 0x4B))) {
            detectZip(openStream)?.let { return FormatDetection.Supported(it) }
        }
        if (header.hasAsciiAt(60, "BOOKMOBI")) return FormatDetection.Supported(BookFormat.MOBI)

        val text = decodeText(header)
        return when {
            fb2Root.containsMatchIn(text) -> FormatDetection.Supported(BookFormat.FB2)
            mhtmlRoot.containsMatchIn(text) -> FormatDetection.Supported(BookFormat.MHTML)
            htmlRoot.containsMatchIn(text) -> FormatDetection.Supported(BookFormat.HTML)
            isPlainText(text) -> FormatDetection.Supported(BookFormat.TXT)
            else -> null
        }
    }

    private fun detectZip(openStream: () -> InputStream): BookFormat? {
        try {
            ZipInputStream(openStream()).use { zip ->
                var epub = false
                var contentTypes = false
                var wordDocument = false
                var hasFiles = false
                var imagesOnly = true
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    hasFiles = true
                    when (entry.name) {
                        "mimetype" -> {
                            val value = zip.readNBytes(128).toString(Charsets.US_ASCII).trim()
                            epub = value == BookFormat.EPUB.mime
                        }
                        "[Content_Types].xml" -> contentTypes = true
                        "word/document.xml" -> wordDocument = true
                    }
                    if (entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in imageExtensions) {
                        imagesOnly = false
                    }
                }
                return when {
                    epub -> BookFormat.EPUB
                    contentTypes && wordDocument -> BookFormat.DOCX
                    hasFiles && imagesOnly -> BookFormat.CBZ
                    else -> null
                }
            }
        } catch (_: IOException) {
            return null
        }
    }

    private fun decodeText(header: ByteArray): String {
        val utf16 = header.size >= 2 && (
            header[0] == 0xFE.toByte() && header[1] == 0xFF.toByte() ||
                header[0] == 0xFF.toByte() && header[1] == 0xFE.toByte()
            )
        return String(header, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
    }

    private fun isPlainText(text: String): Boolean = text.isNotEmpty() && text.all { char ->
        char != '\uFFFD' && char != '\u0000' &&
            (!char.isISOControl() || char == '\t' || char == '\n' || char == '\r' || char == '\u000C')
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun ByteArray.hasAsciiAt(offset: Int, value: String): Boolean {
    val expected = value.toByteArray(Charsets.US_ASCII)
    return size >= offset + expected.size && expected.indices.all { this[offset + it] == expected[it] }
}
