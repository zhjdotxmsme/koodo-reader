package com.koodoreader.feature.translate

/**
 * Google Cloud Translation v2.
 *
 * Desktop reference: `src/utils/plugins/renderer/translation/googleTranslate.ts`
 * (plugin key `google-translate-plugin`) — API key travels as the `key` query
 * parameter, the payload carries `q` / `target` / `format`, and the response is
 * HTML-escaped (`&quot;` etc.), which the desktop builds decode through a
 * `<textarea>`; here [HtmlEntities.unescape] does the same on the JVM.
 */
class GoogleTranslateProvider : TranslationProvider {

    override val id: TranslationSourceId = TranslationSourceId.GOOGLE

    override val defaultEndpoint: String = DEFAULT_ENDPOINT

    override val credentialFields: List<CredentialField> = listOf(
        CredentialField(
            key = "apiKey",
            label = "Google Cloud API key",
            required = true,
            secret = true,
            hint = "Cloud Translation API v2 key; stored encrypted, never logged",
        ),
        CredentialField(
            key = "endpoint",
            label = "Endpoint",
            required = false,
            secret = false,
            hint = DEFAULT_ENDPOINT,
        ),
    )

    override fun buildRequest(
        text: String,
        from: String,
        to: String,
        credentials: ProviderCredentials,
    ): HttpRequest {
        val endpoint = credentials.endpoint.ifBlank { defaultEndpoint }
        val target = to.ifBlank { DEFAULT_TARGET }
        val fields = mutableListOf<Pair<String, Any?>>(
            "q" to text,
            "target" to target,
            "format" to "text",
        )
        if (!LanguageCodes.isAuto(from)) {
            fields += "source" to from
        }
        return HttpRequest(
            method = "POST",
            url = endpoint + UrlEncoding.query("key" to credentials.apiKey),
            headers = mapOf("Content-Type" to "application/json"),
            body = MiniJson.body(*fields.toTypedArray()),
        )
    }

    override fun parseResponse(response: HttpResponse): TranslationOutcome {
        if (!response.isSuccess) {
            return TranslationOutcome.Failure(id, FailureReason.HTTP_STATUS, "Google returned HTTP ${response.statusCode}")
        }
        val json = MiniJson.parse(response.body)
            ?: return TranslationOutcome.Failure(id, FailureReason.MALFORMED_RESPONSE, "Google returned a non-JSON body")
        val translated = json.stringAt("data", "translations", 0, "translatedText")
            ?: return TranslationOutcome.Failure(
                id,
                FailureReason.MALFORMED_RESPONSE,
                "Google response has no data.translations[0].translatedText",
            )
        return TranslationOutcome.Success(
            text = HtmlEntities.unescape(translated),
            provider = id,
            detectedSourceLanguage = json.stringAt("data", "translations", 0, "detectedSourceLanguage"),
        )
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://translation.googleapis.com/language/translate/v2"
        const val DEFAULT_TARGET = "zh-CN"
    }
}
