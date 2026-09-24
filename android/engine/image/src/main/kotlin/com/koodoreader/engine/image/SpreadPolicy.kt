package com.koodoreader.engine.image

/** 阅读方向：日漫/部分港台漫画是右到左（RTL）。 */
enum class ReadingDirection { LTR, RTL }

/**
 * 单页/双页（跨页）配置。默认值对齐桌面漫画阅读器：可开双页、封面单独一页、
 * 从左到右。
 */
data class SpreadConfig(
    /** 是否允许双页跨页（窄屏/竖屏时宿主仍可强制单页）。 */
    val doublePage: Boolean = true,
    val direction: ReadingDirection = ReadingDirection.LTR,
    /** 首图（封面）单独一页，之后的 1-2、3-4… 成对 —— 对齐桌面 comic 的翻页手感。 */
    val coverAsSingle: Boolean = true,
)

/**
 * 当前屏上的一个「跨页单元」。
 *
 * @param start 归属页号（该跨页的第一页，作为进度/落点用它）
 * @param pages 该跨页包含的页号（升序；末页可能是单张）
 * @param visualOrder 绘制顺序：RTL 时左右互换（`pages` 仍是升序，语义不翻转）
 */
data class Spread(
    val start: Int,
    val pages: List<Int>,
    val visualOrder: List<Int>,
    val isDouble: Boolean,
) {
    /** 该跨页的最后一页；空卷（无页）时退化为 [start]。 */
    val last: Int get() = pages.lastOrNull() ?: start
}

/**
 * 单页/双页排版策略（纯逻辑，供 Compose 宿主直接消费）。
 *
 * 分组规则（[SpreadConfig.coverAsSingle] = true 时）：
 * ```
 * 页号:  0 | 1 2 | 3 4 | 5 6 | ...
 * ```
 * 关闭时：
 * ```
 * 页号:  0 1 | 2 3 | 4 5 | ...
 * ```
 * 末页落单时按单页处理（不会出现「半张空白」——桌面同样如此）。
 */
object SpreadPolicy {

    fun spreadAt(index: Int, pageCount: Int, config: SpreadConfig): Spread {
        if (pageCount <= 0) return Spread(0, emptyList(), emptyList(), false)
        val i = index.coerceIn(0, pageCount - 1)
        if (!config.doublePage || pageCount == 1) return single(i)
        if (config.coverAsSingle && i == 0) return single(0)
        val first = if (config.coverAsSingle) 1 else 0
        val start = first + ((i - first) / 2) * 2
        val pages = listOf(start, start + 1).filter { it < pageCount }
        if (pages.size == 1) return single(pages[0])
        return Spread(
            start = start,
            pages = pages,
            visualOrder = if (config.direction == ReadingDirection.RTL) pages.reversed() else pages,
            isDouble = true,
        )
    }

    /** 下一页所属跨页的落点（可能跨 2 页）。 */
    fun next(index: Int, pageCount: Int, config: SpreadConfig): Int {
        if (pageCount <= 0) return 0
        val spread = spreadAt(index, pageCount, config)
        return (spread.last + 1).coerceAtMost(pageCount - 1)
    }

    /** 上一页所属跨页的起始页。 */
    fun previous(index: Int, pageCount: Int, config: SpreadConfig): Int {
        if (pageCount <= 0) return 0
        val spread = spreadAt(index, pageCount, config)
        if (spread.start == 0) return 0
        return spreadAt(spread.start - 1, pageCount, config).start
    }

    fun isFirst(index: Int, pageCount: Int, config: SpreadConfig): Boolean =
        pageCount <= 0 || spreadAt(index, pageCount, config).start == 0

    fun isLast(index: Int, pageCount: Int, config: SpreadConfig): Boolean =
        pageCount <= 0 || spreadAt(index, pageCount, config).last >= pageCount - 1

    /** 单页视口（窄屏/竖屏/用户关掉双页时宿主可强制走这条）。 */
    fun single(index: Int): Spread = Spread(index, listOf(index), listOf(index), false)
}
