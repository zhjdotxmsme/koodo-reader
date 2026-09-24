package com.koodoreader.reader.shell

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.res.ResourcesCompat
import com.koodoreader.core.common.FontItem
import com.koodoreader.core.common.NativeFontKeys
import java.io.File
import java.text.Collator

/**
 * Native font foundation (P2 prep — no reader UI yet, see card t-muew0ia6):
 *  - system font enumeration (API 29+ SystemFonts; lower: /system/fonts listing
 *    + known generic families)
 *  - custom font import via SAF (ttf/otf → filesDir/fonts/<key>.<ext>)
 *  - Typeface LRU cache with graceful degradation (a bad font never crashes,
 *    never blocks opening a book)
 *  - desktop-parity metadata via [FontPrefs] (fontList / customFonts keys)
 */
object FontManager {

    data class SystemFontInfo(val key: String, val label: String)

    private val GENERIC_FAMILIES = linkedMapOf(
        "sans-serif" to "Sans",
        "serif" to "Serif",
        "monospace" to "Mono",
        "sans-serif-condensed" to "Sans condensed",
    )

    // ------------------------------------------------------ system fonts

    fun systemFonts(context: Context): List<SystemFontInfo> {
        if (Build.VERSION.SDK_INT >= 29) {
            val seen = HashSet<String>()
            val out = mutableListOf<SystemFontInfo>()
            SystemFonts.getAvailableFonts()
                .mapNotNull { it.file?.name }
                .forEach { name ->
                    val key = NativeFontKeys.normalizeFontName(name)
                    if (seen.add(key)) out.add(SystemFontInfo(key, name))
                }
            return out.sortedWith(compareBy(Collator.getInstance()) { it.label })
        }
        // Low-version fallback: generic families + /system/fonts filenames.
        val out = GENERIC_FAMILIES.map { (key, label) -> SystemFontInfo(key, label) }.toMutableList()
        File("/system/fonts").listFiles()
            ?.sortedBy { it.name }
            ?.forEach { f ->
                val key = NativeFontKeys.normalizeFontName(f.name)
                out.add(SystemFontInfo(key, f.name))
            }
        return out
    }

    // ------------------------------------------------------- custom fonts

    /**
     * Import a SAF-picked font (ttf/otf only — woff/woff2 stay on the
     * WebView track, ADR-005). Returns the FontItem, or null (invalid ext /
     * unreadable / not loadable — the file is deleted in the failure cases).
     */
    fun importFont(context: Context, uri: Uri): FontItem? {
        val name = displayName(context, uri) ?: return null
        val ext = NativeFontKeys.fontExtension(name)
        if (!NativeFontKeys.isValidImportExt(ext)) return null
        val key = NativeFontKeys.normalizeFontName(name)

        val dir = File(context.filesDir, NativeFontKeys.FONT_DIR)
        dir.mkdirs()
        val dest = File(dir, "$key.$ext")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return null
        }
        if (copied.isFailure) {
            dest.delete()
            return null
        }

        // Fail fast on unloadable fonts: keep the catalog clean.
        if (typefaceForFile(dest) == null) {
            dest.delete()
            return null
        }

        val item = FontItem(id = key, label = name, value = key, type = ext)
        FontPrefs.get(context).addFont(item)
        return item
    }

    fun installedCustom(context: Context): List<FontItem> =
        FontPrefs.get(context).installed()

    fun deleteCustom(context: Context, key: String) {
        val dir = File(context.filesDir, NativeFontKeys.FONT_DIR)
        dir.listFiles()?.forEach { f -> if (f.nameWithoutExtension == key) f.delete() }
        FontPrefs.get(context).removeFont(key)
    }

    // ----------------------------------------------------- typeface cache

    private val lru = object : LinkedHashMap<String, Typeface>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?) =
            size > 32
    }

    /** Load a bundled/custom font by reader-settings key; null → caller uses default. */
    fun typefaceFor(context: Context, fontKey: String?): Typeface? {
        if (fontKey.isNullOrBlank()) return null
        if (fontKey.startsWith("bundled:")) {
            val res = FontCatalog.byKey(fontKey)?.resId ?: return null
            return synchronized(lru) {
                lru["res:$fontKey"]
                    ?: runCatching { ResourcesCompat.getFont(context, res) }.getOrNull()
                        ?.also { lru["res:$fontKey"] = it }
            }
        }
        val dir = File(context.filesDir, NativeFontKeys.FONT_DIR)
        val file = dir.listFiles()?.firstOrNull { it.nameWithoutExtension == fontKey }
        return file?.let { typefaceForFile(it) }
    }

    private fun typefaceForFile(file: File): Typeface? = synchronized(lru) {
        lru[file.absolutePath]
            ?: runCatching { Typeface.createFromFile(file) }.getOrNull()
                ?.also { lru[file.absolutePath] = it }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) c.getString(idx) else null
        }
    }.getOrNull()
}
