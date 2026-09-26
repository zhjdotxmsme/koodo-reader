package com.koodoreader.reader.epubhost

import com.koodoreader.core.importer.EpubSpine
import com.koodoreader.engine.layout.CfiAddressing
import com.koodoreader.engine.layout.EpubDocument
import com.koodoreader.engine.layout.HtmlFlattener
import com.koodoreader.engine.layout.LayoutEngine
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.LayoutResult
import com.koodoreader.engine.layout.LayoutTokens
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.TextBlock
import java.io.File

/**
 * EPUB → 分页结果 的集成会话（EPUB 原生阅读屏 步骤②）。
 *
 * 打开时一次性完成：[EpubSpine] 逐章 XHTML → [HtmlFlattener] 扁平化为
 * [TextBlock] → [LayoutEngine] 自绘分页 → [LayoutResult]（每行自带
 * [com.koodoreader.engine.layout.LayoutPosition]，经 [CfiAddressing] 与 CFI
 * 互转——CFI 集成的主线就在这里）。
 *
 * 纯 JVM（无 Android 类型）：measurer 由构造方注入——真机传
 * TextPaint 实现，JVM 测试传 engine/layout 自带的确定性 measurer。
 * 翻页手势/进度是 [result] 之上的宿主职责（步骤③）。
 *
 * 内存口径：整本分页在打开时一次完成（10 万字 ≈ 数十万 LayoutLine，
 * 可控）；懒式逐章分页属优化，不在本步。
 */
class EpubBookSession private constructor(
    val spine: EpubSpine,
    val document: EpubDocument,
    val result: LayoutResult,
) : AutoCloseable {

    val pageCount: Int get() = result.pages.size

    /** 某页的全部已排版行（宿主逐行绘制；行含 x/y/baseline/字号/对齐）。 */
    fun pageLines(page: Int): List<LayoutLine> =
        result.pages.getOrNull(page)?.columns?.flatMap { it.lines } ?: emptyList()

    /**
     * 该页第一个已排版行的位置 CFI —— 翻页时写阅读进度用
     * （`epubcfi(/6/<spine>!<chain>/<element>/<offsetStep>:<char>)`）。
     * 空页（理论上分页器不产生）返回 null。
     */
    fun cfiForPage(page: Int): String? =
        result.pages.getOrNull(page)?.columns
            ?.firstNotNullOfOrNull { col -> col.lines.firstOrNull() }
            ?.let { CfiAddressing.toCfi(it.position) }

    /**
     * 把一个位置 CFI 解析回分页位置并落到对应页（标注/书签打开用）。
     * 位置不在本文档（或格式不符）时返回 null。
     */
    fun pageForCfi(cfi: String): Int? {
        val position = CfiAddressing.fromCfi(cfi, result) ?: return null
        return result.pages.indexOfFirst { page ->
            page.columns.any { col -> col.lines.any { it.position == position } }
        }.takeIf { it >= 0 }
            // 位置存在但恰无行精确落点（比如章首）→ 二分：找第一个 ≥ 该位置的页
            ?: result.pages.indexOfFirst { page ->
                page.columns.firstOrNull()?.lines?.firstOrNull()?.let {
                    it.position.spineIndex == position.spineIndex &&
                        it.position.elementIndex >= position.elementIndex
                } == true
            }.takeIf { it >= 0 }
    }

    /** 章节边界：第 [chapterOrder] 章（0 基）所在的页号。 */
    fun pageOfChapter(chapterOrder: Int): Int =
        result.pages.indexOfFirst { page ->
            page.columns.any { col -> col.lines.any { it.position.spineIndex >= chapterOrder } }
        }.coerceAtLeast(0)

    override fun close() {
        spine.close()
    }

    companion object {

        /**
         * 打开并分页整本 EPUB。
         *
         * @param file the .epub 文件
         * @param viewportWidthPx / viewportHeightPx 屏幕内容区像素（排PageSize）
         * @param measurer 文字测量（真机 TextPaint / 测试 Monospaced）
         */
        fun open(
            file: File,
            viewportWidthPx: Float,
            viewportHeightPx: Float,
            measurer: com.koodoreader.engine.layout.TextMeasurer,
            options: PaginatorOptions = PaginatorOptions(),
        ): EpubBookSession {
            val spine = EpubSpine.open(file)
            try {
                val tokens = LayoutTokens.defaults(viewportWidthPx, viewportHeightPx)
                val document = EpubDocument(
                    spine.chapters.mapIndexed { order, chapter ->
                        val html = spine.readChapter(chapter.index).orEmpty()
                        HtmlFlattener.DEFAULT.flatten(
                            html = html,
                            spineIndex = order + 1, // CFI spine 步是 1 基
                            href = chapter.href,
                        )
                    },
                )
                val result = LayoutEngine(measurer, options).layout(document, tokens)
                return EpubBookSession(spine, document, result)
            } catch (t: Throwable) {
                spine.close()
                throw t
            }
        }
    }
}
