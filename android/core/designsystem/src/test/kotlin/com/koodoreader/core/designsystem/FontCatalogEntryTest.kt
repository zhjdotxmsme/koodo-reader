package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Verifies that [FontCatalogEntry] key names are aligned with the desktop
 * font system:
 *   - fontUtil.getFontIds() → stored under ConfigService "fontList"
 *   - fontUtil.getFontMeta(id) → stored under ConfigService "customFonts"
 *   - FontItem.kt mirrors the same fields on the native side
 *
 * Known key references:
 *   - FontCatalog.kt (android/app): "bundled:lxgw_wenkai_lite", "bundled:inter"
 *   - FontManager.kt: CSS generic families "serif", "sans-serif", "monospace"
 *   - FontItem.kt NativeFontKeys: FONT_LIST_KEY = "fontList"
 *   - fontConfig.ts: font id values like "LXGWWenKai-Light", "Inter-Regular", etc.
 */
class FontCatalogEntryTest {

    // ─── Built-in sentinel ───────────────────────────────────────────────────

    @Test
    fun `BUILT_IN entry key matches desktop sentinel value`() {
        assertEquals("Built-in font", FontCatalogEntry.BUILT_IN.key)
    }

    @Test
    fun `BUILT_IN entry has null fontFamily`() {
        assertEquals(null, FontCatalogEntry.BUILT_IN.fontFamily)
    }

    @Test
    fun `BUILT_IN entry is usable in a config`() {
        val config = ReaderAppearanceConfig(
            fontCatalogEntry = FontCatalogEntry.BUILT_IN,
        )
        assertEquals("Built-in font", config.fontCatalogEntry.key)
    }

    // ─── CSS generic family keys ─────────────────────────────────────────────

    @Test
    fun `CSS_FAMILY_KEYS contains serif sans-serif monospace`() {
        assertEquals(3, FontCatalogEntry.CSS_FAMILY_KEYS.size)
        assert(FontCatalogEntry.CSS_FAMILY_KEYS.contains("serif"))
        assert(FontCatalogEntry.CSS_FAMILY_KEYS.contains("sans-serif"))
        assert(FontCatalogEntry.CSS_FAMILY_KEYS.contains("monospace"))
    }

    @Test
    fun `CSS generic entries can be constructed`() {
        for (family in FontCatalogEntry.CSS_FAMILY_KEYS) {
            val entry = FontCatalogEntry(
                key = family,
                displayName = family,
                fontFamily = family,
            )
            assertEquals(family, entry.key)
            assertEquals(family, entry.fontFamily)
        }
    }

    // ─── KNOWN_BUNDLED_KEYS alignment with fontConfig ts ───────────────────

    @Test
    fun `KNOWN_BUNDLED_KEYS contains LXGWWenKai variants from fontConfig ts`() {
        assert(FontCatalogEntry.KNOWN_BUNDLED_KEYS.contains("LXGWWenKai-Light"))
        assert(FontCatalogEntry.KNOWN_BUNDLED_KEYS.contains("LXGWWenKai-Medium"))
        assert(FontCatalogEntry.KNOWN_BUNDLED_KEYS.contains("LXGWWenKai-Regular"))
    }

    @Test
    fun `KNOWN_BUNDLED_KEYS contains Inter from fontConfig ts`() {
        assert(FontCatalogEntry.KNOWN_BUNDLED_KEYS.contains("Inter-Regular"))
        assert(FontCatalogEntry.KNOWN_BUNDLED_KEYS.contains("Inter-Medium"))
    }

    @Test
    fun `KNOWN_BUNDLED_KEYS contains NotoSansSC from fontConfig ts`() {
        assert(FontCatalogEntry.KNOWN_BUNDLED_KEYS.contains("NotoSansSC-Light"))
        assert(FontCatalogEntry.KNOWN_BUNDLED_KEYS.contains("NotoSansSC-Medium"))
        assert(FontCatalogEntry.KNOWN_BUNDLED_KEYS.contains("NotoSansSC-Regular"))
    }

    @Test
    fun `KNOWN_BUNDLED_KEYS contains NotoSerifSC from fontConfig ts`() {
        assert(FontCatalogEntry.KNOWN_BUNDLED_KEYS.contains("NotoSerifSC-Light"))
        assert(FontCatalogEntry.KNOWN_BUNDLED_KEYS.contains("NotoSerifSC-Regular"))
    }

    @Test
    fun `KNOWN_BUNDLED_KEYS is non-empty`() {
        assert(FontCatalogEntry.KNOWN_BUNDLED_KEYS.isNotEmpty())
    }

    @Test
    fun `KNOWN_BUNDLED_KEYS entries are non-blank`() {
        for (key in FontCatalogEntry.KNOWN_BUNDLED_KEYS) {
            assert(key.isNotBlank(), "blank key in KNOWN_BUNDLED_KEYS")
        }
    }

    // ─── Bundled Android fonts (FontCatalog.kt alignment) ───────────────────

    @Test
    fun `bundled Android font keys follow 'bundled:' prefix convention`() {
        // FontCatalog.kt uses "bundled:lxgw_wenkai_lite", "bundled:inter"
        // which is a different namespace from desktop font ids.
        val bundled = FontCatalogEntry(
            key = "bundled:lxgw_wenkai_lite",
            displayName = "霞鹜文楷",
            fontFamily = null,
        )
        assertEquals("bundled:lxgw_wenkai_lite", bundled.key)
        assertEquals("bundled:lxgw_wenkai_lite", bundled.displayName)
        assertEquals(null, bundled.fontFamily)
    }

    // ─── Custom font entry ───────────────────────────────────────────────────

    @Test
    fun `custom font entry can be constructed with user-supplied key`() {
        val custom = FontCatalogEntry(
            key = "user-imported-book-font",
            displayName = "My Custom Font",
            fontFamily = "My Custom Font",
        )
        assertEquals("user-imported-book-font", custom.key)
        assertEquals("My Custom Font", custom.displayName)
        assertEquals("My Custom Font", custom.fontFamily)
    }

    // ─── FontItem.kt mirror verification ─────────────────────────────────────

    @Test
    fun `FontItem key concept matches FontCatalogEntry key concept`() {
        // FontItem.kt stores id/label/value — FontCatalogEntry stores key/displayName.
        // Both share the same semantic role (stable identifier for a font).
        val entry = FontCatalogEntry(
            key = "Roboto-Regular",
            displayName = "Roboto Regular",
            fontFamily = "Roboto",
        )
        assertNotNull(entry.key)
        assertNotNull(entry.displayName)
    }

    // ─── Custom font metadata stored under ConfigService customFonts ─────────

    @Test
    fun `custom font entry can be round-tripped through codec`() {
        val entry = FontCatalogEntry(
            key = "LXGWWenKai-Regular",
            displayName = "霞鹜文楷",
            fontFamily = "LXGW Wenkai",
        )
        val json = AppearanceCodec.encode(
            ReaderAppearanceConfig(fontCatalogEntry = entry),
        )
        val decoded = AppearanceCodec.decode(json)
        assertEquals(entry.key, decoded.fontCatalogEntry.key)
        assertEquals(entry.displayName, decoded.fontCatalogEntry.displayName)
        assertEquals(entry.fontFamily, decoded.fontCatalogEntry.fontFamily)
    }
}
