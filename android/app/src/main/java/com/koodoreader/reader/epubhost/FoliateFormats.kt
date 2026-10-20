package com.koodoreader.reader.epubhost

/**
 * Which imported extensions the visible foliate-js WebView hosts.
 *
 * PDF and comics keep their dedicated screens; CBR stays on the island.
 * Foliate itself does not parse DOCX — Kotlin converts DOCX to HTML first,
 * then this host opens the HTML.
 */
object FoliateFormats {

    private val HOSTED: Set<String> = setOf(
        "epub",
        "mobi", "azw", "azw3",
        "fb2", "fbz",
        "txt", "md",
        "html", "htm", "xhtml", "xml", "mhtml",
        "docx",
    )

    fun usesHost(format: String?): Boolean {
        if (format.isNullOrBlank()) return false
        return format.lowercase() in HOSTED
    }
}
