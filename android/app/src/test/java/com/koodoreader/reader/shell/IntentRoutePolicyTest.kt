package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P5-CBZ-5 + P8-F1 路由表测试（卡内验收 [3]：≥8 用例；本套件 14 项覆盖
 * 扩展名 / mime / octet-stream / CBR 拦截 / 未知回退 / 大小写与参数容差）。
 */
class IntentRoutePolicyTest {

    private fun decide(mime: String?, name: String) = IntentRoutePolicy.decide(mime, name)

    // ---------------------------------------------------- 原生漫画（扩展名）

    @Test
    fun `cbz by extension routes native`() =
        assertEquals(IntentRoutePolicy.Route.NATIVE_COMIC, decide(null, "Book.cbz"))

    @Test
    fun `cbt by extension routes native`() =
        assertEquals(IntentRoutePolicy.Route.NATIVE_COMIC, decide(null, "book.cbt"))

    @Test
    fun `cb7 by extension routes native`() =
        assertEquals(IntentRoutePolicy.Route.NATIVE_COMIC, decide(null, "book.CB7"))

    @Test
    fun `extension wins over an unusable mime`() {
        // 文件管理器常见：mime 给 octet-stream，扩展名才是真身
        assertEquals(
            IntentRoutePolicy.Route.NATIVE_COMIC,
            decide("application/octet-stream", "manga.cbz"),
        )
        assertEquals(
            IntentRoutePolicy.Route.NATIVE_COMIC,
            decide(null, "manga.cbz"),
        )
    }

    @Test
    fun `extension is case-insensitive`() {
        assertEquals(
            IntentRoutePolicy.Route.NATIVE_COMIC,
            decide("application/octet-stream", "MANGA.CBZ"),
        )
    }

    // ---------------------------------------------------- 原生漫画（mime）

    @Test
    fun `cbz mime routes native even with a meaningless name`() {
        assertEquals(
            IntentRoutePolicy.Route.NATIVE_COMIC,
            decide("application/x-cbz", "download"),
        )
    }

    @Test
    fun `comicbook+zip mime routes native`() {
        assertEquals(
            IntentRoutePolicy.Route.NATIVE_COMIC,
            decide("application/vnd.comicbook+zip", "file"),
        )
    }

    @Test
    fun `mime parameters are stripped before matching`() {
        assertEquals(
            IntentRoutePolicy.Route.NATIVE_COMIC,
            decide("application/x-cbz; charset=binary", "file"),
        )
    }

    // ------------------------------------------------ CBR / RAR 拦截

    @Test
    fun `cbr extension routes island even with a comic mime`() {
        assertEquals(
            IntentRoutePolicy.Route.ISLAND,
            decide("application/vnd.comicbook-rar", "book.cbr"),
        )
    }

    @Test
    fun `rar mime routes island`() {
        assertEquals(
            IntentRoutePolicy.Route.ISLAND,
            decide("application/x-rar-compressed", "book"),
        )
    }

    // ------------------------------------------------ 原生 PDF（P8-F1）

    @Test
    fun `pdf by extension routes native pdf`() {
        assertEquals(IntentRoutePolicy.Route.NATIVE_PDF, decide(null, "doc.pdf"))
    }

    @Test
    fun `pdf mime routes native pdf`() {
        assertEquals(
            IntentRoutePolicy.Route.NATIVE_PDF,
            decide("application/pdf", "download"),
        )
    }

    // ------------------------------------------------ 原生壳（文本/文档全格式）

    @Test
    fun `epub and other text formats route to the native shell`() {
        for (name in listOf("a.epub", "b.txt", "c.md", "d.mobi", "e.azw3", "f.fb2", "g.docx")) {
            assertEquals(IntentRoutePolicy.Route.NATIVE_SHELL, decide(null, name))
        }
    }

    @Test
    fun `web formats route to the native shell`() {
        // 白名单内（与桌面 supportedFormats 同集）：html/htm/xhtml/xml/mhtml。
        // 注意 `.mht` **不在**导入白名单，导入管线会拒收，故路由表也不认领它
        // （避免"导得进来但打不开"的反向不一致）。
        for (name in listOf("a.html", "b.htm", "c.xhtml", "d.xml", "e.mhtml")) {
            assertEquals(IntentRoutePolicy.Route.NATIVE_SHELL, decide(null, name))
        }
        assertEquals(IntentRoutePolicy.Route.ISLAND, decide(null, "f.mht"))
    }

    @Test
    fun `text format mimes route to the native shell when the name is useless`() {
        val mimes = listOf(
            "application/epub+zip",
            "text/plain",
            "text/markdown",
            "application/x-mobipocket-ebook",
            "text/html",
            "application/xhtml+xml",
            "multipart/related",
            "application/x-fictionbook+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
        for (mime in mimes) {
            assertEquals(IntentRoutePolicy.Route.NATIVE_SHELL, decide(mime, "download"))
        }
    }

    // ------------------------------------------------ 兜底岛回退

    @Test
    fun `unknown mime and extension routes island as fallback`() {
        assertEquals(IntentRoutePolicy.Route.ISLAND, decide(null, "file.xyz"))
        assertEquals(IntentRoutePolicy.Route.ISLAND, decide("application/x-unknown", "blob"))
    }

    @Test
    fun `null mime and no extension routes island`() {
        assertEquals(IntentRoutePolicy.Route.ISLAND, decide(null, "noext"))
    }
}
