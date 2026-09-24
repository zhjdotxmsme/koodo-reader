package com.koodoreader.core.common

/**
 * Mirror of the desktop FontItem (src/utils/file/fontUtil.ts:13) so native
 * font metadata stays desktop-compatible (same fields, same config keys).
 */
data class FontItem(
    val id: String,
    val label: String,
    val value: String,
    val type: String,
)

/** Config keys / naming rules shared with the desktop font system. */
object NativeFontKeys {

    /** Directory under the app files dir (desktop storage: `fonts/`). */
    const val FONT_DIR = "fonts"

    /** Desktop config keys (kept identical for migration parity). */
    const val FONT_LIST_KEY = "fontList"
    const val CUSTOM_FONTS_MAP = "customFonts"

    /** Desktop localforage key prefix for font bytes in the browser track. */
    const val LOCALFORAGE_PREFIX = "font_"

    /** The native reader accepts only real font files; woff/woff2 stay on the WebView track (ADR-005). */
    val IMPORTABLE_EXTS = setOf("ttf", "otf")

    /** Desktop-side importable set (fontUtil.isValidFontExtension). */
    val DESKTOP_IMPORT_EXTS = listOf("ttf", "otf", "woff")

    /**
     * Desktop parity (fontUtil.normalizeFontName): strip the extension, then
     * spaces/hyphens/dots → underscores. Case is preserved.
     */
    fun normalizeFontName(fileName: String): String {
        val lastDot = fileName.lastIndexOf('.')
        val base = if (lastDot != -1) fileName.substring(0, lastDot) else fileName
        return base.replace(' ', '_').replace('-', '_').replace('.', '_')
    }

    fun fontExtension(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "ttf")
        return ext.lowercase()
    }

    fun isValidImportExt(ext: String): Boolean =
        ext.lowercase() in IMPORTABLE_EXTS
}
