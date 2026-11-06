package com.koodoreader.reader.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.koodoreader.core.importer.EpubChapterLoader
import com.koodoreader.core.importer.EpubResourceLoader
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.reader.ChapterLoader

/** Cache key for [LayoutLine] inline images (matches legacy canvas reader). */
fun imageLineKey(line: LayoutLine): String =
    "${line.position.spineIndex}:${line.imageSrc}"

fun ChapterLoader.chapterHrefForSpine(spineIndex: Int): String {
    if (spineIndex < 1) return ""
    val epub = this as? EpubChapterLoader ?: return ""
    val order = spineIndex - 1
    return epub.epubSpine.chapters.getOrNull(order)?.href ?: ""
}

fun ChapterLoader.readInlineImage(baseHref: String, src: String): ByteArray? {
    if (baseHref.isBlank() || src.isBlank()) return null
    val srcClean = src.trim()
    if (srcClean.startsWith("data:")) {
        val base64 = srcClean.substringAfter("base64=", "").trim()
        return runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull()
    }
    val resolved = EpubResourceLoader.resolve(baseHref, srcClean) ?: return null
    val epub = this as? EpubChapterLoader ?: return null
    return epub.epubSpine.readResource(resolved)
}

fun ChapterLoader.decodeInlineImage(line: LayoutLine): Bitmap? {
    val src = line.imageSrc?.takeIf { it.isNotBlank() } ?: return null
    val baseHref = chapterHrefForSpine(line.position.spineIndex)
    val bytes = readInlineImage(baseHref, src) ?: return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

fun ChapterLoader.loadInlineImages(lines: List<LayoutLine>): Map<String, Bitmap?> =
    lines.filter { it.imageSrc != null }
        .associate { line -> imageLineKey(line) to decodeInlineImage(line) }
