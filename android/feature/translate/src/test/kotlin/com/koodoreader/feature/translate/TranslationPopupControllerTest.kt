package com.koodoreader.feature.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P6 acceptance #1: 划词翻译弹窗 + 多源切换. */
class TranslationPopupControllerTest {

    private val sink = RecordingLogger()
    private val secrets = InMemorySecretStore()
    private val store = CredentialsStore(secrets, sink)
    private val providers = listOf(GoogleTranslateProvider(), MicrosoftTranslateProvider(), DeepLTranslateProvider())
    private val selector = ProviderSelector(providers, TranslationSourceId.GOOGLE)
    private val dao = FakeTranslationHistoryDao()
    private val history = TranslationHistoryRepository(dao, sink, FakeClock())

    private val googleBody =
        """{"data":{"translations":[{"translatedText":"你好，世界","detectedSourceLanguage":"en"}]}}"""
    private val deeplBody = """{"translations":[{"detected_source_language":"EN","text":"你好，世界 (DeepL)"}]}"""

    private val transport = FakeHttpTransport { request ->
        if (request.url.contains("deepl.com")) {
            HttpResponse(200, deeplBody)
        } else {
            HttpResponse(200, googleBody)
        }
    }

    private fun controller(
        http: HttpTransport = transport,
        withHistory: Boolean = true,
    ) = TranslationPopupController(
        selector = selector,
        credentialsStore = store,
        transport = http,
        logger = sink,
        history = if (withHistory) history else null,
    )

    @Test
    fun `show opens the popup for the selection and dismiss closes it`() {
        val popup = controller()

        val shown = popup.show("Hello world", bookKey = "bk-1", cfi = "epubcfi(/6/4!/4/2)")

        assertTrue(shown.visible)
        assertEquals("Hello world", shown.selection)
        assertEquals(PopupStatus.IDLE, shown.status)
        assertNull(shown.translatedText)
        assertNull(popup.copyPayload())

        val dismissed = popup.dismiss()
        assertFalse(dismissed.visible)
    }

    @Test
    fun `blank selection does not open the popup`() {
        val popup = controller()

        assertFalse(popup.show("   ").visible)
        assertTrue(runBlocking { popup.translate() }.status == PopupStatus.IDLE)
    }

    @Test
    fun `translating without a key asks for credentials and never hits the network`() {
        val popup = controller()

        popup.show("Hello world")
        val state = runBlocking { popup.translate() }

        assertEquals(PopupStatus.NEEDS_CREDENTIALS, state.status)
        assertTrue(state.failureDetail.orEmpty().contains("API key"))
        assertTrue(transport.requests.isEmpty())
        assertTrue(runBlocking { dao.count() } == 0L)
    }

    @Test
    fun `successful translation fills the popup and records history with context`() {
        store.save(TranslationSourceId.GOOGLE, ProviderCredentials(apiKey = TestTokens.GOOGLE))
        val popup = controller()

        popup.show("Hello world", bookKey = "bk-1", cfi = "epubcfi(/6/4!/4/2)")
        val state = runBlocking { popup.translate() }

        assertEquals(PopupStatus.DONE, state.status)
        assertEquals("你好，世界", state.translatedText)
        assertEquals("你好，世界", state.displayText())
        assertTrue(state.recordedInHistory)

        val entry = runBlocking { history.recent(1).first() }
        assertEquals("bk-1", entry.bookKey)
        assertEquals("epubcfi(/6/4!/4/2)", entry.cfi)
        assertEquals("zh-CN", entry.targetLang)
        assertEquals("google-translate-plugin", entry.provider)
        assertEquals("Hello world", entry.sourceText)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `switching source re-translates through the new provider`() {
        store.save(TranslationSourceId.GOOGLE, ProviderCredentials(apiKey = TestTokens.GOOGLE))
        store.save(
            TranslationSourceId.DEEPL,
            ProviderCredentials(apiKey = TestTokens.DEEPL),
        )
        val popup = controller()
        popup.show("Hello world")

        assertTrue(popup.switchProvider(TranslationSourceId.DEEPL))
        assertEquals(TranslationSourceId.DEEPL, popup.state.value.activeSource)
        assertTrue(popup.sources().first { it.id == TranslationSourceId.DEEPL }.active)
        assertEquals(
            listOf(TranslationSourceId.GOOGLE, TranslationSourceId.DEEPL),
            popup.sources().filter { it.configured }.map { it.id },
        )
        assertTrue(popup.sources().first { it.id == TranslationSourceId.MICROSOFT }.configured.not())

        val state = runBlocking { popup.switchProviderAndTranslate(TranslationSourceId.DEEPL) }

        assertEquals(PopupStatus.DONE, state.status)
        assertEquals("你好，世界 (DeepL)", state.translatedText)
        assertEquals("https://api-free.deepl.com/v2/translate", transport.requests.last().url)
        assertEquals("deepl-translate-plugin", runBlocking { history.recent(1).first() }.provider)
    }

    @Test
    fun `unknown source is rejected and logged`() {
        val googleOnly = ProviderSelector(listOf(GoogleTranslateProvider()))
        val popup = TranslationPopupController(googleOnly, store, transport, sink, history)

        assertFalse(popup.switchProvider(TranslationSourceId.DEEPL))
        assertTrue(sink.warnings.any { it.contains("deepl-translate-plugin is not registered") })
    }

    @Test
    fun `cycle provider walks the registration order`() {
        val popup = controller()

        assertEquals(TranslationSourceId.MICROSOFT, popup.cycleProvider())
        assertEquals(TranslationSourceId.MICROSOFT, popup.state.value.activeSource)
    }

    @Test
    fun `popup picks a configured source even when it is not the current one`() {
        store.save(TranslationSourceId.DEEPL, ProviderCredentials(apiKey = TestTokens.DEEPL))
        val popup = controller()

        val state = popup.show("Hello world")

        assertEquals(TranslationSourceId.DEEPL, state.activeSource)
        assertEquals(PopupStatus.IDLE, state.status)
    }

    @Test
    fun `provider failure keeps the selection and reports the reason without recording history`() {
        store.save(TranslationSourceId.GOOGLE, ProviderCredentials(apiKey = TestTokens.GOOGLE))
        val popup = controller(http = FakeHttpTransport.returning("""{"error":"boom"}""", status = 500))

        popup.show("Hello world")
        val state = runBlocking { popup.translate() }

        assertEquals(PopupStatus.FAILED, state.status)
        assertNull(state.translatedText)
        assertEquals("Google returned HTTP 500", state.failureDetail)
        assertEquals("Hello world", state.selection)
        assertTrue(runBlocking { dao.count() } == 0L)
        assertTrue(sink.warnings.any { it.contains("translation failed: source=google-translate-plugin reason=HTTP_STATUS") })
    }

    @Test
    fun `copy payload contains selection translation and provider`() {
        store.save(TranslationSourceId.GOOGLE, ProviderCredentials(apiKey = TestTokens.GOOGLE))
        val popup = controller()
        popup.show("Hello world")
        runBlocking { popup.translate() }

        val payload = popup.copyPayload().orEmpty()

        assertTrue(payload.contains("Hello world"))
        assertTrue(payload.contains("你好，世界"))
        assertTrue(payload.contains("Google Translate"))
    }

    @Test
    fun `popup works without a history repository`() {
        store.save(TranslationSourceId.GOOGLE, ProviderCredentials(apiKey = TestTokens.GOOGLE))
        val popup = controller(withHistory = false)

        popup.show("Hello world")
        val state = runBlocking { popup.translate() }

        assertEquals(PopupStatus.DONE, state.status)
        assertFalse(state.recordedInHistory)
        assertTrue(runBlocking { dao.count() } == 0L)
    }

    @Test
    fun `popup logging never contains the api key`() {
        store.save(TranslationSourceId.GOOGLE, ProviderCredentials(apiKey = TestTokens.GOOGLE))
        val popup = controller()

        popup.show("Hello world")
        runBlocking { popup.translate() }

        assertFalse(sink.text().contains(TestTokens.GOOGLE))
        assertTrue(sink.infos.isNotEmpty())
    }
}
