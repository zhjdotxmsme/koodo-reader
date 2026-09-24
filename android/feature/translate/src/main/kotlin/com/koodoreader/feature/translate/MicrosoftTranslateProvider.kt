package com.koodoreader.feature.translate

/**
 * Microsoft (Azure) Translator v3.
 *
 * Desktop reference: `src/utils/plugins/renderer/translation/azureTranslate.ts`
 * (plugin key `azure-translate-plugin`) — subscription key in the
 * `Ocp-Apim-Subscription-Key` header, optional `Ocp-Apim-Subscription-Region`,
 * `api-version=3.0` + `to` (and `from` when known) as query parameters, payload
 * `[{ "text": ... }]`, answer `[0].translations[0].text`.
 */
class MicrosoftTranslateProvider : TranslationProvider {

    override val id: TranslationSourceId = TranslationSourceId.MICROSOFT

    override val defaultEndpoint: String = DEFAULT_ENDPOINT

    override val credentialFields: List<CredentialField> = listOf(
        CredentialField(
            key = "apiKey",
            label = "Subscription key",
            required = true,
            secret = true,
            hint = "Azure Translator resource key",
        ),
        CredentialField(
            key = "region",
            label = "Region",
            required = false,
            secret = false,
            hint = "e.g. eastasia (required by multi-service resources)",
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
        val endpoint = credentials.endpoint.ifBlank { defaultEndpoint }.trimEnd('/')
        val url = endpoint + "/translate" + UrlEncoding.query(
            "api-version" to API_VERSION,
            "to" to to,
            "from" to if (LanguageCodes.isAuto(from)) null else from,
        )
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers[SUBSCRIPTION_KEY_HEADER] = credentials.apiKey
        if (credentials.region.isNotBlank()) {
            headers[SUBSCRIPTION_REGION_HEADER] = credentials.region
        }
        return HttpRequest(
            method = "POST",
            url = url,
            headers = headers,
            body = "[{" + MiniJson.quote("text") + ":" + MiniJson.quote(text) + "}]",
        )
    }

    override fun parseResponse(response: HttpResponse): TranslationOutcome {
        if (!response.isSuccess) {
            return TranslationOutcome.Failure(
                id,
                FailureReason.HTTP_STATUS,
                "Microsoft returned HTTP ${response.statusCode}",
            )
        }
        val json = MiniJson.parse(response.body)
            ?: return TranslationOutcome.Failure(id, FailureReason.MALFORMED_RESPONSE, "Microsoft returned a non-JSON body")
        val translated = json.stringAt(0, "translations", 0, "text")
            ?: return TranslationOutcome.Failure(
                id,
                FailureReason.MALFORMED_RESPONSE,
                "Microsoft response has no [0].translations[0].text",
            )
        return TranslationOutcome.Success(
            text = translated,
            provider = id,
            detectedSourceLanguage = json.stringAt(0, "detectedLanguage", "language"),
        )
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.cognitive.microsofttranslator.com"
        const val API_VERSION = "3.0"
        const val SUBSCRIPTION_KEY_HEADER = "Ocp-Apim-Subscription-Key"
        const val SUBSCRIPTION_REGION_HEADER = "Ocp-Apim-Subscription-Region"
    }
}
