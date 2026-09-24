package com.koodoreader.feature.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsStoreTest {

    private val sink = RecordingLogger()

    @Test
    fun `save and load round trips every field`() {
        val store = CredentialsStore(InMemorySecretStore(), sink)
        val credentials = ProviderCredentials(
            apiKey = TestTokens.MICROSOFT,
            endpoint = "https://api.cognitive.microsofttranslator.com",
            region = "eastasia",
            plan = ApiPlan.PRO,
        )

        store.save(TranslationSourceId.MICROSOFT, credentials)

        assertEquals(credentials, store.load(TranslationSourceId.MICROSOFT))
        assertTrue(store.isConfigured(TranslationSourceId.MICROSOFT))
        assertEquals(setOf(TranslationSourceId.MICROSOFT), store.configuredSources())
    }

    @Test
    fun `values are stored under desktop-parity namespaced keys`() {
        val secrets = InMemorySecretStore()
        val store = CredentialsStore(secrets, sink)

        store.save(TranslationSourceId.DEEPL, ProviderCredentials(apiKey = TestTokens.DEEPL, plan = ApiPlan.FREE))

        assertTrue(
            secrets.keys().containsAll(
                listOf(
                    "p6.translate.cred.deepl-translate-plugin.apiKey",
                    "p6.translate.cred.deepl-translate-plugin.endpoint",
                    "p6.translate.cred.deepl-translate-plugin.region",
                    "p6.translate.cred.deepl-translate-plugin.plan",
                ),
            ),
        )
        assertEquals("free", secrets.getString("p6.translate.cred.deepl-translate-plugin.plan"))
        assertEquals(ApiPlan.FREE, store.load(TranslationSourceId.DEEPL)?.plan)
    }

    @Test
    fun `unknown source returns null and require falls back to empty credentials`() {
        val store = CredentialsStore(InMemorySecretStore(), sink)

        assertNull(store.load(TranslationSourceId.GOOGLE))
        assertFalse(store.require(TranslationSourceId.GOOGLE).hasApiKey())
        assertEquals("google-translate-plugin: not configured", store.describe(TranslationSourceId.GOOGLE))
    }

    @Test
    fun `clear removes every field and forgets the token`() {
        val secrets = InMemorySecretStore()
        val store = CredentialsStore(secrets, sink)
        store.save(TranslationSourceId.GOOGLE, ProviderCredentials(apiKey = TestTokens.GOOGLE))

        store.clear(TranslationSourceId.GOOGLE)

        assertNull(store.load(TranslationSourceId.GOOGLE))
        assertTrue(store.configuredSources().isEmpty())
        assertTrue(store.knownTokens().isEmpty())
        assertTrue(secrets.keys().isEmpty())
    }

    @Test
    fun `knownTokens exposes stored secrets for redaction but never logs them`() {
        val store = CredentialsStore(InMemorySecretStore(), sink)
        store.save(TranslationSourceId.DEEPL, ProviderCredentials(apiKey = TestTokens.DEEPL))

        assertTrue(store.knownTokens().contains(TestTokens.DEEPL))
        assertFalse(sink.text().contains(TestTokens.DEEPL))
        assertTrue(store.maskedApiKey(TranslationSourceId.DEEPL).startsWith(TokenRedaction.MASK))
    }

    @Test
    fun `configuredSources reflects only sources with a key`() {
        val store = CredentialsStore(InMemorySecretStore(), sink)
        store.save(TranslationSourceId.GOOGLE, ProviderCredentials(apiKey = TestTokens.GOOGLE))
        store.save(TranslationSourceId.DEEPL, ProviderCredentials(apiKey = "   "))

        assertEquals(setOf(TranslationSourceId.GOOGLE), store.configuredSources())
    }

    @Test
    fun `describe never contains the raw key`() {
        val store = CredentialsStore(InMemorySecretStore(), sink)
        store.save(
            TranslationSourceId.GOOGLE,
            ProviderCredentials(apiKey = TestTokens.GOOGLE, endpoint = "https://proxy.test/v2"),
        )

        val description = store.describe(TranslationSourceId.GOOGLE)

        assertNotNull(description)
        assertFalse(description.contains(TestTokens.GOOGLE))
        assertTrue(description.contains("https://proxy.test/v2"))
    }
}
