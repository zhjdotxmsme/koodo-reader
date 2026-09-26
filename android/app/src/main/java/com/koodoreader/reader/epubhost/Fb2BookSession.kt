package com.koodoreader.reader.epubhost

import com.koodoreader.engine.fb2.Fb2Document
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.TextMeasurer
import java.io.File

/**
 * FB2 (FictionBook 2) → 分页结果的会话（P5.5a 接入）。
 *
 * 块由 `:engine:fb2` 产出（FB2 XML → HTML 等价物 → D0 扁平化），
 * 分页/CFI 委托 [PagedDocumentSession]——与其余六种文本格式共用同一管线
 * 与同一渲染屏。
 */
class Fb2BookSession private constructor(
    private val paged: PagedDocumentSession,
) : ReaderSession {

    override val pageCount: Int get() = paged.pageCount
    override val chapterCount: Int get() = paged.chapterCount

    override fun pageLines(page: Int): List<LayoutLine> = paged.pageLines(page)
    override fun cfiForPage(page: Int): String? = paged.cfiForPage(page)
    override fun pageForCfi(cfi: String): Int? = paged.pageForCfi(cfi)
    override fun pageOfChapter(chapterOrder: Int): Int = paged.pageOfChapter(chapterOrder)
    override fun chapterLabel(chapterOrder: Int): String = paged.chapterLabel(chapterOrder)
    override fun close() {}

    companion object {

        fun supportsExtension(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() == "fb2"

        fun open(
            file: File,
            viewportWidthPx: Float,
            viewportHeightPx: Float,
            measurer: TextMeasurer,
            options: PaginatorOptions = PaginatorOptions(),
        ): Fb2BookSession {
            val result = Fb2Document.fromBytes(file.readBytes())
            val label = result.title?.takeIf { it.isNotBlank() }
                ?: file.name.substringBeforeLast('.')
            val paged = PagedDocumentSession.create(
                chapters = listOf(
                    PagedDocumentSession.Chapter(index = 0, label = label, blocks = result.blocks),
                ),
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                measurer = measurer,
                options = options,
            )
            return Fb2BookSession(paged)
        }
    }
}
