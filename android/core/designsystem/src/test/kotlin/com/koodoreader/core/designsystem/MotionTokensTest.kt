package com.koodoreader.core.designsystem

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
}
