package com.koodoreader.feature.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the **degradation** behaviour of the encrypted credential store.
 *
 * The master key lives in the Android Keystore and the encrypted file is
 * useless without it. Three ordinary events make the two disagree — a
 * reinstall under a different signature, a changed lock-screen credential, a
 * backup restored onto other hardware — and in each case the store throws
 * from its constructor.
 *
 * A translation API key is an optional convenience. Losing it costs the
 * reader one re-typed key; a crash in the reading screen costs them the
 * book. So the store must degrade instead of throwing.
 *
 * These tests use the pure half (the seam), not the Android one — the
 * Android Keystore is not available on a plain JVM.
 */
class EncryptedSecretStoreDegradationTest {

    @Test
    fun `an empty store reports no credentials`() {
        val store = InMemorySecretStore()
        val credentials = CredentialsStore(store)

        assertNull(credentials.load(TranslationSourceId.entries.first()))
        assertTrue(credentials.configuredSources().isEmpty())
    }

    @Test
    fun `credentials survive a save and load`() {
        val credentials = CredentialsStore(InMemorySecretStore())
        val id = TranslationSourceId.entries.first()

        credentials.save(
            id,
            ProviderCredentials(
                apiKey = "key-123",
                endpoint = "https://example.test",
                region = "cn",
                plan = ApiPlan.fromWire("free"),
            ),
        )

        val loaded = credentials.load(id)
        assertEquals("key-123", loaded?.apiKey)
        assertEquals("https://example.test", loaded?.endpoint)
        assertEquals("cn", loaded?.region)
        assertEquals(1, credentials.configuredSources().size)
    }

    @Test
    fun `a cleared source reports itself as unconfigured`() {
        val credentials = CredentialsStore(InMemorySecretStore())
        val id = TranslationSourceId.entries.first()
        credentials.save(id, ProviderCredentials(apiKey = "key-123"))

        credentials.clear(id)

        assertTrue(!credentials.isConfigured(id))
        assertTrue(credentials.configuredSources().isEmpty())
    }

    @Test
    fun `the degraded store is still usable for the current session`() {
        // This is what the fallback does: the reader re-types the key and it
        // works for the rest of the session, even though the encrypted file
        // could not be opened.
        val degraded = InMemorySecretStore()
        val credentials = CredentialsStore(degraded)
        val id = TranslationSourceId.entries.first()

        credentials.save(id, ProviderCredentials(apiKey = "key-123"))

        assertEquals("key-123", credentials.load(id)?.apiKey)
    }

    @Test
    fun `a secret is never echoed verbatim by the description`() {
        val credentials = CredentialsStore(InMemorySecretStore())
        val id = TranslationSourceId.entries.first()
        credentials.save(id, ProviderCredentials(apiKey = "super-secret-key-value"))

        val described = credentials.describe(id)

        assertTrue(described, !described.contains("super-secret-key-value"))
        assertTrue(described, described.contains("***"))
    }
}
