package com.koodoreader.reader.epubhost

import com.koodoreader.core.importer.EpubChapterLoader
import com.koodoreader.core.importer.EpubResourceLoader
import com.koodoreader.core.importer.EpubSpine
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.TextMeasurer
import java.io.File

/**
 * EPUB → 分页结果 的集成会话（EPUB 原生阅读屏 步骤②，多格式重构后）。
 *
 * 打开时经 [EpubChapterLoader] 按需读取各章 XHTML → [PagedDocumentSession]。
 * [expandRemaining] 在首章快开后继续加载剩余章节并重建分页（Canvas 渐进打开）。
 *
 * 纯 JVM：measurer 由构造方注入——真机传 TextPaint 实现（AndroidTextMeasurer），
 * JVM 测试传 engine/layout 自带的确定性 measurer。
 */
class EpubBookSession private constructor(
    private val loader: EpubChapterLoader,
    private var pagedSession: PagedDocumentSession,
    private val layoutWidth: Float,
    private val layoutHeight: Float,
    private val measurer: TextMeasurer,
    private val layoutOptions: PaginatorOptions,
    private val chapterDescriptors: List<ChapterDesc>,
    private val loadedChapters: MutableList<PagedDocumentSession.Chapter>,
) : ReaderSession {

    private var layoutGeneration: Int = 0

    val spine: EpubSpine get() = loader.epubSpine

    /** Spine 条目总数（含尚未分页的章）。 */
    val totalChapterCount: Int get() = chapterDescriptors.size

    /** 已加载并参与分页的章数。 */
    val loadedChapterCount: Int get() = loadedChapters.size

    val isFullyLoaded: Boolean get() = loadedChapterCount >= chapterDescriptors.size

    /** Compose 用：每次 [expandRemaining] 重建分页后递增。 */
    fun layoutRevision(): Int = layoutGeneration

    override val pageCount: Int get() = pagedSession.pageCount
    override val chapterCount: Int get() = pagedSession.chapterCount

    override fun pageLines(page: Int): List<LayoutLine> = pagedSession.pageLines(page)

    override fun cfiForPage(page: Int): String? = pagedSession.cfiForPage(page)

    override fun pageForCfi(cfi: String): Int? = pagedSession.pageForCfi(cfi)

    override fun pageOfChapter(chapterOrder: Int): Int = pagedSession.pageOfChapter(chapterOrder)

    override fun chapterLabel(chapterOrder: Int): String = pagedSession.chapterLabel(chapterOrder)

    override fun chapterText(chapterOrder: Int): String = pagedSession.chapterText(chapterOrder)

    override fun layoutResult() = pagedSession.layoutResult()

    override fun close() {
        loader.close()
    }

    override fun chapterHref(index: Int): String =
        spine.chapters.getOrNull(index)?.href ?: ""

    override fun readImage(baseHref: String, src: String): ByteArray? {
        if (baseHref.isBlank() || src.isBlank()) return null
        val srcClean = src.trim()
        if (srcClean.startsWith("data:")) {
            val base64 = srcClean.substringAfter("base64=", "").trim()
            return runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull()
        }
        val resolved = EpubResourceLoader.resolve(baseHref, srcClean) ?: return null
        return spine.readResource(resolved)
    }

    /**
     * 加载下一批章节并重建分页。
     *
     * @return `true` 表示仍有章节未加载；`false` 表示已全部加载或无需再加载。
     */
    @Synchronized
    fun expandRemaining(batchSize: Int = Int.MAX_VALUE): Boolean {
        if (isFullyLoaded) return false
        val batch = batchSize.coerceAtLeast(1)
        val remaining = chapterDescriptors.size - loadedChapterCount
        val toLoad = minOf(batch, remaining)
        val end = loadedChapterCount + toLoad
        for (i in loadedChapterCount until end) {
            loadedChapters.add(loadChapter(chapterDescriptors[i]))
        }
        rebuildPaged()
        return !isFullyLoaded
    }

    private fun loadChapter(desc: ChapterDesc): PagedDocumentSession.Chapter {
        val item = loader.load(desc.id)
        return PagedDocumentSession.Chapter(
            index = item.index,
            label = desc.title ?: item.href.substringAfterLast('/'),
            blocks = item.blocks,
        )
    }

    private fun rebuildPaged() {
        pagedSession = PagedDocumentSession.create(
            chapters = loadedChapters.toList(),
            viewportWidthPx = layoutWidth,
            viewportHeightPx = layoutHeight,
            measurer = measurer,
            options = layoutOptions,
        )
        layoutGeneration++
    }

    private data class ChapterDesc(val id: String, val title: String?)

    companion object {

        fun open(
            file: File,
            viewportWidthPx: Float,
            viewportHeightPx: Float,
            measurer: TextMeasurer,
            options: PaginatorOptions = PaginatorOptions(),
            chapterLimit: Int? = null,
        ): EpubBookSession {
            val loader = EpubChapterLoader.open(file)
            return try {
                val descriptors = loader.descriptors().map { ChapterDesc(it.id, it.title) }
                val take = chapterLimit?.coerceIn(1, descriptors.size) ?: descriptors.size
                val chapters = ArrayList<PagedDocumentSession.Chapter>(take)
                for (i in 0 until take) {
                    val item = loader.load(descriptors[i].id)
                    chapters.add(
                        PagedDocumentSession.Chapter(
                            index = item.index,
                            label = descriptors[i].title ?: item.href.substringAfterLast('/'),
                            blocks = item.blocks,
                        ),
                    )
                }
                val paged = PagedDocumentSession.create(
                    chapters = chapters,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    measurer = measurer,
                    options = options,
                )
                EpubBookSession(
                    loader = loader,
                    pagedSession = paged,
                    layoutWidth = viewportWidthPx,
                    layoutHeight = viewportHeightPx,
                    measurer = measurer,
                    layoutOptions = options,
                    chapterDescriptors = descriptors,
                    loadedChapters = chapters.toMutableList(),
                )
            } catch (t: Throwable) {
                loader.close()
                throw t
            }
        }
    }
}
