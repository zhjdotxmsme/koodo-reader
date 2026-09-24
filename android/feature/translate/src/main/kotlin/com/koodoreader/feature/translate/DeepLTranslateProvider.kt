package com.koodoreader.feature.translate

/**
 * DeepL translate API v2.
 *
 * Desktop reference: `src/utils/plugins/renderer/translation/deeplTranslate.ts`
 * (plugin key `deepl-translate-plugin`) — the endpoint depends on the plan
 * (`keyType === "free"` -> api-free.deepl.com), the key travels in the
 * `Authorization: DeepL-Auth-Key <key>` header, the payload is
 * `{ text: [...], source_lang, target_lang }` with **upper-case** language tags,
 * and the answer is `translations[0].text` (plus `detected_source_language`).
 */
class DeepLTranslateProvider : TranslationProvider {

    override val id: TranslationSourceId = TranslationSourceId.DEEPL

    override val defaultEndpoint: String = PRO_ENDPOINT

    override val credentialFields: List<CredentialField> = listOf(
        CredentialField(
            key = "apiKey",
            label = "DeepL API key",
            required = true,
            secret = true,
            hint = "Authentication key from the DeepL account page",
        ),
        CredentialField(
            key = "keyType",
            label = "Plan",
            required = false,
            secret = false,
            hint = "free -> api-free.deepl.com, otherwise api.deepl.com",
        ),
        CredentialField(
            key = "endpoint",
            label = "Endpoint",
            required = false,
            secret = false,
            hint = "override only for proxies",
        ),
    )

    /** Plan-aware default endpoint, also used by the settings screen. */
    fun endpointFor(credentials: ProviderCredentials): String {
        if (credentials.endpoint.isNotBlank()) {
            return credentials.endpoint
        }
        return when (credentials.plan) {
            ApiPlan.FREE -> FREE_ENDPOINT
            ApiPlan.PRO -> PRO_ENDPOINT
        }
    }

    override fun buildRequest(
        text: String,
        from: String,
        to: String,
        credentials: ProviderCredentials,
    ): HttpRequest {
        val fields = mutableListOf<Pair<String, Any?>>(
            "text" to listOf(text),
            "target_lang" to LanguageCodes.deeplTag(to),
        )
        if (!LanguageCodes.isAuto(from)) {
            fields += "source_lang" to LanguageCodes.deeplTag(from)
        }
        return HttpRequest(
            method = "POST",
            url = endpointFor(credentials),
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "$AUTH_SCHEME ${credentials.apiKey}",
            ),
            body = MiniJson.body(*fields.toTypedArray()),
        )
    }

    override fun parseResponse(response: HttpResponse): TranslationOutcome {
        if (!response.isSuccess) {
            return TranslationOutcome.Failure(id, FailureReason.HTTP_STATUS, "DeepL returned HTTP ${response.statusCode}")
        }
        val json = MiniJson.parse(response.body)
            ?: return TranslationOutcome.Failure(id, FailureReason.MALFORMED_RESPONSE, "DeepL returned a non-JSON body")
        val translated = json.stringAt("translations", 0, "text")
            ?: return TranslationOutcome.Failure(
                id,
                FailureReason.MALFORMED_RESPONSE,
                "DeepL response has no translations[0].text",
            )
        return TranslationOutcome.Success(
            text = translated,
            provider = id,
            detectedSourceLanguage = json.stringAt("translations", 0, "detected_source_language"),
        )
    }

    companion object {
        const val FREE_ENDPOINT = "https://api-free.deepl.com/v2/translate"
        const val PRO_ENDPOINT = "https://api.deepl.com/v2/translate"
        const val AUTH_SCHEME = "DeepL-Auth-Key"
    }
}
