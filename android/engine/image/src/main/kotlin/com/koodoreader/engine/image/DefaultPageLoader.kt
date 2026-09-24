package com.koodoreader.engine.image

import java.util.concurrent.Executor

/**
 * [PageLoader] 的默认实现：一个 LRU-ish 的驻留表 + [LoadWindow] 策略。
 *
 * 行为要点：
 *  - **同步 [seek]**（默认路径，可测）：调用线程上完成 IO，返回真实生效的计划；
 *  - **内存预算** [maxResidentBytes]：加完新页后若超预算，从**当前页之前**最老的
 *    页开始淘汰（永不淘汰当前页），并把淘汰结果并回 [LoadPlan.toEvict]；
 *  - **失败不崩**：单页读取异常 → 记入 [lastError]，该页不进驻留表（宿主显示占位）。
 */
class DefaultPageLoader(
    private val extractor: ArchiveExtractor,
    private val decoder: PageDecoder = PageDecoder.INTRINSIC_SIZE,
    private val executor: Executor? = null,
    private val maxResidentBytes: Long = DEFAULT_MAX_RESIDENT_BYTES,
) : PageLoader {

    private val lock = Any()
    private val resident = LinkedHashMap<Int, ComicPage>()
    private var current = 0
    private var residentBytes = 0L
    private var closed = false
    private var lastErrorValue: Throwable? = null

    /** 最近一次单页读取失败（宿主可据此提示「本页损坏」）。 */
    val lastError: Throwable? get() = synchronized(lock) { lastErrorValue }

    override val pageCount: Int get() = extractor.pageCount

    override val currentIndex: Int get() = synchronized(lock) { current }

    override fun seek(index: Int): LoadPlan {
        if (closed) return LoadPlan.EMPTY
        synchronized(lock) {
            if (pageCount <= 0) {
                current = 0
                return LoadPlan.EMPTY
            }
            current = index.coerceIn(0, pageCount - 1)
            return applyPlan(LoadWindow.plan(current, pageCount, resident.keys.toSet()))
        }
    }

    override fun seekAsync(index: Int): LoadPlan {
        if (closed) return LoadPlan.EMPTY
        val ex = executor ?: return seek(index)
        val target: Int
        val predicted: LoadPlan
        synchronized(lock) {
            if (pageCount <= 0) {
                current = 0
                return LoadPlan.EMPTY
            }
            current = index.coerceIn(0, pageCount - 1)
            target = current
            predicted = LoadWindow.plan(target, pageCount, resident.keys.toSet())
        }
        ex.execute {
            synchronized(lock) {
                // 期间又翻页了就丢弃这次结果（下一次 seek 会重新算窗口）
                if (!closed && current == target) {
                    applyPlan(LoadWindow.plan(target, pageCount, resident.keys.toSet()))
                }
            }
        }
        return predicted
    }

    override fun prefetch(): LoadPlan = seek(currentIndex)

    override fun page(index: Int): ComicPage? = synchronized(lock) { resident[index] }

    override fun loadedIndices(): Set<Int> = synchronized(lock) { resident.keys.toSet() }

    override fun residentStats(): ResidentStats =
        synchronized(lock) { ResidentStats(resident.size, residentBytes) }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            resident.clear()
            residentBytes = 0L
        }
        extractor.close()
    }

    // ---- 内部 ---------------------------------------------------------------

    /** 在持锁状态下执行计划，返回「真实生效」的计划（含预算淘汰）。 */
    private fun applyPlan(plan: LoadPlan): LoadPlan {
        val evicted = ArrayList<Int>(plan.toEvict)
        for (index in plan.toEvict) removeLocked(index)
        for (index in plan.toLoad) {
            loadLocked(index)?.let { page ->
                resident[index] = page
                residentBytes += page.bytes.size
            }
        }
        evicted += enforceBudgetLocked()
        evicted.sort()
        return LoadPlan(plan.toLoad, evicted)
    }

    private fun loadLocked(index: Int): ComicPage? {
        if (index !in 0 until pageCount) return null
        val entry = extractor.entry(index)
        return runCatching {
            decoder.decode(entry, extractor.readPage(index))
        }.onFailure { lastErrorValue = it }.getOrNull()
    }

    private fun removeLocked(index: Int) {
        resident.remove(index)?.let { residentBytes -= it.bytes.size }
    }

    /**
     * 超预算时淘汰「当前页之前」最老的驻留页（FIFO），绝不淘汰当前页。
     * 返回额外淘汰的页号。
     */
    private fun enforceBudgetLocked(): List<Int> {
        if (maxResidentBytes <= 0 || residentBytes <= maxResidentBytes) return emptyList()
        val extra = ArrayList<Int>()
        val behind = resident.keys.filter { it < current }.sorted()
        for (index in behind) {
            if (residentBytes <= maxResidentBytes) break
            removeLocked(index)
            extra += index
        }
        return extra
    }

    companion object {
        /** 默认常驻上限：兼顾 2K 彩页（~1.5 MB/页）与低端机堆。 */
        const val DEFAULT_MAX_RESIDENT_BYTES: Long = 64L * 1024 * 1024
    }
}
