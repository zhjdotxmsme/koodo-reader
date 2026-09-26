package com.koodoreader.reader.epubhost

import com.koodoreader.engine.htmlbook.HtmlDocument
import com.koodoreader.engine.htmlbook.MhtmlDocument
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.TextMeasurer
import java.io.File

/**
 * HTML / XHTML / HTM / XML / MHTML → 分页结果的会话（P5.5b/c 接入）。
 *
 * 产出块由 `:engine:htmlbook` 负责（HtmlDocument / MhtmlDocument，复用 D0
 * 扁平化与 engine:text 的编码探测），分页/CFI 委托 [PagedDocumentSession]
 * ——与 EPUB/TXT/MD/MOBI 同一条管线、同一个渲染屏。
 *
 * 章节：单文件 HTML 视为一章（标题取 `<title>`，进度条显示）；按 `<h1>`
 * 划章的目录卡后续再做（目录 UI 与 EPUB 的 TOC 一并）。
 */
class WebBookSession private constructor(
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

        /** 是否 Web 文档轨可处理的扩展名（HTML/HTM/XHTML/XML/MHTML/MHT）。 */
        fun supportsExtension(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in setOf("html", "htm", "xhtml", "xml", "mhtml", "mht")
        }

        fun open(
            file: File,
            viewportWidthPx: Float,
            viewportHeightPx: Float,
            measurer: TextMeasurer,
            options: PaginatorOptions = PaginatorOptions(),
        ): WebBookSession {
            val ext = file.name.substringAfterLast('.', "").lowercase()
            val bytes = file.readBytes()
            val (blocks, title) = if (ext == "mhtml" || ext == "mht") {
                MhtmlDocument.blocks(bytes) to null
            } else {
                val result = HtmlDocument.fromBytes(bytes)
                result.blocks to result.title
            }
            val label = title?.takeIf { it.isNotBlank() }
                ?: file.name.substringBeforeLast('.')
            val paged = PagedDocumentSession.create(
                chapters = listOf(
                    PagedDocumentSession.Chapter(index = 0, label = label, blocks = blocks),
                ),
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                measurer = measurer,
                options = options,
            )
            return WebBookSession(paged)
        }
    }
}
