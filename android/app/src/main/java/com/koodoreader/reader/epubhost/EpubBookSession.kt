package com.koodoreader.reader.epubhost

import com.koodoreader.core.importer.EpubSpine
import com.koodoreader.engine.layout.HtmlFlattener
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.TextMeasurer
import java.io.File

/**
 * EPUB → 分页结果 的集成会话（EPUB 原生阅读屏 步骤②，多格式重构后）。
 *
 * 打开时一次性完成：[EpubSpine] 逐章 XHTML → [HtmlFlattener] 扁平化为
 * [TextBlock] → [PagedDocumentSession]（LayoutEngine 自绘分页 + CFI 读写）。
 * 全部分页/CFI 能力委托给 [paged]，本类只负责「EPUB spine 生命周期」
 * （[close] 释放归档句柄）与「章节标签」。
 *
 * 纯 JVM：measurer 由构造方注入——真机传 TextPaint 实现（AndroidTextMeasurer），
 * JVM 测试传 engine/layout 自带的确定性 measurer。
 */
class EpubBookSession private constructor(
    val spine: EpubSpine,
    val paged: PagedDocumentSession,
) : ReaderSession {

    override val pageCount: Int get() = paged.pageCount
    override val chapterCount: Int get() = paged.chapterCount

    /** 某页的全部已排版行（宿主逐行绘制；行含 x/y/baseline/字号/对齐）。 */
    override fun pageLines(page: Int): List<LayoutLine> = paged.pageLines(page)

    /**
     * 该页第一个已排版行的位置 CFI —— 翻页时写阅读进度用
     * （`epubcfi(/6/<spine>!<chain>/<element>/<offsetStep>:<char>)`）。
     */
    override fun cfiForPage(page: Int): String? = paged.cfiForPage(page)

    /** 把一个位置 CFI 解析回分页位置并落到对应页（标注/书签打开用）。 */
    override fun pageForCfi(cfi: String): Int? = paged.pageForCfi(cfi)

    /** 章节边界：第 [chapterOrder] 章（0 基）所在的页号。 */
    override fun pageOfChapter(chapterOrder: Int): Int = paged.pageOfChapter(chapterOrder)

    /** 章节显示标签（进度条；EPUB 用 OPF 里的章节文件名）。 */
    override fun chapterLabel(chapterOrder: Int): String = paged.chapterLabel(chapterOrder)

    override fun close() {
        spine.close()
    }

    companion object {

        /**
         * 打开并分页整本 EPUB。
         *
         * @param file the .epub 文件
         * @param viewportWidthPx / viewportHeightPx 屏幕内容区像素
         * @param measurer 文字测量（真机 TextPaint / 测试 Monospaced）
         */
        fun open(
            file: File,
            viewportWidthPx: Float,
            viewportHeightPx: Float,
            measurer: TextMeasurer,
            options: PaginatorOptions = PaginatorOptions(),
        ): EpubBookSession {
            val spine = EpubSpine.open(file)
            try {
                val chapters = spine.chapters.mapIndexed { order, chapter ->
                    val html = spine.readChapter(chapter.index).orEmpty()
                    PagedDocumentSession.Chapter(
                        index = order,
                        label = chapter.href.substringAfterLast('/'),
                        blocks = HtmlFlattener.DEFAULT.flatten(
                            html = html,
                            spineIndex = order + 1, // CFI spine 步是 1 基
                            href = chapter.href,
                        ).blocks,
                    )
                }
                val paged = PagedDocumentSession.create(
                    chapters = chapters,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    measurer = measurer,
                    options = options,
                )
                return EpubBookSession(spine, paged)
            } catch (t: Throwable) {
                spine.close()
                throw t
            }
        }
    }
}