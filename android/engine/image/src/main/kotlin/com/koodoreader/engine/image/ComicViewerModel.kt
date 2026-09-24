package com.koodoreader.engine.image

/** 一屏的状态快照（Compose 侧渲染所需的一切，无 Android 类型）。 */
data class ViewerSnapshot(
    val spread: Spread,
    /** 与 [Spread.pages] 一一对应；尚未驻留/读取失败为 null（画占位）。 */
    val pages: List<ComicPage?>,
    val atFirst: Boolean,
    val atLast: Boolean,
    /** 0..1，口径与桌面 `book.progress` 一致（当前页 / 总页数）。 */
    val progress: Float,
    val loadPlan: LoadPlan,
    val resident: ResidentStats,
) {
    val allLoaded: Boolean get() = pages.isNotEmpty() && pages.all { it != null }
}

/**
 * 漫画阅读器的宿主模型：把 [PageLoader]（IO/窗口）与 [SpreadPolicy]（排版）
 * 粘成 UI 可直接渲染的快照。
 *
 * 职责边界：
 *  - 这里**不碰 Android/Compose**，宿主（:app）只做「快照 → UI + 手势回调」；
 *  - 翻页只改落点，加载策略永远由 [PageLoader]/[LoadWindow] 决定，
 *    避免 UI 层各写一套窗口逻辑（桌面 comic-book.js 的教训）。
 */
class ComicViewerModel(
    private val loader: PageLoader,
    var config: SpreadConfig = SpreadConfig(),
) {

    val pageCount: Int get() = loader.pageCount

    val currentIndex: Int get() = loader.currentIndex

    private var lastPlan: LoadPlan = LoadPlan.EMPTY

    /** 打开并落到 [index]（越界夹取）。 */
    fun open(index: Int = 0): ViewerSnapshot {
        lastPlan = loader.seek(index)
        return snapshot()
    }

    /**
     * 下一页（跨页感知：双页模式下步进 2）。
     * 已在末页返回 null —— 宿主据此决定「退出阅读/无操作」，不在这里做 UI 决策。
     */
    fun next(): ViewerSnapshot? {
        if (!canGoNext()) return null
        lastPlan = loader.seek(SpreadPolicy.next(currentIndex, pageCount, config))
        return snapshot()
    }

    /** 上一页（跨页感知）。已在首页返回 null。 */
    fun previous(): ViewerSnapshot? {
        if (!canGoPrevious()) return null
        lastPlan = loader.seek(SpreadPolicy.previous(currentIndex, pageCount, config))
        return snapshot()
    }

    fun canGoNext(): Boolean = pageCount > 0 && !SpreadPolicy.isLast(currentIndex, pageCount, config)

    fun canGoPrevious(): Boolean = pageCount > 0 && !SpreadPolicy.isFirst(currentIndex, pageCount, config)

    /** 当前跨页的原始尺寸（双页模式用于决定「两张并排」还是「退化为单页」）。 */
    fun currentSpread(): Spread = SpreadPolicy.spreadAt(currentIndex, pageCount, config)

    /**
     * 窗口已经覆盖「当前页 + 后 3 页」，而下一页跨页最多 2 页，
     * 因此本调用只是重放一次窗口（失败重试/内存淘汰后补窗口用）。
     */
    fun prefetch(): LoadPlan {
        lastPlan = loader.prefetch()
        return lastPlan
    }

    fun snapshot(): ViewerSnapshot {
        val spread = SpreadPolicy.spreadAt(currentIndex, pageCount, config)
        val pages = spread.pages.map { loader.page(it) }
        return ViewerSnapshot(
            spread = spread,
            pages = pages,
            atFirst = SpreadPolicy.isFirst(currentIndex, pageCount, config),
            atLast = SpreadPolicy.isLast(currentIndex, pageCount, config),
            progress = progress(),
            loadPlan = lastPlan,
            resident = loader.residentStats(),
        )
    }

    /** 0..1 进度（空卷为 0）。 */
    fun progress(): Float {
        if (pageCount <= 0) return 0f
        return (currentIndex + 1).toFloat() / pageCount.toFloat()
    }

    /** 宿主退出时释放：委托给 loader（连带关闭归档）。 */
    fun close() = loader.close()
}
