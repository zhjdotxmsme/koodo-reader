package com.koodoreader.reader.shell

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Copies external book URIs into app cache for native comic / island hand-off. */
object IntentBookCache {

    fun copyToCache(
        resolver: ContentResolver,
        cacheDir: File,
        uri: Uri,
        mimeType: String?,
    ): File? {
        val name = displayName(resolver, uri, mimeType)
        val dir = File(cacheDir, "intent-books")
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, name)
        val input = when (uri.scheme?.lowercase()) {
            "content" -> resolver.openInputStream(uri)
            else -> File(uri.path ?: return null).inputStream()
        } ?: return null
        input.use { source ->
            target.outputStream().use { sink -> source.copyTo(sink) }
        }
        return target
    }

    fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun displayName(resolver: ContentResolver, uri: Uri, mimeType: String?): String {
        val raw = queryDisplayName(resolver, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "book"
        val safe = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim()
        val withName = if (safe.isEmpty()) "book" else safe
        return if (withName.contains('.')) withName else "$withName.${extensionFor(mimeType)}"
    }

    private fun extensionFor(mimeType: String?): String =
        when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "application/epub+zip" -> "epub"
            "application/pdf" -> "pdf"
            "text/plain" -> "txt"
            else -> "bin"
        }
}
