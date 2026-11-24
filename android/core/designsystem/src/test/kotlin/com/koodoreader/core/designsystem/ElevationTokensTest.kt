package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElevationTokensTest {

    @Test
    fun `floating shadow uses the 0 4 12 vocabulary`() {
        assertEquals(0f, ElevationTokens.FLOATING_SHADOW_Y - 4f, 0.001f)
        assertEquals(12f, ElevationTokens.FLOATING_SHADOW_BLUR, 0.001f)
    }

    @Test
    fun `popup shadow uses the 0 12 32 vocabulary`() {
        assertEquals(12f, ElevationTokens.POPUP_SHADOW_Y, 0.001f)
        assertEquals(32f, ElevationTokens.POPUP_SHADOW_BLUR, 0.001f)
    }

    @Test
    fun `popup shadow is stronger than floating shadow`() {
        assertTrue(ElevationTokens.POPUP_SHADOW_ALPHA > ElevationTokens.FLOATING_SHADOW_ALPHA)
    }

    @Test
    fun `shadow alphas are within valid range`() {
        assertTrue(ElevationTokens.FLOATING_SHADOW_ALPHA in 0f..1f)
        assertTrue(ElevationTokens.POPUP_SHADOW_ALPHA in 0f..1f)
    }

    @Test
    fun `card corner radius is 12dp`() {
        assertEquals(12f, ElevationTokens.CARD_CORNER_RADIUS, 0.001f)
    }
}
