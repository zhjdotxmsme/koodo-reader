package com.koodoreader.reader.epubhost

/**
 * JS `onResult(callId, payload, error)`: success uses `null` or `''`.
 * Android's `@JavascriptInterface` turns `''` into a non-null empty String,
 * which must not be treated as a failure.
 */
object FoliateJsResult {
    fun errorOrNull(error: String?): String? = error?.takeIf { it.isNotBlank() }
}
