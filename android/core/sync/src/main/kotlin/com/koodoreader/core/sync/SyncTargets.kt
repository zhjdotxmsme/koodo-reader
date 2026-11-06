package com.koodoreader.core.sync

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

/** Writes one snapshot file on the local filesystem. */
class LocalDirectorySyncTarget(private val file: File) : SyncTarget {
    override fun read(): InputStream? = if (file.isFile) file.inputStream() else null

    override fun write(snapshot: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(snapshot)
        if (!tmp.renameTo(file)) {
            file.writeBytes(snapshot)
            tmp.delete()
        }
    }
}

/**
 * WebDAV object. GET 404 yields null; other non-success statuses throw.
 * Credentials are optional Basic auth.
 */
class WebDavSyncTarget(
    private val url: String,
    private val username: String = "",
    private val password: String = "",
) : SyncTarget {
    override fun read(): InputStream? = HttpBlob.exchange(
        url = url,
        method = "GET",
        body = null,
        headers = basicAuth(username, password),
    )

    override fun write(snapshot: ByteArray) {
        HttpBlob.exchange(
            url = url,
            method = "PUT",
            body = snapshot,
            headers = basicAuth(username, password),
        )
    }
}

/**
 * S3 object URL. Pass a presigned URL, or an [headers] map that already
 * contains `Authorization` (SigV4). Signing stays with the caller so this
 * module does not embed AWS credentials.
 */
class S3SyncTarget(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) : SyncTarget {
    override fun read(): InputStream? = HttpBlob.exchange(url, "GET", null, headers)

    override fun write(snapshot: ByteArray) {
        HttpBlob.exchange(url, "PUT", snapshot, headers)
    }
}

internal object HttpBlob {
    fun exchange(
        url: String,
        method: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): InputStream? {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) error("$method $code")
            if (method == "GET") {
                val bytes = conn.inputStream.use { it.readBytes() }
                return ByteArrayInputStream(bytes)
            }
            return null
        } finally {
            conn.disconnect()
        }
    }
}

private fun basicAuth(username: String, password: String): Map<String, String> {
    if (username.isEmpty()) return emptyMap()
    val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    return mapOf("Authorization" to "Basic $token")
}
