package com.koodoreader.core.importer

import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Comic (CBZ) cover extraction (P1 import) — parity with the desktop
 * importer (`src/components/importLocal/component.tsx` `handleComicImport`,
 * which follows kookit `comic-book.js`): the FIRST image of the archive under
 * NATURAL (numeric-aware) sort order, and the archive's image count for the
 * `books.page` column.
 *
 * CBR / CBT / CB7 need RAR/TAR/7z decoders that the pure-JVM module does not
 * ship (the WebView track uses wasm libs for those) — those keep placeholder
 * covers until P5's native image engine lands.
 */
object ComicCover {

    /** Image extensions accepted as comic pages (importLocal COMIC_IMAGE_EXTS). */
    val IMAGE_EXTS: Set<String> = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg",
        "avif", "apng", "ico", "cur", "jfif", "pjpeg", "pjp",
    )

    data class Result(
        val cover: BookCover?,
        val pageCount: Int,
    )

    /** Extract cover + page count from a CBZ [file]. Returns null on failure. */
    fun extract(file: File): Result? = runCatching {
        ZipFile(file).use { zip ->
            val images = zip.entries().asSequence()
                .map { it.name }
                .filter { name ->
                    // substringAfterLast returns the input unchanged when
                    // there is no directory prefix (no missing-default here).
                    extOf(name.substringAfterLast('/')) in IMAGE_EXTS
                }
                .sortedWith(naturalComparator())
                .toList()
            if (images.isEmpty()) {
                Result(cover = null, pageCount = 0)
            } else {
                val bytes = readEntryBytes(zip, images.first())
                if (bytes == null) {
                    Result(cover = null, pageCount = images.size)
                } else {
                    Result(
                        cover = BookCover(bytes, coverExt(images.first())),
                        pageCount = images.size,
                    )
                }
            }
        }
    }.getOrNull()

    private fun extOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (ext == "jpg" || ext == "jfif" || ext == "pjpeg" || ext == "pjp") "jpeg" else ext
    }

    private fun coverExt(entryName: String): String = extOf(entryName.substringAfterLast('/'))

    private fun readEntryBytes(zip: ZipFile, name: String): ByteArray? {
        zip.getEntry(name)?.let { e -> return zip.getInputStream(e).use { it.readBytes() } }
        val match = zip.entries().asSequence().firstOrNull { it.name.equals(name, ignoreCase = true) }
        return match?.let { zip.getInputStream(it).use { it.readBytes() } }
    }

    /**
     * Locale-independent numeric-aware string comparator: splits each string
     * into digit / non-digit runs (`a10b` → [a,10,b]) and compares integer
     * runs numerically, the rest case-insensitively. Mirrors JS
     * `localeCompare(x, y, undefined, { numeric: true, sensitivity: "base" })`
     * for the ASCII book-file names this app handles.
     */
    internal fun naturalComparator(): Comparator<String> = Comparator { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            val da = ca.isDigit()
            val db = cb.isDigit()
            if (da && db) {
                val va = readInt(a, i)
                val vb = readInt(b, j)
                if (va.value != vb.value) return@Comparator va.value.compareTo(vb.value)
                if (va.consumed != vb.consumed) {
                    // "07" vs "7": equal number, longer run first
                    return@Comparator vb.consumed.compareTo(va.consumed)
                }
                i += va.consumed
                j += vb.consumed
            } else if (da != db) {
                // Digits sort before letters when one side has none (stable choice)
                return@Comparator if (da) -1 else 1
            } else {
                val cmp = Character.toLowerCase(ca).compareTo(Character.toLowerCase(cb))
                if (cmp != 0) return@Comparator cmp
                i++
                j++
            }
        }
        (a.length - i).compareTo(b.length - j)
    }

    private class IntRun(val value: Long, val consumed: Int)

    private fun readInt(s: String, from: Int): IntRun {
        var value = 0L
        var i = from
        while (i < s.length && s[i].isDigit()) {
            value = (value * 10 + (s[i] - '0')).coerceAtMost(Long.MAX_VALUE / 2)
            i++
        }
        return IntRun(value, i - from)
    }
}
