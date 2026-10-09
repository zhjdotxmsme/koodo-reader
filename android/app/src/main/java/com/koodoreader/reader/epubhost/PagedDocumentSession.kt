package com.koodoreader.reader.epubhost

import com.koodoreader.engine.layout.CfiAddressing
import com.koodoreader.engine.layout.EpubDocument
import com.koodoreader.engine.layout.LayoutEngine
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.LayoutPosition
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

    /** The zip-internal href of chapter [index] (e.g. `OEBPS/chapter/01.xhtml`); used to resolve relative `<img src>` paths. */
    fun chapterHref(index: Int): String = ""

    /** Read an image resource. [baseHref] = chapter's zip path; [src] = `<img>` src (relative to chapter dir). Null for formats without inline images. */
    fun readImage(baseHref: String, src: String): ByteArray? = null

    /** Plain text of chapter [chapterOrder] (0-based), for full-text search. */
    fun chapterText(chapterOrder: Int): String = ""

    /** Direct layout result for hit-testing / word selection (null for non-layout sessions). */
    fun layoutResult(): LayoutResult = error("Not a layout session")
}

/**
 * 从已存储的位置 CFI 恢复起始页：无进度、空白或 CFI 不属于本文档时回到第
 * 0 页。翻页写进度（[ReaderProgressPrefs]）/ 打开恢复走这一个入口，纯函数
 * 可 JVM 测。
 */
fun ReaderSession.resumePage(storedCfi: String?): Int {
    if (storedCfi.isNullOrBlank()) return 0
    return pageForCfi(storedCfi)?.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) ?: 0
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

    override fun chapterText(chapterOrder: Int): String =
        spine.getOrNull(chapterOrder)?.blocks?.joinToString("") { it.text } ?: ""

    override fun layoutResult(): LayoutResult = result

    override fun close() {}

    /** 每个元素 = 一章：index（0 基阅读序）/ label（进度显示）/ blocks。 */
    data class Chapter(
        val index: Int,
        val label: String,
        val blocks: List<TextBlock>,
    )

    companion object {

        /**
         * Optional text transform applied to every non-image [TextBlock] before
         * layout. Set by [com.koodoreader.reader.zhconvert.ZhConvertBridge] on
         * application start; `null` (default) = no transform. This single hook
         * covers all text formats (EPUB/TXT/MD/MOBI/WEB/FB2/DOCX) because they
         * all funnel through [create].
         */
        var textTransform: ((String) -> String)? = null

        fun create(
            chapters: List<Chapter>,
            viewportWidthPx: Float,
            viewportHeightPx: Float,
            measurer: TextMeasurer,
            options: PaginatorOptions = PaginatorOptions(),
        ): PagedDocumentSession {
            val effective = textTransform?.let { tr ->
                chapters.map { ch ->
                    ch.copy(blocks = ch.blocks.map { b ->
                        if (b.imageSrc != null || b.text.isEmpty()) return@map b
                        val transformed = tr(b.text)
                        if (transformed == b.text) {
                            b
                        } else {
                            // Text length changed: the old collapsed→source char offsets
                            // no longer align with the (converted) text. Drop them so
                            // TextBlock's length invariant holds and CFI char offsets
                            // fall back to clamping (expected for a script conversion).
                            b.copy(text = transformed, sourceOffsets = null)
                        }
                    })
                }
            } ?: chapters
            val document = EpubDocument(
                effective.map { ch ->
                    SpineItem(
                        index = ch.index + 1, // CFI spine 步是 1 基
                        href = "",
                        title = ch.label,
                        blocks = ch.blocks,
                    )
                },
            )
            val tokens = LayoutTokens(
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                fontSizePx = options.fontSizePx,
            )
            val result = LayoutEngine(measurer, options).layout(document, tokens)
            return PagedDocumentSession(document, result, chapters.map { it.label })
        }
    }
}