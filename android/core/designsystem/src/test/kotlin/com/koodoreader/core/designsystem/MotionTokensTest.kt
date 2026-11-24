package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MotionTokensTest {

    @Test
    fun `duration scale stays ascending`() {
        assertTrue(
            MotionTokens.ALL.zipWithNext().all { (a, b) -> a < b },
            MotionTokens.ALL.toString(),
        )
    }

    @Test
    fun `press scale matches UX-Pro-Max 0 dot 98`() {
        assertEquals(0.98f, MotionTokens.PRESS_SCALE, 0.001f)
    }
}
