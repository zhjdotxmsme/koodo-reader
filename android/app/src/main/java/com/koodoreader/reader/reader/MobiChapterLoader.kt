package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.HtmlFlattener
import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.reader.ChapterDescriptor
import com.koodoreader.engine.layout.reader.ChapterLoader
import com.koodoreader.engine.mobi.MobiBook

/**
 * MOBI / AZW / AZW3 as [ChapterLoader]: H1/H2 boundaries when present, else
 * synthetic ~20k-character slices on the raw HTML string (never one giant layout unit).
 */
class MobiChapterLoader(
    private val book: MobiBook,
) : ChapterLoader {

    private data class Slice(
        val id: String,
        val title: String,
        val html: String,
        val order: Int,
    )

    private val slices: List<Slice> = buildSlices(book)

    override fun descriptors(): List<ChapterDescriptor> =
        slices.map { slice ->
            ChapterDescriptor(
                id = slice.id,
                title = slice.title,
                level = 1,
                charCount = slice.html.length,
            )
        }

    override fun load(id: String): SpineItem {
        val slice = slices.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("unknown chapter id: $id")
        val blocks = HtmlFlattener.DEFAULT.flatten(
            html = slice.html,
            spineIndex = slice.order + 1,
        ).blocks
        return SpineItem(
            index = slice.order,
            href = "mobi:${slice.id}",
            blocks = blocks,
        )
    }

    override fun close() = Unit

    companion object {
        private const val SYNTHETIC_CHARS = 20_000
        private val headingStart = Regex("""(?is)<h([12])\b[^>]*>""")

        private fun buildSlices(book: MobiBook): List<Slice> {
            val html = book.text
            val defaultTitle = book.metadata.title?.takeIf { it.isNotBlank() }
                ?: book.pdbName.ifEmpty { "MOBI" }
            val headingMatches = headingStart.findAll(html).toList()
            if (headingMatches.isNotEmpty()) {
                return headingMatches.mapIndexed { index, match ->
                    val start = match.range.first
                    val end = headingMatches.getOrNull(index + 1)?.range?.first ?: html.length
                    val segment = html.substring(start, end)
                    val title = titleFromHeading(segment) ?: "$defaultTitle ${index + 1}"
                    Slice(
                        id = "heading:$index",
                        title = title,
                        html = segment,
                        order = index,
                    )
                }
            }
            if (html.length <= SYNTHETIC_CHARS) {
                return listOf(
                    Slice(id = "spine:0", title = defaultTitle, html = html, order = 0),
                )
            }
            val out = mutableListOf<Slice>()
            var offset = 0
            var order = 0
            while (offset < html.length) {
                val end = minOf(offset + SYNTHETIC_CHARS, html.length)
                out += Slice(
                    id = "spine:$order",
                    title = "$defaultTitle (${order + 1})",
                    html = html.substring(offset, end),
                    order = order,
                )
                offset = end
                order++
            }
            return out
        }

        private fun titleFromHeading(segment: String): String? {
            val inner = Regex("""(?is)<h[12]\b[^>]*>(.*?)</h[12]>""")
                .find(segment)?.groupValues?.get(1) ?: return null
            return inner.replace(Regex("<[^>]+>"), "").trim().takeIf { it.isNotEmpty() }
        }
    }
}
