package com.koodoreader.reader.shell

import com.koodoreader.core.importer.BookRules

/**
 * VIEW/SEND intent 的 mime-type/文件名 → 路由决策（P5-CBZ-5 + P8-F1 共用）。
 *
 * 纯 JVM、无 Android 类型：MainActivity 的 `handleIntent` 用它决定载荷落点。
 * 判定原则是**原生优先、兜底岛仅接残余**：
 *  - CBZ/CBT/CB7 → [Route.NATIVE_COMIC]（engine/image，直接开漫画屏）；
 *  - PDF / EPUB / TXT / MD / MOBI / AZW(AZW3) / HTML(XHTML/XML) / MHTML /
 *    FB2 / DOCX → [Route.NATIVE_PDF]/[Route.NATIVE_SHELL]（导入管线进 Room +
 *    原生壳 READER，由格式分派到对应 ReaderSession）；
 *  - CBR → [Route.ISLAND]（UnRAR 许可 + .so 16KB，ADR-006 §4.2 明确不原生）；
 *  - 无法识别 → [Route.ISLAND]。
 *
 * 判定顺序（与文件管理器的真实行为一致）：
 *  1. 扩展名（`.cbz` 之类）—— 最可靠；
 *  2. mime-type —— 文件管理器常给 `application/octet-stream` 或空；
 *  3. 两者都无法识别 → ISLAND。
 */
object IntentRoutePolicy {

    /** intent 载荷的落点。 */
    enum class Route {
        /** 原生漫画屏（engine/image：CBZ / CBT / CB7）→ [ComicViewerActivity]。 */
        NATIVE_COMIC,

        /**
         * 原生 PDF（engine/pdf + NativePdfScreen，经导入管线 + 原生壳 READER 路由）。
         */
        NATIVE_PDF,

        /**
         * 原生文本/文档屏（EPUB/TXT/MD/MOBI/AZW/AZW3/HTML/XHTML/XML/MHTML/
         * FB2/DOCX）：导入管线进 Room → 原生壳 → 对应 ReaderSession。
         * 与 [NATIVE_PDF] 同一落点（走 NativeShellActivity），分开命名是为了
         * 路由语义可读（PDF 有独立屏）。
         */
        NATIVE_SHELL,

        /** 兜底岛 WebView（CBR，以及一切无法识别的载荷）。 */
        ISLAND,
    }

    /** engine:image 原生可读的漫画容器扩展名（小写，不含点）。 */
    private val COMIC_EXTS = setOf("cbz", "cbt", "cb7")

    /** engine:image 原生可读的漫画容器 mime-type（小写）。 */
    private val NATIVE_COMIC_MIMES = setOf(
        "application/x-cbz",
        "application/x-cbt",
        "application/x-cb7",
        "application/vnd.comicbook+zip", // CBZ 的另一常见注册名
        "application/vnd.comicbook-rar", // 注意：RAR 容器 mime 不代表可原生读 → 见下方拦截
    )

    /** 明确不原生的容器（UnRAR 许可 + .so 16KB 对齐）——即使 mime 看起来像也不走原生。 */
    private val ISLAND_ONLY_EXTS = setOf("cbr")

    /** 原生 PDF 屏已就绪（P3），扩展名 + mime 都认。 */
    private val PDF_EXTS = setOf("pdf")
    private val NATIVE_PDF_MIMES = setOf("application/pdf")

    /**
     * 原生壳可读的文本/文档格式 —— **派生自导入白名单**（[BookRules.BOOK_EXTENSIONS]
     * 去掉漫画、PDF 与 CBR），而不是另抄一份：导入管线收哪些格式，intent 路由就认
     * 哪些格式，两边永不漂移（此前硬编码表里的 `mht`/`markdown` 不在白名单，
     * 外部打开会被导入拒收 → 已由派生消除）。
     */
    private val NATIVE_SHELL_EXTS: Set<String> =
        BookRules.BOOK_EXTENSIONS.toSet() - COMIC_EXTS - PDF_EXTS - ISLAND_ONLY_EXTS

    /** 对应的 mime 表，同样从 [BookRules.MIME_BY_EXT] 派生。 */
    private val NATIVE_SHELL_MIMES: Set<String> =
        NATIVE_SHELL_EXTS.mapNotNull { BookRules.MIME_BY_EXT[it] }.toSet() +
            // 少数真实世界变体（文件管理器/浏览器另存）
            setOf("text/x-markdown", "multipart/related", "application/x-fictionbook")

    fun decide(mimeType: String?, fileName: String?): Route {
        val name = fileName.orEmpty().lowercase()
        val ext = name.substringAfterLast('.', "")

        if (ext in ISLAND_ONLY_EXTS) return Route.ISLAND
        if (ext in COMIC_EXTS) return Route.NATIVE_COMIC
        if (ext in PDF_EXTS) return Route.NATIVE_PDF
        if (ext in NATIVE_SHELL_EXTS) return Route.NATIVE_SHELL

        val mime = mimeType.orEmpty().lowercase().substringBefore(';').trim()
        // RAR 容器的 mime（application/vnd.comicbook-rar / application/x-rar-compressed）
        // 一律兜底岛：容器是 RAR，engine 解不了。
        if (mime.contains("rar")) return Route.ISLAND
        if (mime in NATIVE_COMIC_MIMES) return Route.NATIVE_COMIC
        if (mime in NATIVE_PDF_MIMES) return Route.NATIVE_PDF
        if (mime in NATIVE_SHELL_MIMES) return Route.NATIVE_SHELL

        return Route.ISLAND
    }
}
