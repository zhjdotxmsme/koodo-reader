package com.koodoreader.feature.dictionary

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-demand asset download — the mechanism the P6 dictionary shares with the P6 OCR
 * model download (`docs/p6-dictionary-architecture.md` §6).
 *
 * The interface is deliberately asset-agnostic: an id, a URL and a target file. The
 * dictionary uses it for `.mdx` files and the OCR feature uses the exact same
 * contract for its model bundle, so a single implementation (and a single set of
 * tests / retry-progress semantics) covers both.
 *
 * Implementations MUST be atomic: nothing may appear at [Request.targetFile] until
 * the download finished, otherwise a killed download leaves a file that the MDX
 * parser will read as a truncated dictionary.
 */
interface OnDemandDownloader {

    data class Request(
        val id: String,
        val url: String,
        val targetFile: File,
        /** Expected size when the caller knows it — enables the truncation check. */
        val expectedBytes: Long? = null,
        val headers: Map<String, String> = emptyMap(),
        /** Polled between chunks so the UI can abort an in-flight download. */
        val isCancelled: () -> Boolean = { false },
    )

    data class Progress(val bytesRead: Long, val totalBytes: Long) {
        /** `0f..1f`, or `-1f` when the server did not send a length. */
        val fraction: Float
            get() = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else -1f
    }

    sealed interface Outcome {
        data class Success(val file: File, val bytes: Long, val resumed: Boolean) : Outcome
        data class Failure(val reason: String, val cause: Throwable? = null) : Outcome
        data object Cancelled : Outcome
    }

    fun download(request: Request, onProgress: (Progress) -> Unit = {}): Outcome

    /** Cheap pre-check: is the asset already there and plausibly complete? */
    fun isComplete(request: Request): Boolean {
        val file = request.targetFile
        if (!file.isFile) return false
        val expected = request.expectedBytes ?: return true
        return file.length() == expected
    }
}

/**
 * `java.net`-based downloader (no third-party dependency, works on Android API 24+
 * and on a plain JVM so it is unit-testable).
 *
 * The HTTP surface is reduced to [ConnectionFactory] so tests can inject a fake
 * server without a socket; production uses [HttpUrlConnectionFactory].
 *
 * Desktop parity: the caller-supplied headers mirror what `dictUtil.downloadCloudDict`
 * sends (`Cache-Control: no-transform`, `Accept-Encoding: identity`,
 * src/utils/file/dictUtil.ts:160-165) — those two headers matter because a
 * transformer/proxy that re-compresses the body would corrupt a byte-exact `.mdx`.
 */
class HttpOnDemandDownloader(
    private val factory: ConnectionFactory = HttpUrlConnectionFactory(),
    private val bufferSize: Int = 64 * 1024,
) : OnDemandDownloader {

    override fun download(request: OnDemandDownloader.Request, onProgress: (OnDemandDownloader.Progress) -> Unit): OnDemandDownloader.Outcome {
        val target = request.targetFile
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        val existing = if (part.isFile) part.length() else 0L
        val resumeFrom = if (existing > 0L) existing else null

        val connection = try {
            factory.open(request.url, resumeFrom, request.headers)
        } catch (e: IOException) {
            return OnDemandDownloader.Outcome.Failure("cannot open ${request.url}: ${e.message}", e)
        }

        connection.use { conn ->
            val resumed = resumeFrom != null && conn.responseCode == HTTP_PARTIAL
            val append = resumed
            if (!append) part.delete()
            var total = conn.contentLength
            if (total > 0) total += if (append) existing else 0L

            try {
                var written = if (append) existing else 0L
                // The write happens in this block; the file is CLOSED before the checks
                // and the rename below. Renaming (or replacing) a file that a stream
                // still holds open fails on Windows, which is where this runs.
                conn.stream().use { input ->
                    // APPEND when resuming: `File.outputStream()` truncates, which would
                    // silently drop the bytes a previous attempt already downloaded.
                    java.io.FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(bufferSize)
                        while (true) {
                            if (request.isCancelled()) return OnDemandDownloader.Outcome.Cancelled
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(OnDemandDownloader.Progress(written, total))
                        }
                        output.flush()
                    }
                }
                val expected = request.expectedBytes
                if (expected != null && written != expected) {
                    part.delete()
                    return OnDemandDownloader.Outcome.Failure(
                        "truncated download: got $written bytes, expected $expected"
                    )
                }
                if (target.exists() && !target.delete()) {
                    return OnDemandDownloader.Outcome.Failure("cannot replace ${target.name}")
                }
                if (!part.renameTo(target)) {
                    // Some filesystems refuse renameTo across handles; fall back to a copy.
                    return runCatching {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                        OnDemandDownloader.Outcome.Success(target, written, resumed)
                    }.getOrElse {
                        OnDemandDownloader.Outcome.Failure("cannot move ${part.name} into place", it)
                    }
                }
                return OnDemandDownloader.Outcome.Success(target, written, resumed)
            } catch (e: IOException) {
                return OnDemandDownloader.Outcome.Failure("download failed: ${e.message}", e)
            }
        }
    }

    private companion object {
        const val HTTP_PARTIAL = 206
    }
}

/** Narrow HTTP abstraction so the download loop can be tested without sockets. */
interface ConnectionFactory {
    fun open(url: String, rangeStart: Long?, headers: Map<String, String>): Connection
}

interface Connection : Closeable {
    val responseCode: Int
    val contentLength: Long
    fun stream(): InputStream
}

class HttpUrlConnectionFactory(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 60_000) : ConnectionFactory {
    override fun open(url: String, rangeStart: Long?, headers: Map<String, String>): Connection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        if (rangeStart != null) conn.setRequestProperty("Range", "bytes=$rangeStart-")
        return object : Connection {
            override val responseCode: Int get() = conn.responseCode
            override val contentLength: Long get() = conn.contentLengthLong
            override fun stream(): InputStream = conn.inputStream
            override fun close() {
                conn.disconnect()
            }
        }
    }
}
