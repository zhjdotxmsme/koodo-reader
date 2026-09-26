package com.koodoreader.reader.epubhost

import com.koodoreader.engine.layout.HtmlFlattener
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.TextMeasurer
import com.koodoreader.engine.mobi.MobiBook
import com.koodoreader.engine.mobi.MobiParser
import java.io.File

/**
 * MOBI / AZW3 / AZW → 分页结果的会话（多格式接入）。
 *
 * [MobiParser] 产出 [MobiBook]（`text` = 保留原始 HTML 标记的正文），经
 * [HtmlFlattener] 扁平化为 TextBlock 后走 [PagedDocumentSession]（与 EPUB
 * 同管道）。章节：H1/H2 划分由 [HtmlFlattener] 的块级语义自然承载，整书
 * 作为单章会话（多章目录在目录卡挂账）。
 */
class MobiBookSession private constructor(
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

        /** 是否 MOBI 轨可处理的扩展名（MOBI/AZW3/AZW）。 */
        fun supportsExtension(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext == "mobi" || ext == "azw3" || ext == "azw"
        }

        fun open(
            file: File,
            viewportWidthPx: Float,
            viewportHeightPx: Float,
            measurer: TextMeasurer,
            options: PaginatorOptions = PaginatorOptions(),
        ): MobiBookSession {
            val book = MobiParser.parse(file)
                ?: throw IllegalArgumentException("cannot parse MOBI: ${file.path}")
            return fromBook(
                book,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                measurer = measurer,
                options = options,
            )
        }

        /** 从已解析的 [MobiBook] 构造（测试注入程序化 books）。 */
        fun fromBook(
            book: MobiBook,
            viewportWidthPx: Float,
            viewportHeightPx: Float,
            measurer: TextMeasurer,
            options: PaginatorOptions = PaginatorOptions(),
        ): MobiBookSession {
            val title = book.metadata.title?.ifEmpty { null }
                ?: book.pdbName.ifEmpty { "MOBI" }
            val blocks = HtmlFlattener.DEFAULT.flatten(
                html = book.text,
                spineIndex = 1,
            ).blocks
            val paged = PagedDocumentSession.create(
                chapters = listOf(
                    PagedDocumentSession.Chapter(
                        index = 0,
                        label = title,
                        blocks = if (blocks.isEmpty()) emptyList() else blocks,
                    ),
                ),
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                measurer = measurer,
                options = options,
            )
            return MobiBookSession(paged)
        }
    }
}