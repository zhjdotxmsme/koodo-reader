package com.koodoreader.engine.image

import java.io.Closeable
import java.util.concurrent.Executor

/**
 * 一页漫画（已从归档取出、已读出固有尺寸，但**未解码像素**）。
 *
 * 字节数组是共享的：`bytes` 直接指向缓存里的那份数据，宿主解码后应尽快释放
 * （Compose 侧不要长期持有 `bytes`，只持有 Bitmap）。
 */
class ComicPage(
    val index: Int,
    val entryName: String,
    val ext: String,
    val bytes: ByteArray,
    val size: ImageHeader.Size?,
) {
    val byteCount: Int get() = bytes.size

    /** 尺寸未知时为 0，排版层应回退到「按视口等比」而不是除以 0。 */
    val width: Int get() = size?.width ?: 0
    val height: Int get() = size?.height ?: 0

    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ComicPage) return false
        return index == other.index && entryName == other.entryName && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * (31 * index + entryName.hashCode()) + bytes.contentHashCode()

    override fun toString(): String = "ComicPage(#$index, $entryName, ${bytes.size}B, ${width}x$height)"
}

/** 驻留统计（宿主用它决定是否继续预取 / 展示内存压力）。 */
data class ResidentStats(val pages: Int, val bytes: Long)

/** 一次翻页要执行的加载/卸载动作。 */
data class LoadPlan(
    /** 需要读入的页号（升序）。 */
    val toLoad: List<Int>,
    /** 需要释放的页号（升序，含内存预算淘汰）。 */
    val toEvict: List<Int>,
) {
    val isEmpty: Boolean get() = toLoad.isEmpty() && toEvict.isEmpty()

    companion object {
        val EMPTY = LoadPlan(emptyList(), emptyList())
    }
}

/**
 * 懒加载窗口策略 —— **本卡的核心契约**（对齐桌面漫画阅读器）：
 *
 *  - **加载：当前页 + 后 3 页**（`current .. current+3`，到卷尾截断）；
 *  - **卸载：当前页 -4 页及更早**（`index <= current-4`）；
 *  - 保留窗口因此是 `[current-3, current+3]`（最多 7 页驻留），顺序阅读时
 *    往前翻总是命中已驻留页（无闪烁）。
 *
 * 额外一条（**跳页**场景，非顺序阅读）：落到远处（目录跳转/进度条拖动）时，
 * 窗口外的旧页（`> current+3`）一并释放，否则驻留集合会随跳页次数无界增长。
 * 这一条只影响跳页，不改变上面的顺序阅读契约（见 PageLoaderTest）。
 */
object LoadWindow {

    /** 向前预取页数（含当前页共 AHEAD+1 页）。 */
    const val AHEAD = 3

    /** 向后保留页数（用于「往回翻一页」瞬时命中）。 */
    const val KEEP_BEHIND = 3

    /** 卸载阈值：`index <= current - EVICT_BEHIND` 即释放（= KEEP_BEHIND + 1）。 */
    const val EVICT_BEHIND = KEEP_BEHIND + 1

    /** 需要驻留的页号区间（含端点）。 */
    fun retention(current: Int, pageCount: Int): IntRange {
        if (pageCount <= 0) return IntRange.EMPTY
        val c = current.coerceIn(0, pageCount - 1)
        val first = (c - KEEP_BEHIND).coerceAtLeast(0)
        val last = (c + AHEAD).coerceAtMost(pageCount - 1)
        return first..last
    }

    /** 加载目标：`current .. current+AHEAD`，到卷尾截断。 */
    fun loadTargets(current: Int, pageCount: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        val c = current.coerceIn(0, pageCount - 1)
        return (c..(c + AHEAD).coerceAtMost(pageCount - 1)).toList()
    }

    /** 给定当前页与已驻留页，算出本次要加载/卸载什么。 */
    fun plan(current: Int, pageCount: Int, loaded: Set<Int>): LoadPlan {
        if (pageCount <= 0) return LoadPlan.EMPTY
        val c = current.coerceIn(0, pageCount - 1)
        val window = retention(c, pageCount)
        val toLoad = loadTargets(c, pageCount).filter { it !in loaded }
        val toEvict = loaded.filter { it !in window }.sorted()
        return LoadPlan(toLoad, toEvict)
    }
}

/**
 * 懒加载分页读取器接口（constraint ①）。
 *
 * 生命周期：`open/seek -> page(i) -> close`。实现**拥有** [ArchiveExtractor]，
 * `close()` 会一并关闭归档。
 *
 * 线程模型：`seek`/`prefetch`/`page` 可从任意线程调用（实现内部串行化）；
 * `seekAsync` 把真正的 IO 丢到 [Executor]，适合 UI 线程。
 */
interface PageLoader : Closeable {

    val pageCount: Int

    /** 当前页号（始终在 `0..pageCount-1`；空卷为 0）。 */
    val currentIndex: Int

    /**
     * 跳到 [index]（越界自动夹取），同步执行加载/卸载并返回本次计划。
     * 返回的 [LoadPlan] 已把内存预算淘汰也算在 `toEvict` 里。
     */
    fun seek(index: Int): LoadPlan

    /**
     * 异步版 [seek]：立即更新 [currentIndex] 并返回**调用时刻**预测的计划，
     * 真实 IO 在构造时给的 executor 上跑。没有 executor 时退化为同步。
     */
    fun seekAsync(index: Int): LoadPlan

    /** 按当前页重新补窗口（内存淘汰/失败重试后用）。 */
    fun prefetch(): LoadPlan

    /** 已驻留则返回页对象，否则 null（不触发 IO）。 */
    fun page(index: Int): ComicPage?

    fun currentPage(): ComicPage? = page(currentIndex)

    fun loadedIndices(): Set<Int>

    fun residentStats(): ResidentStats
}

/** 页解码钩子：默认只读文件头拿固有尺寸；宿主可换成 BitmapFactory 版本。 */
fun interface PageDecoder {
    fun decode(entry: PageEntry, bytes: ByteArray): ComicPage

    companion object {
        /** 默认解码器：不解码像素，仅解析容器头（纯 JVM 可测）。 */
        val INTRINSIC_SIZE = PageDecoder { entry, bytes ->
            ComicPage(entry.index, entry.name, entry.ext, bytes, ImageHeader.read(bytes))
        }
    }
}
