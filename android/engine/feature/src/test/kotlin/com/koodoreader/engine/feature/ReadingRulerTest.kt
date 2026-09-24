package com.koodoreader.engine.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadingRulerTest {

    private val viewport = 600f

    @Test
    fun `height is line height times line height in px`() {
        val config = ReadingRulerConfig()
        assertEquals(72f, config.heightPx, 1e-4f)
        assertEquals(0.5f, config.safeOpacity, 1e-4f)
        assertEquals(
            480f,
            ReadingRulerConfig(lineHeight = 20f).heightPx,
            1e-4f,
        )
        // lineHeight 0 still leaves a touchable band
        assertEquals(
            ReadingRulerConfig.MIN_HEIGHT_PX,
            ReadingRulerConfig(lineHeight = 0f).heightPx,
            1e-4f,
        )
    }

    @Test
    fun `ruler is centred in the viewport when not following the finger`() {
        val area = ReadingRuler.resolve(fingerY = 0f, viewportHeight = viewport)
        assertEquals(264f, area.top, 1e-4f)
        assertEquals(336f, area.bottom, 1e-4f)
        assertEquals(72f, area.height, 1e-4f)
        assertEquals(300f, area.centerY, 1e-4f)
        // the finger position is ignored
        assertEquals(area, ReadingRuler.resolve(fingerY = 10f, viewportHeight = viewport))
        assertEquals(area, ReadingRuler.resolve(fingerY = 590f, viewportHeight = viewport))
    }

    @Test
    fun `offset shifts the band`() {
        val area = ReadingRuler.resolve(
            fingerY = 0f,
            viewportHeight = viewport,
            config = ReadingRulerConfig(offset = 60f),
        )
        assertEquals(324f, area.top, 1e-4f)
        assertEquals(360f, area.centerY, 1e-4f)
    }

    @Test
    fun `follow finger mode tracks the pointer and clamps at both edges`() {
        val config = ReadingRulerConfig(followFinger = true)

        val top = ReadingRuler.resolve(fingerY = 0f, viewportHeight = viewport, config = config)
        assertEquals(0f, top.top, 1e-4f)
        assertEquals(72f, top.bottom, 1e-4f)

        val middle = ReadingRuler.resolve(fingerY = 300f, viewportHeight = viewport, config = config)
        assertEquals(264f, middle.top, 1e-4f)

        val bottom = ReadingRuler.resolve(fingerY = 600f, viewportHeight = viewport, config = config)
        assertEquals(528f, bottom.top, 1e-4f)
        assertEquals(600f, bottom.bottom, 1e-4f)
    }

    @Test
    fun `ruler never leaves the viewport`() {
        val config = ReadingRulerConfig(followFinger = true, offset = 10_000f)
        val area = ReadingRuler.resolve(fingerY = 10f, viewportHeight = viewport, config = config)
        assertEquals(528f, area.top, 1e-4f)
        assertEquals(600f, area.bottom, 1e-4f)
        assertTrue(area.top >= 0f && area.bottom <= viewport)

        val negative = ReadingRuler.resolve(
            fingerY = 10f,
            viewportHeight = viewport,
            config = ReadingRulerConfig(followFinger = true, offset = -10_000f),
        )
        assertEquals(0f, negative.top, 1e-4f)
    }

    @Test
    fun `height is clamped to the viewport`() {
        val area = ReadingRuler.resolve(
            fingerY = 0f,
            viewportHeight = viewport,
            config = ReadingRulerConfig(lineHeight = 100f),
        )
        assertEquals(600f, area.height, 1e-4f)
        assertEquals(0f, area.top, 1e-4f)
    }

    @Test
    fun `hit testing includes the band edges`() {
        val area = ReadingRuler.resolve(fingerY = 0f, viewportHeight = viewport)
        assertTrue(area.contains(264f))
        assertTrue(area.contains(300f))
        assertTrue(area.contains(336f))
        assertFalse(area.contains(263.9f))
        assertFalse(area.contains(336.1f))
        assertTrue(ReadingRuler.hits(area, 300f))
        assertFalse(RulerArea.EMPTY.contains(0f))
    }

    @Test
    fun `progress and line geometry`() {
        val area = ReadingRuler.resolve(fingerY = 0f, viewportHeight = viewport)
        assertEquals(0.5f, ReadingRuler.progressOf(area, viewport), 1e-4f)
        assertEquals(11, ReadingRuler.lineIndexOf(area, 24f))
        assertEquals(3, ReadingRuler.lineSpanOf(area, 24f))

        assertEquals(0f, ReadingRuler.progressOf(area, 0f), 1e-4f)
        assertEquals(0, ReadingRuler.lineIndexOf(area, 0f))
        assertEquals(0, ReadingRuler.lineSpanOf(area, 0f))
    }

    @Test
    fun `degenerate viewport yields an empty band`() {
        val area = ReadingRuler.resolve(fingerY = 100f, viewportHeight = 0f)
        assertEquals(RulerArea.EMPTY, area)
        assertEquals(0f, area.height, 0f)
        assertFalse(area.contains(0f))
        assertEquals(0f, ReadingRuler.progressOf(area, 0f), 0f)
    }

    @Test
    fun `opacity is clamped into the desktop range`() {
        assertEquals(1f, ReadingRulerConfig(opacity = 2f).safeOpacity, 1e-4f)
        assertEquals(0f, ReadingRulerConfig(opacity = -1f).safeOpacity, 1e-4f)
        assertEquals(0.3f, ReadingRulerConfig(opacity = 0.3f).safeOpacity, 1e-4f)
    }
}
