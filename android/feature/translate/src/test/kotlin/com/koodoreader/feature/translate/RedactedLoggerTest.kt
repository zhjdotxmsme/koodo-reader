package com.koodoreader.feature.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLAUDE.md hard rule / P6 acceptance #2:
 * **a full API token must never reach an info-level log line.**
 *
 * These assertions run against the *production* logging path
 * (`CredentialsStore.logger` -> `RedactedLogger` -> delegate), not against a
 * test-only helper: the store builds the redacting logger itself, so any caller
 * that logs through the store is covered.
 */
class RedactedLoggerTest {

    private val token = TestTokens.GOOGLE

    @Test
    fun `info log line from CredentialsStore never contains the full token`() {
        val sink = RecordingLogger()
        val store = CredentialsStore(InMemorySecretStore(), sink)

        store.save(
            TranslationSourceId.GOOGLE,
            ProviderCredentials(apiKey = token, endpoint = "", region = ""),
        )
        // A careless call site interpolates the key into an info line; the store's
        // logger still has to strip it.
        store.logger.info("google request url=https://translation.googleapis.com/language/translate/v2?key=$token")
        store.logger.warn("retrying with apiKey=$token")
        store.logger.error("provider failed, Authorization: Bearer $token", null)

        assertTrue("sink captured nothing - assertion would be vacuous", sink.entries.isNotEmpty())
        assertTrue(
            "the save() info line must actually be emitted",
            sink.infos.any { it.contains("translation credentials saved") },
        )
        assertFalse("full token leaked into a log line:\n${sink.text()}", sink.text().contains(token))
        assertTrue(sink.text().contains(TokenRedaction.MASK))
        // The masked form (last 4 chars only) is what operators get to see.
        assertTrue(store.maskedApiKey(TranslationSourceId.GOOGLE).endsWith(token.takeLast(4)))
        assertFalse(store.describe(TranslationSourceId.GOOGLE).contains(token))
    }

    @Test
    fun `token loaded from storage is redacted even before this process saved it`() {
        val sink = RecordingLogger()
        val seeded = InMemorySecretStore(
            mapOf(CredentialsStore.fieldKey(TranslationSourceId.DEEPL, CredentialsStore.FIELD_API_KEY) to TestTokens.DEEPL),
        )
        val store = CredentialsStore(seeded, sink)

        store.load(TranslationSourceId.DEEPL)
        store.logger.info("deepl header Authorization: DeepL-Auth-Key ${TestTokens.DEEPL}")

        assertTrue(sink.entries.isNotEmpty())
        assertFalse(sink.text().contains(TestTokens.DEEPL))
    }

    @Test
    fun `shape based redaction catches tokens the app has never seen`() {
        val sink = RecordingLogger()
        val logger = RedactedLogger(sink)

        logger.info("apiKey=unknown-key-1234567890")
        logger.info("Authorization: Bearer bearer-value-abcdef")
        logger.info("openai key sk-proj-abcdefghijklmnop")
        logger.info("google key AIzaSyD-UNSEEN-abcdefghijklmnop1234")
        logger.info("url=https://example.test/translate?api_key=qwerty9876543210&to=en")

        val output = sink.text()
        assertFalse(output.contains("unknown-key-1234567890"))
        assertFalse(output.contains("bearer-value-abcdef"))
        assertFalse(output.contains("sk-proj-abcdefghijklmnop"))
        assertFalse(output.contains("AIzaSyD-UNSEEN-abcdefghijklmnop1234"))
        assertFalse(output.contains("qwerty9876543210"))
        assertTrue(sink.infos.size == 5)
    }

    @Test
    fun `non secret text is preserved`() {
        val sink = RecordingLogger()
        val logger = RedactedLogger(sink)

        logger.info("translation history recorded: provider=google-translate-plugin target=zh-CN sourceChars=42")

        assertEquals(
            "INFO translation history recorded: provider=google-translate-plugin target=zh-CN sourceChars=42",
            sink.entries.single(),
        )
    }

    @Test
    fun `mask keeps only a short suffix`() {
        assertEquals("", TokenRedaction.mask(null))
        assertEquals("", TokenRedaction.mask(""))
        assertEquals("***", TokenRedaction.mask("abc"))
        assertEquals("***cdef", TokenRedaction.mask("abcdef"))
    }

    @Test
    fun `redacted request description hides header and query credentials`() {
        val request = HttpRequest(
            method = "POST",
            url = "https://translation.googleapis.com/language/translate/v2?key=${TestTokens.GOOGLE}&target=zh-CN",
            headers = mapOf("Authorization" to "DeepL-Auth-Key ${TestTokens.DEEPL}", "Content-Type" to "application/json"),
            body = "{\"q\":\"secret book text\"}",
        )

        val description = request.redactedDescription()

        assertFalse(description.contains(TestTokens.GOOGLE))
        assertFalse(description.contains(TestTokens.DEEPL))
        assertFalse("request body content must not be logged", description.contains("secret book text"))
        assertTrue(description.contains("target=zh-CN"))
        assertTrue(description.contains("Content-Type:application/json"))
    }
}
