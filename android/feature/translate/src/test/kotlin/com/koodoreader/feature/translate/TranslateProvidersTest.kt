package com.koodoreader.feature.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format parity with the desktop plugins
 * (`src/utils/plugins/renderer/translation/googleTranslate.ts`,
 * `azureTranslate.ts`, `deeplTranslate.ts`): request shape, response parsing,
 * error mapping and "no token in any log/description".
 */
class TranslateProvidersTest {

    private val google = GoogleTranslateProvider()
    private val microsoft = MicrosoftTranslateProvider()
    private val deepl = DeepLTranslateProvider()

    // ---------------------------------------------------------------- Google

    @Test
    fun `google builds the v2 request like the desktop plugin`() {
        val request = google.buildRequest(
            text = "Hello world",
            from = "en",
            to = "zh-CN",
            credentials = ProviderCredentials(apiKey = TestTokens.GOOGLE),
        )

        assertEquals("POST", request.method)
        assertEquals(
            "https://translation.googleapis.com/language/translate/v2?key=${TestTokens.GOOGLE}",
            request.url,
        )
        assertEquals("application/json", request.header("Content-Type"))
        assertTrue(request.body.orEmpty().contains("\"q\":\"Hello world\""))
        assertTrue(request.body.orEmpty().contains("\"target\":\"zh-CN\""))
        assertTrue(request.body.orEmpty().contains("\"format\":\"text\""))
        assertTrue(request.body.orEmpty().contains("\"source\":\"en\""))
        assertFalse(request.redactedDescription().contains(TestTokens.GOOGLE))
    }

    @Test
    fun `google omits source when the language is automatic`() {
        val request = google.buildRequest("Hi", "auto", "zh-CN", ProviderCredentials(apiKey = "k"))

        assertFalse(request.body.orEmpty().contains("\"source\""))
        assertFalse(request.body.orEmpty().contains("\"source\""))
    }

    @Test
    fun `google decodes html entities from the response`() {
        val transport = FakeHttpTransport.returning(
            """{"data":{"translations":[{"translatedText":"你好 &amp; 欢迎 &#39;朋友&#39;","detectedSourceLanguage":"en"}]}}""",
        )

        val outcome = runBlocking {
            google.translate("Hello & welcome", "en", "zh-CN", ProviderCredentials(apiKey = TestTokens.GOOGLE), transport)
        }

        assertTrue(outcome is TranslationOutcome.Success)
        val success = outcome as TranslationOutcome.Success
        assertEquals("你好 & 欢迎 '朋友'", success.text)
        assertEquals("en", success.detectedSourceLanguage)
        assertEquals(TranslationSourceId.GOOGLE, success.provider)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `google reports missing credentials without calling the network`() {
        val transport = FakeHttpTransport.returning("{}")

        val outcome = runBlocking { google.translate("Hi", "en", "zh-CN", ProviderCredentials(), transport) }

        assertEquals(
            TranslationOutcome.Failure(TranslationSourceId.GOOGLE, FailureReason.MISSING_CREDENTIALS, "missing API key for Google Translate"),
            outcome,
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `identical languages short circuit before the network call`() {
        val transport = FakeHttpTransport.returning("{}")

        val outcome = runBlocking {
            google.translate("你好", "zh-CN", "zh-CN", ProviderCredentials(apiKey = TestTokens.GOOGLE), transport)
        }

        assertEquals("你好", (outcome as TranslationOutcome.Success).text)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `empty selection is rejected`() {
        val outcome = runBlocking {
            google.translate("   ", "en", "zh-CN", ProviderCredentials(apiKey = "k"), FakeHttpTransport.returning("{}"))
        }

        assertEquals(FailureReason.EMPTY_INPUT, (outcome as TranslationOutcome.Failure).reason)
    }

    @Test
    fun `google maps non-2xx and malformed bodies to failures`() {
        val forbidden = google.parseResponse(HttpResponse(403, """{"error":{"code":403}}"""))
        val malformed = google.parseResponse(HttpResponse(200, "<html>nope</html>"))
        val missingField = google.parseResponse(HttpResponse(200, """{"data":{"translations":[]}}"""))

        assertEquals(FailureReason.HTTP_STATUS, (forbidden as TranslationOutcome.Failure).reason)
        assertEquals(FailureReason.MALFORMED_RESPONSE, (malformed as TranslationOutcome.Failure).reason)
        assertEquals(FailureReason.MALFORMED_RESPONSE, (missingField as TranslationOutcome.Failure).reason)
    }

    @Test
    fun `transport errors become NETWORK failures`() {
        val outcome = runBlocking {
            google.translate(
                "Hi",
                "en",
                "zh-CN",
                ProviderCredentials(apiKey = TestTokens.GOOGLE),
                FakeHttpTransport.failing("timeout"),
            )
        }

        val failure = outcome as TranslationOutcome.Failure
        assertEquals(FailureReason.NETWORK, failure.reason)
        assertEquals("timeout", failure.detail)
    }

    // ------------------------------------------------------------- Microsoft

    @Test
    fun `microsoft builds the v3 request like the desktop plugin`() {
        val request = microsoft.buildRequest(
            text = "Hi there",
            from = "en",
            to = "zh-CN",
            credentials = ProviderCredentials(apiKey = TestTokens.MICROSOFT, region = "eastasia"),
        )

        assertEquals(
            "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=zh-CN&from=en",
            request.url,
        )
        assertEquals(TestTokens.MICROSOFT, request.header("Ocp-Apim-Subscription-Key"))
        assertEquals("eastasia", request.header("Ocp-Apim-Subscription-Region"))
        assertEquals("""[{"text":"Hi there"}]""", request.body)
        assertFalse(request.redactedDescription().contains(TestTokens.MICROSOFT))
    }

    @Test
    fun `microsoft omits the from parameter and the region header when not set`() {
        val request = microsoft.buildRequest("Hi", "auto", "zh-CN", ProviderCredentials(apiKey = "k"))

        assertEquals(
            "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=zh-CN",
            request.url,
        )
        assertTrue(request.headers.keys.none { it.equals(MicrosoftTranslateProvider.SUBSCRIPTION_REGION_HEADER, true) })
    }

    @Test
    fun `microsoft honours a custom endpoint and parses the translations array`() {
        val request = microsoft.buildRequest(
            "Hi",
            "en",
            "zh-CN",
            ProviderCredentials(apiKey = "k", endpoint = "https://proxy.test/translator/"),
        )
        assertEquals("https://proxy.test/translator/translate?api-version=3.0&to=zh-CN&from=en", request.url)

        val outcome = microsoft.parseResponse(
            HttpResponse(
                200,
                """[{"detectedLanguage":{"language":"en","score":1.0},"translations":[{"text":"你好","to":"zh-CN"}]}]""",
            ),
        )

        assertEquals("你好", (outcome as TranslationOutcome.Success).text)
        assertEquals("en", outcome.detectedSourceLanguage)
    }

    @Test
    fun `microsoft maps 401 to HTTP_STATUS`() {
        val outcome = microsoft.parseResponse(HttpResponse(401, """{"error":{"code":401001}}"""))

        assertEquals(FailureReason.HTTP_STATUS, (outcome as TranslationOutcome.Failure).reason)
    }

    // ----------------------------------------------------------------- DeepL

    @Test
    fun `deepl free plan uses the free endpoint and upper-case tags`() {
        val request = deepl.buildRequest(
            text = "Hello",
            from = "en",
            to = "zh-CN",
            credentials = ProviderCredentials(apiKey = TestTokens.DEEPL, plan = ApiPlan.FREE),
        )

        assertEquals("https://api-free.deepl.com/v2/translate", request.url)
        assertEquals("DeepL-Auth-Key ${TestTokens.DEEPL}", request.header("Authorization"))
        assertTrue(request.body.orEmpty().contains("\"text\":[\"Hello\"]"))
        assertTrue(request.body.orEmpty().contains("\"target_lang\":\"ZH\""))
        assertTrue(request.body.orEmpty().contains("\"source_lang\":\"EN\""))
        assertFalse(request.redactedDescription().contains(TestTokens.DEEPL))
    }

    @Test
    fun `deepl pro plan uses the paid endpoint and traditional chinese maps to ZH-HANT`() {
        val request = deepl.buildRequest(
            "Hello",
            "auto",
            "zh-TW",
            ProviderCredentials(apiKey = "k", plan = ApiPlan.PRO),
        )

        assertEquals("https://api.deepl.com/v2/translate", request.url)
        assertTrue(request.body.orEmpty().contains("\"target_lang\":\"ZH-HANT\""))
        assertFalse(request.body.orEmpty().contains("source_lang"))
    }

    @Test
    fun `deepl parses translations and detected source language`() {
        val outcome = deepl.parseResponse(
            HttpResponse(200, """{"translations":[{"detected_source_language":"EN","text":"你好"}]}"""),
        )

        val success = outcome as TranslationOutcome.Success
        assertEquals("你好", success.text)
        assertEquals("EN", success.detectedSourceLanguage)
    }

    @Test
    fun `deepl maps 456 quota errors to HTTP_STATUS`() {
        val outcome = deepl.parseResponse(HttpResponse(456, """{"message":"Quota exceeded"}"""))

        assertEquals(FailureReason.HTTP_STATUS, (outcome as TranslationOutcome.Failure).reason)
    }

    @Test
    fun `language tag helpers follow the desktop semantics`() {
        assertTrue(LanguageCodes.isAuto("auto"))
        assertTrue(LanguageCodes.isAuto("Automatic"))
        assertTrue(LanguageCodes.isAuto(""))
        assertTrue(LanguageCodes.sameLanguage("zh-CN", "zh-cn"))
        assertFalse(LanguageCodes.sameLanguage("auto", "zh-CN"))
        assertEquals("ZH", LanguageCodes.deeplTag("zh-CN"))
        assertEquals("ZH-HANT", LanguageCodes.deeplTag("zh-Hant"))
        assertEquals("EN-US", LanguageCodes.deeplTag("en-us"))
        assertEquals("a%20b%2Fc", UrlEncoding.encode("a b/c"))
    }
}
