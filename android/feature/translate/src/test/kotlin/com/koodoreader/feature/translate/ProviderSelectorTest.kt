package com.koodoreader.feature.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSelectorTest {

    private val providers = listOf(GoogleTranslateProvider(), MicrosoftTranslateProvider(), DeepLTranslateProvider())

    @Test
    fun `defaults to the first registered provider`() {
        val selector = ProviderSelector(providers)

        assertEquals(TranslationSourceId.GOOGLE, selector.active)
        assertEquals(TranslationSourceId.GOOGLE, selector.activeProvider().id)
    }

    @Test
    fun `an unknown initial source falls back to the first provider`() {
        val selector = ProviderSelector(providers, initialActive = TranslationSourceId.DEEPL)

        assertEquals(TranslationSourceId.DEEPL, selector.active)
        assertTrue(ProviderSelector(listOf(GoogleTranslateProvider()), TranslationSourceId.DEEPL).active == TranslationSourceId.GOOGLE)
    }

    @Test
    fun `select only accepts registered providers`() {
        val selector = ProviderSelector(listOf(GoogleTranslateProvider()))

        assertTrue(selector.select(TranslationSourceId.GOOGLE))
        assertFalse(selector.select(TranslationSourceId.DEEPL))
        assertEquals(TranslationSourceId.GOOGLE, selector.active)
        assertFalse(selector.isRegistered(TranslationSourceId.DEEPL))
    }

    @Test
    fun `next cycles through the registration order`() {
        val selector = ProviderSelector(providers)

        assertEquals(TranslationSourceId.MICROSOFT, selector.next())
        assertEquals(TranslationSourceId.DEEPL, selector.next())
        assertEquals(TranslationSourceId.GOOGLE, selector.next())
    }

    @Test
    fun `resolve prefers the active source when it is configured`() {
        val selector = ProviderSelector(providers, TranslationSourceId.MICROSOFT)

        assertEquals(
            TranslationSourceId.MICROSOFT,
            selector.resolve(setOf(TranslationSourceId.MICROSOFT, TranslationSourceId.DEEPL)),
        )
    }

    @Test
    fun `resolve falls back to the first configured source`() {
        val selector = ProviderSelector(providers, TranslationSourceId.DEEPL)

        assertEquals(
            TranslationSourceId.MICROSOFT,
            selector.resolve(setOf(TranslationSourceId.MICROSOFT)),
        )
    }

    @Test
    fun `resolve keeps the active source when nothing is configured`() {
        val selector = ProviderSelector(providers, TranslationSourceId.DEEPL)

        assertEquals(TranslationSourceId.DEEPL, selector.resolve(emptySet()))
        assertTrue(selector.usable(emptySet()).isEmpty())
    }

    @Test
    fun `usable keeps registration order`() {
        val selector = ProviderSelector(providers)

        val usable = selector.usable(setOf(TranslationSourceId.DEEPL, TranslationSourceId.GOOGLE))

        assertEquals(listOf(TranslationSourceId.GOOGLE, TranslationSourceId.DEEPL), usable.map { it.id })
    }

    @Test
    fun `provider ids keep the desktop plugin keys`() {
        assertEquals("google-translate-plugin", TranslationSourceId.GOOGLE.pluginKey)
        assertEquals("azure-translate-plugin", TranslationSourceId.MICROSOFT.pluginKey)
        assertEquals("deepl-translate-plugin", TranslationSourceId.DEEPL.pluginKey)
        assertEquals(TranslationSourceId.MICROSOFT, TranslationSourceId.fromPluginKey("azure-translate-plugin"))
        assertEquals(null, TranslationSourceId.fromPluginKey("baidu-general-translate-plugin"))
    }
}
