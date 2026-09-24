package com.koodoreader.engine.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/**
 * 懒加载窗口的行为契约（本卡验收核心）：
 *  - 加载：**当前页 + 后 3 页**；
 *  - 卸载：**当前页 -4 页及更早**（顺序阅读时保留窗口 = `[current-3, current+3]`，最多 7 页）；
 *  - 跳页/回退时窗口外的旧页一并释放（防无界增长），不改变上面的顺序阅读语义。
 */
class PageLoaderTest {

    // ---- ① 加载窗口 ---------------------------------------------------------

    @Test
    fun `seek loads the current page plus the next three`() {
        val archive = FakeArchive(20)
        DefaultPageLoader(archive).use { loader ->
            val plan = loader.seek(0)

            assertEquals(listOf(0, 1, 2, 3), plan.toLoad, "当前页 + 后 3 页")
            assertTrue(plan.toEvict.isEmpty())
            assertEquals(setOf(0, 1, 2, 3), loader.loadedIndices())
            assertEquals(listOf(0, 1, 2, 3), archive.readIndices, "只读这 4 页，不多读")
            assertEquals(0, loader.page(0)?.index)
            assertEquals("000.jpg", loader.page(0)?.entryName)
            assertEquals(20, loader.pageCount)
            assertEquals(ResidentStats(4, 4L * "PAGE-0".length), loader.residentStats())
        }
    }

    @Test
    fun `forward paging keeps a seven page retention window and unloads four pages back`() {
        val archive = FakeArchive(20)
        DefaultPageLoader(archive).use { loader ->
            for (page in 0..4) loader.seek(page)

            assertEquals(4, loader.currentIndex)
            assertEquals(setOf(1, 2, 3, 4, 5, 6, 7), loader.loadedIndices(), "[current-3, current+3]")
            assertNull(loader.page(0), "current-4 必须已卸载")
            assertNotNull(loader.page(1), "current-3 保留，往回翻一页不闪")
        }
    }

    @Test
    fun `the load window clamps at the end of the archive`() {
        val archive = FakeArchive(3)
        DefaultPageLoader(archive).use { loader ->
            assertEquals(listOf(0, 1, 2), loader.seek(0).toLoad, "3 页的卷一次装完，不会越界")
            assertTrue(loader.seek(2).isEmpty, "末尾没有新页可加载")

            val again = loader.seek(99)
            assertTrue(again.isEmpty)
            assertEquals(2, loader.currentIndex, "越界被夹到末页")
            assertEquals(3, archive.reads, "整卷只读了 3 页")
        }
    }

    @Test
    fun `out of range seeks are clamped into the archive`() {
        val archive = FakeArchive(10)
        DefaultPageLoader(archive).use { loader ->
            assertEquals(listOf(0, 1, 2, 3), loader.seek(-5).toLoad)
            assertEquals(0, loader.currentIndex)

            loader.seek(1000)
            assertEquals(9, loader.currentIndex)
            assertEquals(setOf(9), loader.loadedIndices(), "跳到末页后按新窗口重算（前面 0..3 被释放）")
        }
    }

    // ---- ② 卸载 / 跳页 ------------------------------------------------------

    @Test
    fun `a backward jump drops the pages far ahead and loads the new window`() {
        val archive = FakeArchive(50)
        DefaultPageLoader(archive).use { loader ->
            loader.seek(30)
            assertEquals(setOf(30, 31, 32, 33), loader.loadedIndices())

            val plan = loader.seek(2)

            assertEquals(listOf(2, 3, 4, 5), plan.toLoad)
            assertEquals(listOf(30, 31, 32, 33), plan.toEvict, "跳页后窗口外的旧页一并释放")
            assertEquals(setOf(2, 3, 4, 5), loader.loadedIndices())
            assertNull(loader.page(30))
        }
    }

    @Test
    fun `stepping back one page reloads the page that was evicted four pages ago`() {
        val archive = FakeArchive(20)
        DefaultPageLoader(archive).use { loader ->
            for (page in 0..4) loader.seek(page)
            assertNull(loader.page(0), "已卸载")

            // current=1：第 0 页不在加载窗口内（loadTargets 只向前），保持 null，
            // 同时 current+3 之外的 5..7 被释放（窗口仍是有界的）
            val back = loader.seek(1)
            assertTrue(back.toLoad.isEmpty())
            assertEquals(listOf(5, 6, 7), back.toEvict)
            assertNull(loader.page(0))

            // 真正退回第 0 页才重新读它
            assertEquals(listOf(0), loader.seek(0).toLoad)
            assertEquals(0, loader.page(0)?.index)
        }
    }

    // ---- ③ 内存预算 ---------------------------------------------------------

    @Test
    fun `resident budget evicts only pages behind the current page`() {
        val fiftyBytes = { _: Int -> ByteArray(50) } // 每页 50B，上限 120B → 4 页窗口天然超预算
        val archive = FakeArchive(20, pageBytes = fiftyBytes)
        DefaultPageLoader(archive, maxResidentBytes = 120).use { loader ->
            val first = loader.seek(0)
            assertTrue(first.toEvict.isEmpty(), "当前页与预取页永不被预算淘汰")

            val second = loader.seek(1)
            assertEquals(listOf(0), second.toEvict, "淘汰当前页之前最老的页")
            assertNull(loader.page(0))
            assertEquals(setOf(1, 2, 3, 4), loader.loadedIndices())
        }
    }

    // ---- ④ 失败 / 生命周期 --------------------------------------------------

    @Test
    fun `a failing page does not break the loader`() {
        val archive = FakeArchive(20, failOn = setOf(2))
        DefaultPageLoader(archive).use { loader ->
            assertEquals(listOf(0, 1, 2, 3), loader.seek(0).toLoad, "失败的页仍然被尝试")

            assertEquals(setOf(0, 1, 3), loader.loadedIndices(), "失败页不进驻留表")
            assertNull(loader.page(2))
            assertNotNull(loader.lastError)

            assertEquals(listOf(2, 4), loader.seek(1).toLoad, "下一次翻页会重试失败页，其余照常")
        }
    }

    @Test
    fun `page never triggers io for a non resident page`() {
        val archive = FakeArchive(20)
        DefaultPageLoader(archive).use { loader ->
            loader.seek(0)
            val readsAfterSeek = archive.reads

            assertNull(loader.page(19))
            assertNull(loader.page(-1))
            assertEquals(readsAfterSeek, archive.reads, "page() 只查驻留表")
        }
    }

    @Test
    fun `prefetch is idempotent and close releases the archive`() {
        val archive = FakeArchive(5)
        val loader = DefaultPageLoader(archive)
        loader.seek(0)
        val readsAfterSeek = archive.reads

        assertTrue(loader.prefetch().isEmpty, "窗口已满时重复预取是空操作")
        assertEquals(readsAfterSeek, archive.reads)

        loader.close()
        assertTrue(archive.closed, "close 一并关闭归档句柄")
        assertNull(loader.page(0))
        assertEquals(ResidentStats(0, 0), loader.residentStats())
        assertTrue(loader.seek(0).isEmpty, "关闭后不再加载")
    }

    @Test
    fun `empty archives are inert`() {
        val archive = FakeArchive(0)
        DefaultPageLoader(archive).use { loader ->
            assertTrue(loader.seek(5).isEmpty)
            assertEquals(0, loader.currentIndex)
            assertNull(loader.page(0))
            assertEquals(ResidentStats(0, 0), loader.residentStats())
        }
    }

    // ---- ⑤ 异步路径（宿主 UI 线程用） ---------------------------------------

    @Test
    fun `seekAsync without an executor is synchronous`() {
        val archive = FakeArchive(20)
        DefaultPageLoader(archive).use { loader ->
            assertEquals(listOf(0, 1, 2, 3), loader.seekAsync(0).toLoad)
            assertEquals(setOf(0, 1, 2, 3), loader.loadedIndices())
        }
    }

    @Test
    fun `seekAsync with a direct executor applies the plan on that executor`() {
        val archive = FakeArchive(20)
        val executor = Executor { it.run() } // 直接执行：确定性，不需要等待
        DefaultPageLoader(archive, executor = executor).use { loader ->
            val predicted = loader.seekAsync(5)

            assertEquals(listOf(5, 6, 7, 8), predicted.toLoad)
            assertEquals(setOf(5, 6, 7, 8), loader.loadedIndices())
            assertEquals(5, loader.currentIndex)
        }
    }

    // ---- ⑥ 策略表本身（文档化契约，防止被顺手改坏） -------------------------

    @Test
    fun `load window policy is current page plus three, evict at minus four`() {
        assertEquals(3, LoadWindow.AHEAD)
        assertEquals(3, LoadWindow.KEEP_BEHIND)
        assertEquals(4, LoadWindow.EVICT_BEHIND)

        val loaded = (0..12).toSet()
        val plan = LoadWindow.plan(current = 10, pageCount = 30, loaded = loaded)

        assertEquals(listOf(13), plan.toLoad, "只补 current+3 里缺的那页")
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), plan.toEvict, "current-4 及更早全部卸载")
        assertEquals(7..13, LoadWindow.retention(10, 30))
        assertEquals(listOf(10, 11, 12, 13), LoadWindow.loadTargets(10, 30))
        assertEquals(listOf(27, 28, 29), LoadWindow.loadTargets(27, 30), "卷尾截断")
        assertTrue(LoadWindow.loadTargets(0, 0).isEmpty())
    }
}
