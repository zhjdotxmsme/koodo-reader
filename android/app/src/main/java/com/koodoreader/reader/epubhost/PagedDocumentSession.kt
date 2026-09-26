package com.koodoreader.reader.epubhost

import com.koodoreader.engine.layout.CfiAddressing
import com.koodoreader.engine.layout.EpubDocument
import com.koodoreader.engine.layout.LayoutEngine
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.LayoutResult
import com.koodoreader.engine.layout.LayoutTokens
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.TextBlock
import com.koodoreader.engine.layout.TextMeasurer

/**
 * 渲染屏依赖的最小会话接口（步骤③④ 复用：EPUB/TXT/MD/MOBI 四个 session 都
 * 实现它，一个 Compose 屏即可渲染全部文本格式）。
 */
interface ReaderSession : AutoCloseable {
    val pageCount: Int
    val chapterCount: Int

    /** 第 [page] 页的全部已排版行（x/baseline/字号/文本由引擎产出）。 */
    fun pageLines(page: Int): List<LayoutLine>

    /** 翻页时写阅读进度用的页首行位置 CFI（没有内容页返回 null）。 */
    fun cfiForPage(page: Int): String?

    /** 标注/书签打开：把位置 CFI 还原回页号（不在本文档返回 null）。 */
    fun pageForCfi(cfi: String): Int?

    /** 第 [chapterOrder] 章（0 基）开始的页号。 */
    fun pageOfChapter(chapterOrder: Int): Int

    /** 章节显示标签（进度条：“第 N 章 · label”）。 */
    fun chapterLabel(chapterOrder: Int): String
}

/**
 * 通用「章节文本 → 分页 → CFI」会话（EPUB/TXT/MD/MOBI 共享）。
 *
 * 输入是已经扁平化好的每章 [TextBlock] 列表（内部按 spine 序组装为
 * [EpubDocument] 交给 [LayoutEngine]），输出统一为：
 *  - [pageLines]/[pageCount]：渲染屏画布直接消费；
 *  - [cfiForPage]/[pageForCfi]：CFI 主线（CfiAddressing，
 *    `epubcfi(/6/<spine>!<chain>/<element>/<offsetStep>:<char>)`）；
 *  - [pageOfChapter]/[chapterLabel]：进度条与目录锚点。
 *
 * 纯 JVM：measurer 注入（真机 TextPaint / 测试确定性实现）。
 */
class PagedDocumentSession private constructor(
    private val document: EpubDocument,
    val result: LayoutResult,
    private val labels: List<String>,
) : ReaderSession {

    val spine: List<SpineItem> = document.spine

    override val pageCount: Int get() = result.pages.size
    override val chapterCount: Int get() = document.spine.size

    override fun pageLines(page: Int): List<LayoutLine> =
        result.pages.getOrNull(page)?.columns?.flatMap { it.lines } ?: emptyList()

    override fun cfiForPage(page: Int): String? =
        result.pages.getOrNull(page)?.columns
            ?.firstNotNullOfOrNull { col -> col.lines.firstOrNull() }
            ?.let { CfiAddressing.toCfi(it.position) }

    override fun pageForCfi(cfi: String): Int? {
        val position = CfiAddressing.fromCfi(cfi, result) ?: return null
        return result.pages.indexOfFirst { page ->
            page.columns.any { col -> col.lines.any { it.position == position } }
        }.takeIf { it >= 0 }
            ?: result.pages.indexOfFirst { page ->
                page.columns.firstOrNull()?.lines?.firstOrNull()?.let {
                    it.position.spineIndex == position.spineIndex &&
                        it.position.elementIndex >= position.elementIndex
                } == true
            }.takeIf { it >= 0 }
    }

    override fun pageOfChapter(chapterOrder: Int): Int =
        result.pages.indexOfFirst { page ->
            page.columns.any { col -> col.lines.any { it.position.spineIndex >= chapterOrder } }
        }.coerceAtLeast(0)

    override fun chapterLabel(chapterOrder: Int): String =
        labels.getOrNull(chapterOrder) ?: ""

    override fun close() {}

    /** 每个元素 = 一章：index（0 基阅读序）/ label（进度显示）/ blocks。 */
    data class Chapter(
        val index: Int,
        val label: String,
        val blocks: List<TextBlock>,
    )

    companion object {

        fun create(
            chapters: List<Chapter>,
            viewportWidthPx: Float,
            viewportHeightPx: Float,
            measurer: TextMeasurer,
            options: PaginatorOptions = PaginatorOptions(),
        ): PagedDocumentSession {
            val document = EpubDocument(
                chapters.map { ch ->
                    SpineItem(
                        index = ch.index + 1, // CFI spine 步是 1 基
                        href = "",
                        title = ch.label,
                        blocks = ch.blocks,
                    )
                },
            )
            val tokens = LayoutTokens.defaults(viewportWidthPx, viewportHeightPx)
            val result = LayoutEngine(measurer, options).layout(document, tokens)
            return PagedDocumentSession(document, result, chapters.map { it.label })
        }
    }
}