package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Verifies that the three built-in [ThemeSpec] presets have colours that match
 * the desktop source values extracted from:
 *   - themeList.tsx  backgroundList / textList (built-in preset colours)
 *   - themeUtil.ts   generateThemeCSS() applies rgba colours via ConfigService
 *
 * Preset colours:
 *   DEFAULT    background rgba(255,255,255,1)   text rgba(0,0,0,1)
 *   PROTECT_EY background rgba(197, 231, 207,1)  text rgba(54, 80, 62,1)
 *   NIGHT      background rgba(44, 47, 49,1)     text rgba(255,255,255,1)
 */
class ThemePresetTest {

    // ─── Default preset ───────────────────────────────────────────────────────

    @Test
    fun `DEFAULT preset backgroundColor matches themeList backgroundList 0`() {
        assertEquals("rgba(255,255,255,1)", ThemeSpec.DEFAULT_PRESET.backgroundColor)
    }

    @Test
    fun `DEFAULT preset foregroundColor matches themeList textList 0`() {
        assertEquals("rgba(0,0,0,1)", ThemeSpec.DEFAULT_PRESET.foregroundColor)
    }

    @Test
    fun `DEFAULT preset kind is DEFAULT`() {
        assertEquals(ThemeKind.DEFAULT, ThemeSpec.DEFAULT_PRESET.kind)
    }

    @Test
    fun `DEFAULT preset name is Default`() {
        assertEquals("Default", ThemeSpec.DEFAULT_PRESET.name)
    }

    @Test
    fun `DEFAULT preset has no backgroundImage`() {
        assertEquals(null, ThemeSpec.DEFAULT_PRESET.backgroundImage)
    }

    // ─── Eye-protection preset ─────────────────────────────────────────────────

    @Test
    fun `PROTECT_EYE preset backgroundColor matches themeList backgroundList 3`() {
        assertEquals("rgba(197, 231, 207,1)", ThemeSpec.PROTECT_EYE_PRESET.backgroundColor)
    }

    @Test
    fun `PROTECT_EYE preset foregroundColor matches themeList textList 3`() {
        assertEquals("rgba(54, 80, 62,1)", ThemeSpec.PROTECT_EYE_PRESET.foregroundColor)
    }

    @Test
    fun `PROTECT_EYE preset kind is PROTECT_EYE`() {
        assertEquals(ThemeKind.PROTECT_EYE, ThemeSpec.PROTECT_EYE_PRESET.kind)
    }

    @Test
    fun `PROTECT_EYE preset name is Eye Protection`() {
        assertEquals("Eye Protection", ThemeSpec.PROTECT_EYE_PRESET.name)
    }

    @Test
    fun `PROTECT_EYE preset has no backgroundImage`() {
        assertEquals(null, ThemeSpec.PROTECT_EYE_PRESET.backgroundImage)
    }

    // ─── Night preset ────────────────────────────────────────────────────────

    @Test
    fun `NIGHT preset backgroundColor matches themeList backgroundList 1`() {
        assertEquals("rgba(44, 47, 49,1)", ThemeSpec.NIGHT_PRESET.backgroundColor)
    }

    @Test
    fun `NIGHT preset foregroundColor matches themeList textList 1`() {
        assertEquals("rgba(255,255,255,1)", ThemeSpec.NIGHT_PRESET.foregroundColor)
    }

    @Test
    fun `NIGHT preset kind is NIGHT`() {
        assertEquals(ThemeKind.NIGHT, ThemeSpec.NIGHT_PRESET.kind)
    }

    @Test
    fun `NIGHT preset name is Night`() {
        assertEquals("Night", ThemeSpec.NIGHT_PRESET.name)
    }

    @Test
    fun `NIGHT preset has no backgroundImage`() {
        assertEquals(null, ThemeSpec.NIGHT_PRESET.backgroundImage)
    }

    // ─── BUILT_IN_PRESETS ordering ───────────────────────────────────────────

    @Test
    fun `BUILT_IN_PRESETS has exactly 3 entries`() {
        assertEquals(3, ThemeSpec.BUILT_IN_PRESETS.size)
    }

    @Test
    fun `BUILT_IN_PRESETS order matches desktop preset index (DEFAULT=0, PROTECT_EYE=1, NIGHT=2)`() {
        assertEquals(ThemeKind.DEFAULT, ThemeSpec.BUILT_IN_PRESETS[0].kind)
        assertEquals(ThemeKind.PROTECT_EYE, ThemeSpec.BUILT_IN_PRESETS[1].kind)
        assertEquals(ThemeKind.NIGHT, ThemeSpec.BUILT_IN_PRESETS[2].kind)
    }

    // ─── CUSTOM construction ─────────────────────────────────────────────────

    @Test
    fun `CUSTOM theme can be constructed with arbitrary colours`() {
        val custom = ThemeSpec(
            kind = ThemeKind.CUSTOM,
            backgroundColor = "#1a1a2e",
            backgroundImage = "official-background-1",
            foregroundColor = "#e0e0e0",
            name = "My Theme",
        )
        assertEquals(ThemeKind.CUSTOM, custom.kind)
        assertEquals("#1a1a2e", custom.backgroundColor)
        assertEquals("official-background-1", custom.backgroundImage)
        assertEquals("#e0e0e0", custom.foregroundColor)
        assertEquals("My Theme", custom.name)
    }

    @Test
    fun `CUSTOM theme with null backgroundImage is allowed`() {
        val custom = ThemeSpec(kind = ThemeKind.CUSTOM)
        assertEquals(ThemeKind.CUSTOM, custom.kind)
        assertEquals(null, custom.backgroundImage)
    }

    // ─── Colour format sanity ────────────────────────────────────────────────

    @Test
    fun `all preset colours are valid rgba or hex strings`() {
        for (preset in ThemeSpec.BUILT_IN_PRESETS) {
            assertNotNull(preset.backgroundColor)
            assertNotNull(preset.foregroundColor)
            // Basic sanity: non-empty and starts with # or rgba
            assertTrue(
                preset.backgroundColor.startsWith("#") || preset.backgroundColor.startsWith("rgba"),
                "unexpected bg format: ${preset.backgroundColor}",
            )
            assertTrue(
                preset.foregroundColor.startsWith("#") || preset.foregroundColor.startsWith("rgba"),
                "unexpected fg format: ${preset.foregroundColor}",
            )
        }
    }

    // ─── ThemeKind enum values ───────────────────────────────────────────────

    @Test
    fun `ThemeKind has exactly four values`() {
        assertEquals(4, ThemeKind.entries.size)
    }

    @Test
    fun `ThemeKind CUSTOM is distinct from presets`() {
        val kinds = ThemeKind.entries.toSet()
        assertTrue(kinds.containsAll(listOf(ThemeKind.DEFAULT, ThemeKind.PROTECT_EYE, ThemeKind.NIGHT, ThemeKind.CUSTOM)))
    }
}
