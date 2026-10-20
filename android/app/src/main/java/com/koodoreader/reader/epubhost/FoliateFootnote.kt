package com.koodoreader.reader.epubhost

/**
 * Foliate noteref clicks arrive as `{ href, excerpt }`. A same-document
 * excerpt opens the Compose sheet; otherwise the host navigates [href].
 */
object FoliateFootnote {

    sealed class Decision {
        data class Show(val text: String) : Decision()
        data class Navigate(val href: String) : Decision()
        data object Ignore : Decision()
    }

    fun decide(excerpt: String, href: String): Decision = when {
        excerpt.isNotBlank() -> Decision.Show(excerpt)
        href.isNotBlank() -> Decision.Navigate(href)
        else -> Decision.Ignore
    }

    fun showSheet(text: String?): Boolean = text != null
}
