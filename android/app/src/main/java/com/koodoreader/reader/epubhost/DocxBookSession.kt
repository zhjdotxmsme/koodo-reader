package com.koodoreader.reader.epubhost

import com.koodoreader.engine.docx.DocxDocument
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.TextMeasurer
import java.io.File

/**
 * DOCX (WordprocessingML) → 分页结果的会话（P5.5d 接入）。
 *
 * 块由 `:engine:docx` 产出（OOXML zip → document.xml → HTML 等价物 → D0
 * 扁平化），分页/CFI 委托 [PagedDocumentSession]。
 */
class DocxBookSession private constructor(
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
            name.substringAfterLast('.', "").lowercase() == "docx"

        fun open(
            file: File,
            viewportWidthPx: Float,
            viewportHeightPx: Float,
            measurer: TextMeasurer,
            options: PaginatorOptions = PaginatorOptions(),
        ): DocxBookSession {
            val result = DocxDocument.fromFile(file)
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
            return DocxBookSession(paged)
        }
    }
}
