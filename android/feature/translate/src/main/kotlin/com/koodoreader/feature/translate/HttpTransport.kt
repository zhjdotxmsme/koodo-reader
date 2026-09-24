package com.koodoreader.feature.translate

/** Envelope of a single outbound HTTP call. Transport-agnostic on purpose. */
enum class HttpBodyKind { NONE, JSON }

/**
 * One outbound request. Instances are plain data so unit tests can assert the
 * exact wire shape (URL, query string, headers, body) without a network stack.
 *
 * Logging rule: never log [headers] or [body] directly. Use
 * [redactedDescription] — it masks sensitive headers and sensitive query
 * parameters (CLAUDE.md: "不要将令牌、密码…记录到 info 级别日志").
 */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val bodyKind: HttpBodyKind = if (body == null) HttpBodyKind.NONE else HttpBodyKind.JSON,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** `POST https://…?key=*** headers=[Authorization:***] body=42 chars` */
    fun redactedDescription(): String {
        val headerText = headers.entries.joinToString(", ") { (name, value) ->
            "$name:${if (SensitiveHeaders.isSensitive(name)) TokenRedaction.MASK else value}"
        }
        val bodyText = body?.let { "${it.length} chars" } ?: "<none>"
        return "$method ${redactUrl(url)} headers=[$headerText] body=$bodyText"
    }

    private fun redactUrl(raw: String): String =
        SENSITIVE_QUERY.replace(raw) { match -> "${match.groupValues[1]}${TokenRedaction.MASK}" }

    private companion object {
        /** `key=`, `api_key=`, `access_token=`, … in a query string or fragment. */
        val SENSITIVE_QUERY = Regex(
            "(?i)([?&#](?:key|api[-_]?key|apikey|access[-_]?token|auth[-_]?token|token|subscription[-_]?key|password)=)[^&#\\s]+",
        )
    }
}

data class HttpResponse(val statusCode: Int, val body: String) {
    val isSuccess: Boolean get() = statusCode in 200..299
}

/** Raised by [HttpTransport] implementations on DNS/TLS/timeout/IO failure. */
class HttpTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Single seam between provider logic and the network. Production supplies an
 * OkHttp/HttpURLConnection-backed implementation from `:app`; unit tests supply
 * a fake that returns canned [HttpResponse]s (or throws [HttpTransportException]).
 */
interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

object SensitiveHeaders {
    private val NAMES = setOf(
        "authorization",
        "proxy-authorization",
        "ocp-apim-subscription-key",
        "x-api-key",
        "api-key",
        "x-goog-api-key",
        "cookie",
        "set-cookie",
    )

    fun isSensitive(name: String): Boolean = NAMES.contains(name.trim().lowercase())
}
