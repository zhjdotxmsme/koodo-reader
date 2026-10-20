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
    fun layoutResult(): LayoutResult? = null
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
    /**
     * monotonic 章边界：`chapterStartPages[i]` = spineIndex i 首行的页号。
     * 长度 = chapterCount（与 [chapterCount] 同步，越界 clamp 到 0/末页）。
     * 一次 O(pages×columns×lines)，之后每次 [pageOfChapter] /
     * [currentPageFrom] 都是 O(log n) binarySearch。
     */
    private val chapterStartPages: IntArray,
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

    // P9-anr (round-3 真机): 原实现每章线性扫全部 pages×columns×lines，
    // `chapterInfo` 在**每次 currentPage 变更的 recomposition** 调 chapterCount
    // 次 → 几百页的 EPUB 上 O(chapters×pages×lines) → 首次翻页 recomposition
    // 卡 ≥ 5s → 系统 "Input dispatching timed out" 强杀 (ANR in
    // com.koodoreader.reader … Waited 5001ms for MotionEvent DOWN)。
    // 改：构造时预计算一次 chapterStartPages，此处 binary search — 从
    // O(n×m) 降到 O(log m)，且完全复用缓存。
    override fun pageOfChapter(chapterOrder: Int): Int =
        if (chapterStartPages.isEmpty()) 0
        else {
            val clamped = chapterOrder.coerceIn(0, chapterStartPages.lastIndex)
            chapterStartPages.getOrElse(clamped) { 0 }.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }

    /**
     * 页号 → 章节号（最大 i 满足 chapterStartPages[i] <= page）。
     * 用于 `chapterInfo` / `addBookmark`：O(log n) 一次到位，
     * 替代调用侧的 O(n) `lastOrNull` 循环。
     */
    fun chapterOf(page: Int): Int {
        if (chapterStartPages.isEmpty() || pageCount == 0) return 0
        val p = page.coerceIn(0, pageCount - 1)
        // 二分：找到最后一个 chapterStart[i] <= p 的 i。
        var lo = 0; var hi = chapterStartPages.size - 1; var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chapterStartPages[mid] <= p) { ans = mid; lo = mid + 1 }
            else hi = mid - 1
        }
        return ans
    }

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
                lineHeightMultiple = options.lineHeightMultiple,
                marginHorizontalPx = options.marginHorizontalPx,
                marginVerticalPx = options.marginVerticalPx,
            )
            val result = LayoutEngine(measurer, options).layout(document, tokens)
            // P9-anr (round-3): 预计算 chapterStartPages（monotonic）
            // chapterStartPages[i] = 章 i 首行的页号（无首行的章复用上一章值）。
            // 一次 O(pages×cols×lines)；之后 [pageOfChapter] 全走 binarySearch。
            // 用 first-line-of-spine 判定（与旧 indexOfFirst 语义等价：
            // 「首行 spineIndex ≥ chapterOrder 的页」）。
            val chapterStart = IntArray(chapters.size) { 0 }
            if (chapters.isNotEmpty() && result.pages.isNotEmpty()) {
                // 对 pages 做单次归并扫：记录每章**最小**首行页。
                // pages 按阅读序，cols 按阅读序，所以「章 i 的首行 page」
                // 随 i 单调不减 → 归并扫 O(pages × 章数) → 退化为 O(pages)。
                // 简单实现：遍历 pages，为每一行**首次**出现的 spineIndex 记页。
                val firstSeen = IntArray(chapters.size) { -1 }
                for ((pIdx, page) in result.pages.withIndex()) {
                    for (col in page.columns) {
                        for (line in col.lines) {
                            val s = line.position.spineIndex
                            if (s in firstSeen.indices && firstSeen[s] == -1) {
                                firstSeen[s] = pIdx
                            }
                        }
                    }
                    // 早停：所有章都见过了
                    if (firstSeen.all { it != -1 }) break
                }
                // 前缀填充（某章可能整段被合并/无首行 → 复用上一章首行页）。
                // chapterStart[0] = 0（首章必在第 0 页或 -1），保持单调不减。
                var last = 0
                for (i in chapters.indices) {
                    val v = firstSeen[i]
                    chapterStart[i] = if (v >= 0) { last = v; v } else last
                }
            }
            return PagedDocumentSession(document, result, chapters.map { it.label }, chapterStart)
        }
    }
}