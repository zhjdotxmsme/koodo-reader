package com.koodoreader.core.importer

import com.koodoreader.engine.layout.HtmlFlattener
import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.reader.ChapterDescriptor
import com.koodoreader.engine.layout.reader.ChapterLoader
import java.io.File

enum class EpubLayoutKind {
    REFLOWABLE,
    FIXED_LAYOUT,
}

/**
 * EPUB spine as a [ChapterLoader]: open parses OPF only; [load] reads one XHTML body.
 */
class EpubChapterLoader private constructor(
    private val spine: EpubSpine,
    private val labels: Map<String, String>,
    val layoutKind: EpubLayoutKind,
) : ChapterLoader {

    private var chapterReads = 0

    fun chapterReadCount(): Int = chapterReads

    override fun descriptors(): List<ChapterDescriptor> =
        spine.chapters.mapIndexed { order, chapter ->
            val label = labels[chapter.href.lowercase()] ?: chapter.href.substringAfterLast('/')
            ChapterDescriptor(
                id = chapterId(order, chapter.idref),
                title = label,
                level = 1,
                charCount = 0,
            )
        }

    override fun load(id: String): SpineItem {
        val order = orderOf(id)
        val chapter = spine.chapters[order]
        chapterReads++
        val html = spine.readChapter(order).orEmpty()
        val blocks = HtmlFlattener.DEFAULT.flatten(
            html = html,
            spineIndex = order + 1,
            href = chapter.href,
        ).blocks
        return SpineItem(
            index = order,
            href = chapter.href,
            title = labels[chapter.href.lowercase()],
            blocks = blocks,
        )
    }

    override fun close() = spine.close()

    /** Archive handle for href resolution and tests (closed with [close]). */
    val epubSpine: EpubSpine get() = spine

    companion object {
        fun open(file: File): EpubChapterLoader {
            val spine = EpubSpine.open(file)
            return try {
                val labels = EpubToc.chapterLabels(spine)
                val kind = layoutKindOf(spine)
                if (kind == EpubLayoutKind.FIXED_LAYOUT) {
                    spine.close()
                    throw IllegalArgumentException("fixed-layout EPUB is not supported for reflow reading")
                }
                EpubChapterLoader(spine, labels, kind)
            } catch (t: Throwable) {
                spine.close()
                throw t
            }
        }

        private fun layoutKindOf(spine: EpubSpine): EpubLayoutKind {
            val opf = spine.readResource(spine.opfHref)?.toString(Charsets.UTF_8).orEmpty()
            val fixed = opf.contains("rendition:layout", ignoreCase = true) &&
                opf.contains("pre-paginated", ignoreCase = true)
            return if (fixed) EpubLayoutKind.FIXED_LAYOUT else EpubLayoutKind.REFLOWABLE
        }
    }

    private fun chapterId(order: Int, idref: String): String =
        if (idref.isNotBlank()) "idref:$idref" else "spine:$order"

    private fun orderOf(id: String): Int {
        if (id.startsWith("spine:")) {
            return id.removePrefix("spine:").toInt()
        }
        val idref = id.removePrefix("idref:")
        val i = spine.chapters.indexOfFirst { it.idref == idref }
        if (i < 0) throw IllegalArgumentException("unknown chapter id: $id")
        return i
    }
}
