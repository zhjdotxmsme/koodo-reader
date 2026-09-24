package com.koodoreader.engine.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 宿主模型：跨页翻页 + 预取覆盖 + 进度 + 生命周期。 */
class ComicViewerModelTest {

    private fun model(pageCount: Int = 12, config: SpreadConfig = SpreadConfig()): Pair<ComicViewerModel, FakeArchive> {
        val archive = FakeArchive(pageCount, pageBytes = { Pics.jpeg(1000, 1500) })
        return ComicViewerModel(DefaultPageLoader(archive), config) to archive
    }

    @Test
    fun `open loads the first spread and reports progress`() {
        val (viewer, _) = model()

        val snapshot = viewer.open(0)

        assertEquals(listOf(0), snapshot.spread.pages, "封面单独一页")
        assertTrue(snapshot.allLoaded)
        assertEquals(4, snapshot.resident.pages, "当前页 + 后 3 页已驻留")
        assertEquals(1f / 12f, snapshot.progress, 0.0001f)
        assertTrue(snapshot.atFirst)
        assertFalse(snapshot.atLast)
        assertEquals(listOf(0, 1, 2, 3), snapshot.loadPlan.toLoad)
    }

    @Test
    fun `next advances by a spread and the following spread is already loaded`() {
        val (viewer, _) = model()

        viewer.open(0)
        val first = viewer.next()

        assertNotNull(first)
        assertEquals(listOf(1, 2), first!!.spread.pages)
        assertTrue(first.allLoaded, "1-2 已在窗口内（当前页 + 后 3 页 = 1..4）")

        val second = viewer.next()
        assertNotNull(second)
        assertEquals(listOf(3, 4), second!!.spread.pages)
        assertTrue(second.allLoaded, "预取覆盖了下一页跨页")
        assertEquals(7, second.resident.pages, "驻留窗口上限仍是 7 页")
    }

    @Test
    fun `previous walks back spread by spread`() {
        val (viewer, _) = model()

        viewer.open(3)
        assertEquals(listOf(3, 4), viewer.currentSpread().pages)

        val back = viewer.previous()
        assertNotNull(back)
        assertEquals(listOf(1, 2), back!!.spread.pages)

        val firstSpread = viewer.previous()
        assertNotNull(firstSpread)
        assertEquals(listOf(0), firstSpread!!.spread.pages)
        assertTrue(firstSpread.atFirst)
        assertNull(viewer.previous(), "已在首页：交给宿主决定是否退出阅读")
    }

    @Test
    fun `next returns null at the last spread`() {
        val (viewer, _) = model(pageCount = 3)

        assertEquals(listOf(0), viewer.open(0).spread.pages)
        assertEquals(listOf(1, 2), viewer.next()!!.spread.pages)
        assertTrue(viewer.snapshot().atLast)
        assertNull(viewer.next())
    }

    @Test
    fun `single page mode steps one page at a time`() {
        val (viewer, _) = model(config = SpreadConfig(doublePage = false))

        viewer.open(0)
        assertEquals(listOf(1), viewer.next()!!.spread.pages)
        assertEquals(listOf(2), viewer.next()!!.spread.pages)
        assertEquals(listOf(1), viewer.previous()!!.spread.pages)
    }

    @Test
    fun `progress tracks the page not the spread`() {
        val (viewer, _) = model(pageCount = 10, config = SpreadConfig(doublePage = false))

        viewer.open(4)

        assertEquals(5f / 10f, viewer.progress(), 0.0001f)
    }

    @Test
    fun `close releases the archive`() {
        val (viewer, archive) = model(pageCount = 4)
        viewer.open(0)

        viewer.close()

        assertTrue(archive.closed)
        assertEquals(0, viewer.snapshot().resident.pages)
    }

    @Test
    fun `empty archives produce an empty snapshot`() {
        val (viewer, _) = model(pageCount = 0)

        val snapshot = viewer.open(0)

        assertTrue(snapshot.spread.pages.isEmpty())
        assertTrue(snapshot.pages.isEmpty())
        assertEquals(0f, snapshot.progress)
        assertNull(viewer.next())
        assertNull(viewer.previous())
    }
}
