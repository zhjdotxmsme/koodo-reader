package com.koodoreader.reader.metadata

import com.koodoreader.core.importer.BookCover
import java.net.HttpURLConnection
import java.net.URL

object CoverDownload {

    fun fetchBookCover(url: String): BookCover? {
        val trimmed = url.trim().replace("http://", "https://")
        if (!trimmed.startsWith("https://")) return null
        val connection = (URL(trimmed).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size < 32) return null
            val ext = when {
                trimmed.contains(".png", ignoreCase = true) -> "png"
                trimmed.contains(".webp", ignoreCase = true) -> "webp"
                bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
                else -> "jpg"
            }
            BookCover(bytes, ext)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}