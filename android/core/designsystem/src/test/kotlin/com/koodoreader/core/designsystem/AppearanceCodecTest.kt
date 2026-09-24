package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Verifies [AppearanceCodec] round-trip fidelity and the three preset
 * serialisation/deserialisation paths.
 */
class AppearanceCodecTest {

    // ─── Round-trip fidelity ─────────────────────────────────────────────────

    @Test
    fun `encode decode round-trip preserves default config`() {
        val original = ReaderAppearanceConfig()
        val json = AppearanceCodec.encode(original)
        assertNotNull(json)
        assert(json.startsWith("{"))
        assert(json.endsWith("}"))
        val decoded = AppearanceCodec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun `encode decode round-trip preserves all typography fields`() {
        val original = ReaderAppearanceConfig(
            typography = TypographyTokens(
                fontSizeSp = 24f,
                lineHeightRatio = 1.75f,
                letterSpacingPx = 3f,
                textIndentPx = 48,
                paragraphSpacingBeforePx = 20,
                paragraphSpacingAfterPx = 10,
                marginLeftPx = 30,
                marginRightPx = 25,
                marginTopPx = 15,
                marginBottomPx = 15,
                textAlign = "justify",
            ),
        )
        val decoded = AppearanceCodec.decode(AppearanceCodec.encode(original))
        assertEquals(original.typography, decoded.typography)
    }

    @Test
    fun `encode decode round-trip preserves theme with backgroundImage`() {
        val original = ReaderAppearanceConfig(
            theme = ThemeSpec(
                kind = ThemeKind.CUSTOM,
                backgroundColor = "#1a1a2e",
                backgroundImage = "official-background-1",
                foregroundColor = "#e0e0e0",
                name = "Dark Theme",
            ),
        )
        val decoded = AppearanceCodec.decode(AppearanceCodec.encode(original))
        assertEquals(original.theme, decoded.theme)
    }

    @Test
    fun `encode decode round-trip preserves custom font entry`() {
        val original = ReaderAppearanceConfig(
            fontCatalogEntry = FontCatalogEntry(
                key = "LXGWWenKai-Regular",
                displayName = "霞鹜文楷",
                fontFamily = "LXGW Wenkai",
            ),
        )
        val decoded = AppearanceCodec.decode(AppearanceCodec.encode(original))
        assertEquals(original.fontCatalogEntry, decoded.fontCatalogEntry)
    }

    @Test
    fun `encode decode round-trip preserves standalone margins`() {
        val original = ReaderAppearanceConfig(
            marginHorizontalPx = 40,
            marginVerticalPx = 20,
            letterSpacingPx = 5f,
        )
        val decoded = AppearanceCodec.decode(AppearanceCodec.encode(original))
        assertEquals(40, decoded.marginHorizontalPx)
        assertEquals(20, decoded.marginVerticalPx)
        assertEquals(5f, decoded.letterSpacingPx)
    }

    // ─── Three preset round-trips ───────────────────────────────────────────

    @Test
    fun `DEFAULT preset round-trip preserves all fields`() {
        val config = ReaderAppearanceConfig(
            typography = TypographyTokens(),
            theme = ThemeSpec.DEFAULT_PRESET,
            fontCatalogEntry = FontCatalogEntry.BUILT_IN,
        )
        val decoded = AppearanceCodec.decode(AppearanceCodec.encode(config))
        assertEquals(ThemeKind.DEFAULT, decoded.theme.kind)
        assertEquals("rgba(255,255,255,1)", decoded.theme.backgroundColor)
        assertNull(decoded.theme.backgroundImage)
        assertEquals("rgba(0,0,0,1)", decoded.theme.foregroundColor)
        assertEquals("Default", decoded.theme.name)
        assertEquals(17f, decoded.typography.fontSizeSp)
        assertEquals(1.5f, decoded.typography.lineHeightRatio)
    }

    @Test
    fun `PROTECT_EYE preset round-trip preserves all fields`() {
        val config = ReaderAppearanceConfig(
            theme = ThemeSpec.PROTECT_EYE_PRESET,
        )
        val decoded = AppearanceCodec.decode(AppearanceCodec.encode(config))
        assertEquals(ThemeKind.PROTECT_EYE, decoded.theme.kind)
        assertEquals("rgba(197, 231, 207,1)", decoded.theme.backgroundColor)
        assertEquals("rgba(54, 80, 62,1)", decoded.theme.foregroundColor)
    }

    @Test
    fun `NIGHT preset round-trip preserves all fields`() {
        val config = ReaderAppearanceConfig(
            theme = ThemeSpec.NIGHT_PRESET,
        )
        val decoded = AppearanceCodec.decode(AppearanceCodec.encode(config))
        assertEquals(ThemeKind.NIGHT, decoded.theme.kind)
        assertEquals("rgba(44, 47, 49,1)", decoded.theme.backgroundColor)
        assertEquals("rgba(255,255,255,1)", decoded.theme.foregroundColor)
    }

    // ─── JSON key names preserved ───────────────────────────────────────────

    @Test
    fun `encoded JSON contains expected key names`() {
        val json = AppearanceCodec.encode(ReaderAppearanceConfig())
        assert(json.contains("\"fontSizeSp\""))
        assert(json.contains("\"lineHeightRatio\""))
        assert(json.contains("\"letterSpacingPx\""))
        assert(json.contains("\"textAlign\""))
        assert(json.contains("\"themeKind\""))
        assert(json.contains("\"backgroundColor\""))
        assert(json.contains("\"foregroundColor\""))
        assert(json.contains("\"fontKey\""))
        assert(json.contains("\"fontDisplayName\""))
        assert(json.contains("\"fontFamily\""))
        assert(json.contains("\"marginHorizontalPx\""))
        assert(json.contains("\"marginVerticalPx\""))
    }

    // ─── Malformed input handling ───────────────────────────────────────────

    @Test
    fun `decode throws on empty string`() {
        assertThrows<AppearanceCodecException> {
            AppearanceCodec.decode("")
        }
    }

    @Test
    fun `decode throws on non-object JSON`() {
        assertThrows<AppearanceCodecException> {
            AppearanceCodec.decode("\"just a string\"")
        }
    }

    @Test
    fun `decode throws on unterminated object`() {
        assertThrows<AppearanceCodecException> {
            AppearanceCodec.decode("{\"fontSizeSp\": 17")
        }
    }

    @Test
    fun `decode gracefully handles missing fields with defaults`() {
        // An empty object should produce the default config.
        val decoded = AppearanceCodec.decode("{}")
        assertEquals(17f, decoded.typography.fontSizeSp)
        assertEquals(ThemeKind.DEFAULT, decoded.theme.kind)
        assertEquals("Built-in font", decoded.fontCatalogEntry.key)
    }

    // ─── String escape handling ─────────────────────────────────────────────

    @Test
    fun `display name with quotes is round-tripped correctly`() {
        val entry = FontCatalogEntry(
            key = "test-font",
            displayName = "Font \"Name\" with quotes",
            fontFamily = null,
        )
        val decoded = AppearanceCodec.decode(
            AppearanceCodec.encode(ReaderAppearanceConfig(fontCatalogEntry = entry)),
        )
        assertEquals("Font \"Name\" with quotes", decoded.fontCatalogEntry.displayName)
    }

    @Test
    fun `display name with newlines is round-tripped correctly`() {
        val entry = FontCatalogEntry(
            key = "test-font",
            displayName = "Line1\nLine2",
            fontFamily = null,
        )
        val decoded = AppearanceCodec.decode(
            AppearanceCodec.encode(ReaderAppearanceConfig(fontCatalogEntry = entry)),
        )
        assertEquals("Line1\nLine2", decoded.fontCatalogEntry.displayName)
    }

    // ─── Background image field ─────────────────────────────────────────────

    @Test
    fun `null backgroundImage serialises as null`() {
        val json = AppearanceCodec.encode(
            ReaderAppearanceConfig(theme = ThemeSpec.DEFAULT_PRESET),
        )
        assert(json.contains("\"backgroundImage\":null"))
    }

    @Test
    fun `data-URI backgroundImage is round-tripped correctly`() {
        val dataUri = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val original = ReaderAppearanceConfig(
            theme = ThemeSpec(
                kind = ThemeKind.CUSTOM,
                backgroundColor = "#ffffff",
                backgroundImage = dataUri,
                foregroundColor = "#000000",
                name = "Custom",
            ),
        )
        val decoded = AppearanceCodec.decode(AppearanceCodec.encode(original))
        assertEquals(dataUri, decoded.theme.backgroundImage)
    }

    @Test
    fun `official background id is round-tripped correctly`() {
        val original = ReaderAppearanceConfig(
            theme = ThemeSpec(
                kind = ThemeKind.CUSTOM,
                backgroundImage = "official-background-3",
                backgroundColor = "#f5f5dc",
                foregroundColor = "#000000",
                name = "Official 3",
            ),
        )
        val decoded = AppearanceCodec.decode(AppearanceCodec.encode(original))
        assertEquals("official-background-3", decoded.theme.backgroundImage)
    }
}
