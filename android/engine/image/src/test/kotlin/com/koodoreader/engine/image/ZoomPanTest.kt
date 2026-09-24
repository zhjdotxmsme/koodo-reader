package com.koodoreader.engine.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 缩放/平移算子：适屏比例、边界夹取、焦点不漂移、双击开合。 */
class ZoomPanTest {

    private val viewport = Viewport(1000f, 800f)
    private val page = ContentSize(1000f, 1500f) // 竖版单页

    @Test
    fun `fit scale contains the page inside the viewport`() {
        assertEquals(0.5333f, ZoomPan.fitScale(viewport, page), 0.001f) // 800/1500
        assertEquals(1f, ZoomPan.fitScale(viewport, ContentSize(0f, 0f)), "尺寸未知时退化到 1")
    }

    @Test
    fun `scale is clamped to the supported range`() {
        assertEquals(ZoomPanState.MIN_SCALE, ZoomPan.clampScale(0.2f))
        assertEquals(ZoomPanState.MAX_SCALE, ZoomPan.clampScale(99f))
        assertEquals(2f, ZoomPan.clampScale(2f))
    }

    @Test
    fun `pan is clamped so the page never leaves the viewport`() {
        val fit = ZoomPanState.FIT
        // 适屏时内容不超出视口 → 任何拖动都被夹回原点
        assertEquals(fit, ZoomPan.pan(fit, 300f, -200f, viewport, page))

        // 放大 3 倍后可以拖动，但不超过半差
        val zoomed = ZoomPan.scaleTo(fit, 3f, viewport.centerX, viewport.centerY, viewport, page)
        val maxX = ZoomPan.maxOffsetX(viewport, page, 3f)
        val dragged = ZoomPan.pan(zoomed, 10_000f, 10_000f, viewport, page)
        assertEquals(maxX, dragged.offsetX, 0.001f)
        assertEquals(ZoomPan.maxOffsetY(viewport, page, 3f), dragged.offsetY, 0.001f)
    }

    @Test
    fun `pinching keeps the content point under the fingers`() {
        val square = ContentSize(1000f, 1000f)
        val squareViewport = Viewport(1000f, 1000f)
        val result = ZoomPan.pinch(ZoomPanState.FIT, 2f, 750f, 500f, squareViewport, square)

        assertEquals(2f, result.scale, 0.001f)
        // 焦点 (750,500) 相对中心 +250；放大 2 倍后偏移 = -250 才能让该点不动
        assertEquals(-250f, result.offsetX, 0.001f)
        assertEquals(0f, result.offsetY, 0.001f)
    }

    @Test
    fun `pinch beyond the limit stops at max scale without drifting`() {
        val result = ZoomPan.pinch(ZoomPanState.FIT, 100f, 900f, 100f, viewport, page)

        assertEquals(ZoomPanState.MAX_SCALE, result.scale, 0.001f)
        // 焦点在右侧 → 内容被推到左边，偏移正好顶到负边界
        assertEquals(-ZoomPan.maxOffsetX(viewport, page, result.scale), result.offsetX, 0.001f)
    }

    @Test
    fun `double tap toggles between fit and zoomed`() {
        val zoomed = ZoomPan.doubleTap(ZoomPanState.FIT, 900f, 100f, viewport, page)
        assertEquals(ZoomPanState.DOUBLE_TAP_SCALE, zoomed.scale, 0.001f)
        assertTrue(zoomed.isZoomed)

        val back = ZoomPan.doubleTap(zoomed, 900f, 100f, viewport, page)
        assertEquals(ZoomPanState.FIT, back)
        assertFalse(back.isZoomed)
    }

    @Test
    fun `matrix values keep the page centered plus the pan offset`() {
        val fitMatrix = ZoomPanState.FIT.matrix(viewport, page)
        val k = 800f / 1500f

        assertEquals(k, fitMatrix[0], 0.001f, "绘制比例 = 适屏比例 × 缩放")
        assertEquals(k, fitMatrix[4], 0.001f)
        assertEquals(1f, fitMatrix[8], 0.001f)
        // 不变式：内容中心映射到「视口中心 + 偏移」
        assertEquals(viewport.centerX, fitMatrix[2] + page.width * k / 2f, 0.01f)
        assertEquals(viewport.centerY, fitMatrix[5] + page.height * k / 2f, 0.01f)

        val zoomed = ZoomPanState(scale = 2f, offsetX = 50f, offsetY = -30f)
        val zoomedMatrix = zoomed.matrix(viewport, page)
        val zoomedK = 2f * k

        assertEquals(zoomedK, zoomedMatrix[0], 0.001f)
        assertEquals(viewport.centerX + 50f, zoomedMatrix[2] + page.width * zoomedK / 2f, 0.01f)
        assertEquals(viewport.centerY - 30f, zoomedMatrix[5] + page.height * zoomedK / 2f, 0.01f)
    }

    @Test
    fun `settle clamps a wild fling result`() {
        val wild = ZoomPanState(scale = 99f, offsetX = 99_999f, offsetY = -99_999f)

        val settled = ZoomPan.settle(wild, viewport, page)

        assertEquals(ZoomPanState.MAX_SCALE, settled.scale, 0.001f)
        assertEquals(ZoomPan.maxOffsetX(viewport, page, settled.scale), settled.offsetX, 0.001f)
        assertEquals(-ZoomPan.maxOffsetY(viewport, page, settled.scale), settled.offsetY, 0.001f)
    }
}
