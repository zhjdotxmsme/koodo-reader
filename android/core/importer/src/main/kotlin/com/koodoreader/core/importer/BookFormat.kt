package com.koodoreader.core.importer

import java.io.InputStream

enum class BookFormat(val extension: String, val mime: String) {
    EPUB("epub", "application/epub+zip"),
    PDF("pdf", "application/pdf"),
    TXT("txt", "text/plain"),
    MOBI("mobi", "application/x-mobipocket-ebook"),
    AZW3("azw3", "application/vnd.amazon.ebook"),
    AZW("azw", "application/vnd.amazon.ebook"),
    HTM("htm", "text/html"),
    HTML("html", "text/html"),
    XML("xml", "application/xml"),
    XHTML("xhtml", "application/xhtml+xml"),
    MHTML("mhtml", "message/rfc822"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    MD("md", "text/markdown"),
    FB2("fb2", "application/x-fictionbook+xml"),
    CBZ("cbz", "application/x-cbz"),
    CBT("cbt", "application/x-cbt"),
    CB7("cb7", "application/x-cb7");

    companion object {
        fun detect(name: String?, mime: String?, openStream: () -> InputStream): FormatDetection =
            FormatDetector.detect(name, mime, openStream)
    }
}

sealed interface FormatDetection {
    data class Supported(val format: BookFormat) : FormatDetection
    data class Unsupported(val reason: String) : FormatDetection
    data object Unknown : FormatDetection
}
