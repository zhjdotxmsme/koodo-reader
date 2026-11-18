package com.koodoreader.reader.metadata

import com.koodoreader.feature.translate.HttpRequest
import com.koodoreader.feature.translate.HttpTransport
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal suspend fun httpGetText(
    transport: HttpTransport,
    url: String,
    headers: Map<String, String> = emptyMap(),
): String? {
    val response = transport.execute(
        HttpRequest(method = "GET", url = url, headers = headers),
    )
    return if (response.isSuccess) response.body else null
}

internal fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

internal const val METADATA_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) KoodoReader/1.0"