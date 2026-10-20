package com.koodoreader.reader.shell

/**
 * Classifies a tap on a laid-out line into the native reader action:
 * image fullscreen, follow a link, open a footnote, or fall through to paging.
 *
 * Pure so the decision table is JVM-testable; the Compose screen maps each
 * [ReaderTapKind] onto a dialog / [LinkRouter] / CFI jump.
 */
sealed class ReaderTapKind {
    data class Image(val src: String) : ReaderTapKind()
    data class Link(val href: String) : ReaderTapKind()
    data class Footnote(val number: Int) : ReaderTapKind()
    data object None : ReaderTapKind()
}

object ReaderTapTarget {

    private val URL = Regex("""(?i)((?:https?|mailto):[^\s<>]+|epubcfi[:\(][^\s<>]+)""")
    private val FOOTNOTE = Regex("""\[(\d{1,4})\]""")

    fun classify(
        imageSrc: String?,
        text: String,
        linkHref: String? = null,
    ): ReaderTapKind {
        if (!imageSrc.isNullOrBlank()) return ReaderTapKind.Image(imageSrc)
        val explicit = linkHref?.trim().orEmpty()
        if (explicit.isNotEmpty()) return ReaderTapKind.Link(explicit)
        val url = URL.find(text)?.value?.trimEnd('.', ',', ';', ')', ']')
        if (!url.isNullOrBlank()) return ReaderTapKind.Link(url)
        val number = FOOTNOTE.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (number != null && number > 0) return ReaderTapKind.Footnote(number)
        return ReaderTapKind.None
    }
}

/** Resolve a relative chapter href against the session's spine hrefs. */
object ReaderHrefJump {
    fun chapterIndex(href: String, chapterHrefs: List<String>): Int? {
        val path = href.substringBefore('#').substringAfterLast('/')
        if (path.isBlank()) return null
        val idx = chapterHrefs.indexOfFirst {
            it.substringAfterLast('/').equals(path, ignoreCase = true)
        }
        return idx.takeIf { it >= 0 }
    }
}
