package com.koodoreader.reader.shell

import com.koodoreader.reader.LocalAssetServer
import java.io.File

/**
 * Loopback access for the reader screens (P3).
 *
 * The reader engine is a `WebView` with file access disabled, so a book can only
 * reach it over HTTP: the activity hands this class its running
 * [LocalAssetServer] and the reader publishes the file for the duration of the
 * session.
 *
 * A null/no-op instance ([NONE]) keeps the screens usable (and renderable in
 * previews) when the loopback server could not start; callers must check
 * [available] and show an error instead of opening a bogus URL.
 */
class ReaderAssetHost(
    val baseUrl: String,
    private val server: LocalAssetServer?,
) {

    val available: Boolean get() = server != null && baseUrl.isNotEmpty()

    /**
     * Publish [file] on the loopback origin and return the absolute URL to hand
     * to `PdfHostBridge.open`, or null when the server is unavailable.
     */
    fun publish(file: File): String? {
        val srv = server ?: return null
        if (!available) return null
        val path = srv.exposeFile(ReaderFiles.virtualPath(file), file)
        return "$baseUrl/$path"
    }

    /** Drop a previously published file (see [LocalAssetServer.releaseExposed]). */
    fun release(file: File) {
        server?.releaseExposed(ReaderFiles.virtualPath(file))
    }

    companion object {
        val NONE = ReaderAssetHost("", null)
    }
}
