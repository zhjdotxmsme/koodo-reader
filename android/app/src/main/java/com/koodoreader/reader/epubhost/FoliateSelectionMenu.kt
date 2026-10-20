package com.koodoreader.reader.epubhost

/**
 * When the foliate WebView reports a non-blank excerpt, NativeEpubScreen
 * shows the same highlight / dictionary / translate / note chrome as Canvas.
 */
object FoliateSelectionMenu {

    fun visible(excerpt: String?): Boolean = !excerpt.isNullOrBlank()

    fun highlighted(cfi: String, noteCfis: List<String>): Boolean =
        cfi.isNotBlank() && noteCfis.any { it == cfi }
}
