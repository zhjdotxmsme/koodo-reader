package com.koodoreader.reader.epubhost

import com.koodoreader.core.importer.BookRules
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.TextMeasurer
import java.io.File

/**
 * 格式 → [ReaderSession] 的**单一分派点**（纯 JVM，可测）。
 *
 * 原来这段 `when (format)` 内联在 [NativeEpubScreen] 的 Composable 里，既不能
 * 单测也无法与导入白名单对照。抽出来之后：
 *  - 屏幕只调用 [open]，格式覆盖由本对象负责；
 *  - [ReaderSessionFactoryTest] 对**每一个受支持格式**（[BookRules.BOOK_EXTENSIONS]
 *    除去漫画与 CBR）用最小合法文件跑通「打开 → 分页」，这就是"APK 打开能正确
 *    读取"在逻辑层的证明；
 *  - 漫画（CBZ/CBT/CB7）不经这里：它们由 [com.koodoreader.reader.imagehost.ComicViewerActivity]
 *    直接展示（engine/image），CBR 由兜底岛承担（ADR-006 §4.2）。
 *
 * 判定用**小写扩展名**（`BookRules.formatFromFile` 产出的是大写，屏幕统一转小写）。
 */
object ReaderSessionFactory {

    /** 漫画容器：由 ComicViewerActivity 承接（engine/image）。 */
    val COMIC_EXTENSIONS = setOf("cbz", "cbt", "cb7")

    /** 明确不原生、保留兜底岛的容器（ADR-006 §4.2）。 */
    val ISLAND_ONLY_EXTENSIONS = setOf("cbr")

    /** PDF 有独立屏（NativePdfScreen / pdf.js），不经本工厂。 */
    val PDF_EXTENSIONS = setOf("pdf")

    /** 该格式字符串（大小写不敏感）是否由本工厂处理。 */
    fun supports(format: String?): Boolean = kindOf(format) != null

    /** 本工厂负责的格式（小写扩展名），派生自导入白名单——单一事实源。 */
    val formats: Set<String> =
        BookRules.BOOK_EXTENSIONS.toSet() - COMIC_EXTENSIONS - ISLAND_ONLY_EXTENSIONS - PDF_EXTENSIONS

    /**
     * 打开 [file] 并分页。未知格式返回 null；文件认领后解析失败则抛出
     *（[NativeEpubScreen] 用 `runCatching` 收成 `openError`）。原先在这里
     * `getOrNull()` 会把原因吞掉，打包 APK 上只剩空白「无法分页」。
     */
    fun open(
        format: String?,
        file: File,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        measurer: TextMeasurer,
        options: PaginatorOptions = PaginatorOptions(),
    ): ReaderSession? = when (kindOf(format)) {
        // Let parse/paginate failures throw. NativeEpubScreen already wraps
        // this in runCatching and surfaces `openError` — swallowing here made
        // packaged-APK open failures look like a blank "could not paginate"
        // with no cause.
        Kind.EPUB -> EpubBookSession.open(file, viewportWidthPx, viewportHeightPx, measurer, options)
        Kind.TEXT -> TextBookSession.open(file, viewportWidthPx, viewportHeightPx, measurer, options)
        Kind.MOBI -> MobiBookSession.open(file, viewportWidthPx, viewportHeightPx, measurer, options)
        Kind.WEB -> WebBookSession.open(file, viewportWidthPx, viewportHeightPx, measurer, options)
        Kind.FB2 -> Fb2BookSession.open(file, viewportWidthPx, viewportHeightPx, measurer, options)
        Kind.DOCX -> DocxBookSession.open(file, viewportWidthPx, viewportHeightPx, measurer, options)
        null -> null
    }

    /** 内部归类（每种格式恰好一个实现，见 [formats]）。 */
    private enum class Kind { EPUB, TEXT, MOBI, WEB, FB2, DOCX }

    private fun kindOf(format: String?): Kind? {
        val ext = format?.trim()?.lowercase() ?: return null
        if (ext !in formats) return null
        return when (ext) {
            "epub" -> Kind.EPUB
            "txt", "md" -> Kind.TEXT
            "mobi", "azw", "azw3" -> Kind.MOBI
            "html", "htm", "xhtml", "xml", "mhtml" -> Kind.WEB
            "fb2" -> Kind.FB2
            "docx" -> Kind.DOCX
            else -> null
        }
    }
}
