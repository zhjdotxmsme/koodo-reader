package com.koodoreader.core.importer

/**
 * Safe relative href resolution inside an EPUB package (no `..` escape).
 */
object EpubResourceLoader {
    fun resolve(chapterHref: String, src: String): String? {
        val trimmed = src.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith('/') || trimmed.contains("..")) return null
        if (Regex("(^|/)\\.\\.(/|$)").containsMatchIn(trimmed)) return null
        val base = chapterHref.substringBeforeLast('/', "")
        val combined = if (base.isEmpty()) trimmed else "$base/${trimmed.trimStart('/')}"
        val parts = combined.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }
}
