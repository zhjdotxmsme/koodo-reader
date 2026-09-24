package com.koodoreader.reader

import android.content.res.AssetManager
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Minimal loopback HTTP server serving the bundled web build
 * (`assets/webapp/…`) to the [android.webkit.WebView].
 *
 * Why not `file:///android_asset/…`? A `file://` page has a null origin, which
 * restricts IndexedDB / localStorage / service workers / CORS in ways that the
 * web app can hit. The official Android app solves this the same way (it embeds
 * lighttpd and loads the page over `http://127.0.0.1:<port>`). This class is the
 * dependency-free equivalent: `ServerSocket` on loopback + a small worker pool.
 *
 * Features:
 *  - binds to 127.0.0.1 only (never exposed to the network), ephemeral port;
 *  - static assets from `assets/<root>` (see [AssetPaths.DEFAULT_ROOTS]: the
 *    React island under `webapp` plus the pdf.js host under `pdfengine`) with
 *    correct MIME types;
 *  - single-range `Range: bytes=a-b` support (206) so media/PDF fetches work;
 *  - SPA fallback to `index.html` for extension-less paths;
 *  - virtual paths mapped to real files ([exposeFile]) — used to hand the web
 *    app a book that arrived as a VIEW/SEND intent;
 *  - `HEAD` supported; every response closes the connection (`Connection: close`).
 */
class LocalAssetServer(
    private val assets: AssetManager,
    private val assetRoot: String = "webapp",
    private val threadCount: Int = 4,
    /**
     * Additional asset roots tried after [assetRoot]. Defaults to
     * `pdfengine` so a server started for the React island also serves the
     * native PDF reader's pdf.js host (see [AssetPaths]).
     */
    extraRoots: List<String> = listOf("pdfengine")
) {

    /** Roots served from `assets/`, in order. */
    private val roots: List<String> = (listOf(assetRoot) + extraRoots).distinct()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var pool: ExecutorService? = null

    /** Actual bound port (0 until [start] succeeds). */
    @Volatile
    var port: Int = 0
        private set

    /** Virtual path (no leading slash, e.g. `__books__/x.epub`) → real file. */
    private val extras = ConcurrentHashMap<String, File>()

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    /**
     * Start accepting connections on an ephemeral loopback port.
     * @return the bound port; throws if the socket cannot be bound.
     */
    fun start(): Int {
        if (isRunning) return port
        val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
        serverSocket = socket
        port = socket.localPort
        val executor = Executors.newFixedThreadPool(threadCount)
        pool = executor
        val acceptor = Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break // socket closed → stop accepting
                }
                try {
                    executor.execute { handle(client) }
                } catch (e: RejectedExecutionException) {
                    closeQuietly(client)
                }
            }
        }
        acceptor.name = "koodo-http"
        acceptor.isDaemon = true
        acceptor.start()
        return port
    }

    /** Stop the server: closes the listening socket and drains the pool. */
    fun stop() {
        val socket = serverSocket
        serverSocket = null
        try {
            socket?.close()
        } catch (ignored: IOException) {
            // already closed
        }
        val executor = pool
        pool = null
        executor?.shutdownNow()
    }

    /** Base URL for the web app once started, e.g. `http://127.0.0.1:41234`. */
    fun baseUrl(): String = "http://$LOOPBACK:$port"

    /** Map a virtual path to a real file (book pushed by an OS intent). */
    fun exposeFile(path: String, file: File): String {
        val key = normalizePath(path)
        extras[key] = file
        return key
    }

    /** Drop a previously exposed virtual path. */
    fun releaseExposed(path: String) {
        extras.remove(normalizePath(path))
    }

    // ── request handling ────────────────────────────────────────────────────
    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = socket.getInputStream()
            val requestLine = readLine(input) ?: return
            val out = BufferedOutputStream(socket.getOutputStream())

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val sep = line.indexOf(':')
                if (sep > 0) {
                    headers[line.substring(0, sep).trim().lowercase()] =
                        line.substring(sep + 1).trim()
                }
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeStatus(out, 400, "Bad Request")
                return
            }
            val method = parts[0].uppercase()
            val isHead = method == "HEAD"
            if (method != "GET" && !isHead) {
                writeStatus(out, 405, "Method Not Allowed")
                return
            }

            val rawTarget = parts[1]
            val decoded = try {
                URLDecoder.decode(rawTarget.substringBefore('?'), "UTF-8")
            } catch (e: Exception) {
                rawTarget.substringBefore('?')
            }
            val path = normalizePath(decoded)
            val range = headers["range"]

            val file = extras[path]
            if (file != null) {
                if (file.isFile) {
                    serveFile(out, file, path, range, isHead)
                } else {
                    extras.remove(path)
                    writeStatus(out, 404, "Not Found")
                }
            } else {
                serveAsset(out, path, range, isHead)
            }
            out.flush()
        } catch (ignored: Exception) {
            // Client vanished / malformed request: nothing useful to send.
        } finally {
            closeQuietly(socket)
        }
    }

    private fun serveAsset(out: OutputStream, path: String, range: String?, isHead: Boolean) {
        var bytes = readAsset(path)
        var servedPath = path
        if (bytes == null && AssetPaths.isClientRoute(path)) {
            // SPA fallback for extension-less client routes.
            bytes = readAsset(AssetPaths.INDEX_FILE)
            servedPath = AssetPaths.INDEX_FILE
        }
        if (bytes == null) {
            writeStatus(out, 404, "Not Found")
            return
        }
        writeBytes(out, bytes, contentType(servedPath), range, isHead)
    }

    private fun serveFile(
        out: OutputStream,
        file: File,
        path: String,
        range: String?,
        isHead: Boolean
    ) {
        val length = file.length()
        val type = contentType(path)
        val parsed = parseRange(range, length)
        if (parsed != null) {
            val start = parsed.first
            val end = parsed.second
            val count = (end - start + 1)
            writeHeader(
                out, 206, "Partial Content", type, count,
                "Content-Range: bytes $start-$end/$length\r\n"
            )
            if (!isHead) {
                FileInputStream(file).use { input ->
                    skipFully(input, start)
                    copyBounded(input, out, count)
                }
            }
        } else {
            writeHeader(out, 200, "OK", type, length, "")
            if (!isHead) {
                FileInputStream(file).use { input -> copyBounded(input, out, length) }
            }
        }
    }

    private fun readAsset(path: String): ByteArray? {
        for (candidate in AssetPaths.candidates(path, roots)) {
            val bytes = try {
                assets.open(candidate).use { input -> input.readBytes() }
            } catch (ignored: Exception) {
                null
            }
            if (bytes != null) return bytes
        }
        return null
    }

    private fun writeBytes(
        out: OutputStream,
        bytes: ByteArray,
        type: String,
        range: String?,
        isHead: Boolean
    ) {
        val length = bytes.size.toLong()
        val parsed = parseRange(range, length)
        if (parsed != null) {
            val start = parsed.first
            val end = parsed.second
            val count = (end - start + 1).toInt()
            writeHeader(
                out, 206, "Partial Content", type, count.toLong(),
                "Content-Range: bytes $start-$end/$length\r\n"
            )
            if (!isHead) out.write(bytes, start.toInt(), count)
        } else {
            writeHeader(out, 200, "OK", type, length, "")
            if (!isHead) out.write(bytes)
        }
    }

    private fun writeHeader(
        out: OutputStream,
        code: Int,
        reason: String,
        type: String,
        length: Long,
        extra: String
    ) {
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: $length\r\n" +
            "Accept-Ranges: bytes\r\n" +
            "Cache-Control: no-cache\r\n" +
            extra +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
    }

    private fun writeStatus(out: OutputStream, code: Int, reason: String) {
        val body = "$code $reason".toByteArray(Charsets.UTF_8)
        writeHeader(out, code, reason, "text/plain; charset=utf-8", body.size.toLong(), "")
        out.write(body)
        out.flush()
    }

    // ── helpers ─────────────────────────────────────────────────────────────
    /** Strip query/leading slashes and traversal segments; empty → index.html. */
    private fun normalizePath(raw: String): String = AssetPaths.normalize(raw)

    /** Parse a single-range `bytes=a-b` header; null when absent/unsatisfiable. */
    private fun parseRange(range: String?, length: Long): Pair<Long, Long>? {
        if (range == null || length <= 0L) return null
        val trimmed = range.trim()
        if (!trimmed.startsWith("bytes=")) return null
        val spec = trimmed.substring(6).substringBefore(',')
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startRaw = spec.substring(0, dash).trim()
        val endRaw = spec.substring(dash + 1).trim()
        val start: Long
        val end: Long
        try {
            if (startRaw.isEmpty()) {
                val suffix = endRaw.toLong()
                if (suffix <= 0L) return null
                start = maxOf(0L, length - suffix)
                end = length - 1
            } else {
                start = startRaw.toLong()
                if (start >= length) return null
                end = minOf(if (endRaw.isEmpty()) length - 1 else endRaw.toLong(), length - 1)
            }
        } catch (e: NumberFormatException) {
            return null
        }
        return if (start > end) null else Pair(start, end)
    }

    private fun copyBounded(input: InputStream, out: OutputStream, count: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    /** Read one CRLF-terminated line (headers are ASCII, so bytes → chars). */
    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        var byte = input.read()
        if (byte == -1) return null
        while (byte != -1) {
            if (byte == '\n'.code) break
            if (byte != '\r'.code) builder.append(byte.toChar())
            byte = input.read()
        }
        return builder.toString()
    }

    private fun contentType(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm", "xhtml" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "webmanifest", "manifest" -> "application/manifest+json"
            "xml" -> "application/xml; charset=utf-8"
            "txt", "md" -> "text/plain; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "wasm" -> "application/wasm"
            "pdf" -> "application/pdf"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "epub" -> "application/epub+zip"
            "mobi" -> "application/x-mobipocket-ebook"
            "azw3", "azw" -> "application/vnd.amazon.ebook"
            "fb2" -> "application/x-fictionbook+xml"
            "cbz" -> "application/x-cbz"
            "cbr" -> "application/x-cbr"
            "cbt" -> "application/x-cbt"
            "cb7" -> "application/x-cb7"
            else -> "application/octet-stream"
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (ignored: IOException) {
            // ignore
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val BACKLOG = 64
        const val SOCKET_TIMEOUT_MS = 5000
        const val COPY_BUFFER = 64 * 1024
    }
}
