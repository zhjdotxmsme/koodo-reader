package com.koodoreader.feature.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P6 acceptance #3: AI assistant integration path (chapter summary + Q&A). */
class AiAssistantTest {

    private val sink = RecordingLogger()
    private val completion =
        """{"choices":[{"index":0,"message":{"role":"assistant","content":"  This chapter introduces the lighthouse.  "}}]}"""

    private fun assistant(
        config: AiModelConfig = AiModelConfig(
            endpoint = "https://ai.example.test/v1/",
            providerId = "openai-compatible",
            apiKey = TestTokens.DEEPL,
            modelId = "gpt-4o-mini",
        ),
        transport: HttpTransport = FakeHttpTransport.returning(completion),
    ) = AiAssistant(config, transport, sink)

    @Test
    fun `unconfigured assistant returns NotConfigured without any request`() {
        val transport = FakeHttpTransport.returning(completion)
        val ai = assistant(config = AiModelConfig(), transport = transport)

        assertFalse(ai.isConfigured())
        assertEquals(AiOutcome.NotConfigured, runBlocking { ai.summarize("chapter text") })
        assertEquals(AiOutcome.NotConfigured, runBlocking { ai.ask("who?", "context") })
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `builds an openai compatible chat request`() {
        val request = assistant().buildChatRequest("Summarise this.")

        assertEquals("POST", request.method)
        assertEquals("https://ai.example.test/v1/chat/completions", request.url)
        assertEquals("Bearer ${TestTokens.DEEPL}", request.header("Authorization"))
        assertEquals("application/json", request.header("Content-Type"))
        val body = request.body.orEmpty()
        assertTrue(body.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"content\":\"Summarise this.\""))
        assertFalse(request.redactedDescription().contains(TestTokens.DEEPL))
    }

    @Test
    fun `parses message content and the legacy text shape`() {
        val ai = assistant()

        assertEquals("This chapter introduces the lighthouse.", ai.parseCompletion(completion))
        assertEquals("legacy", ai.parseCompletion("""{"choices":[{"text":"legacy"}]}"""))
        assertNull(ai.parseCompletion("""{"choices":[]}"""))
        assertNull(ai.parseCompletion("not json"))
    }

    @Test
    fun `summarize sends the summary prompt and returns the completion`() {
        val transport = FakeHttpTransport.returning(completion)
        val ai = assistant(transport = transport)

        val outcome = runBlocking { ai.summarize("The lighthouse stood alone.", language = "zh-CN", maxSentences = 3) }

        assertEquals(AiOutcome.Success("This chapter introduces the lighthouse."), outcome)
        val prompt = MiniJson.parseObject(transport.requests.single().body.orEmpty())
            ?.stringAt("messages", 0, "content")
            .orEmpty()
        assertTrue(prompt.contains("3 sentences"))
        assertTrue(prompt.contains("\"zh-CN\""))
        assertTrue(prompt.contains("The lighthouse stood alone."))
        assertTrue(sink.infos.any { it.startsWith("INFO ai request:") })
        assertFalse(sink.text().contains(TestTokens.DEEPL))
    }

    @Test
    fun `ask sends question and book context`() {
        val transport = FakeHttpTransport.returning(completion)
        val ai = assistant(transport = transport)

        val outcome = runBlocking { ai.ask("Who built the lighthouse?", "Chapter 2 book context") }

        assertTrue(outcome is AiOutcome.Success)
        val prompt = MiniJson.parseObject(transport.requests.single().body.orEmpty())
            ?.stringAt("messages", 0, "content")
            .orEmpty()
        assertTrue(prompt.contains("Who built the lighthouse?"))
        assertTrue(prompt.contains("Chapter 2 book context"))
        assertTrue(prompt.contains("ONLY the provided book context"))
    }

    @Test
    fun `empty inputs are rejected before the request`() {
        val transport = FakeHttpTransport.returning(completion)
        val ai = assistant(transport = transport)

        assertEquals(FailureReason.EMPTY_INPUT, (runBlocking { ai.summarize("  ") } as AiOutcome.Failure).reason)
        assertEquals(FailureReason.EMPTY_INPUT, (runBlocking { ai.ask(" ", "ctx") } as AiOutcome.Failure).reason)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `http errors and malformed completions are mapped`() {
        val failing = assistant(transport = FakeHttpTransport.returning("""{"error":"nope"}""", status = 500))
        val malformed = assistant(transport = FakeHttpTransport.returning("""{"choices":[{"message":{}}]}"""))
        val offline = assistant(transport = FakeHttpTransport.failing("dns failure"))

        assertEquals(
            FailureReason.HTTP_STATUS,
            (runBlocking { failing.summarize("text") } as AiOutcome.Failure).reason,
        )
        assertEquals(
            FailureReason.MALFORMED_RESPONSE,
            (runBlocking { malformed.summarize("text") } as AiOutcome.Failure).reason,
        )
        assertEquals(FailureReason.NETWORK, (runBlocking { offline.summarize("text") } as AiOutcome.Failure).reason)
    }

    @Test
    fun `api key never reaches an info level log line`() {
        val ai = assistant()

        ai.logger.info("using endpoint=${ai.hashCode()} apiKey=${TestTokens.DEEPL}")
        runBlocking { ai.summarize("chapter") }

        assertNotNull(sink.infos)
        assertFalse(sink.text().contains(TestTokens.DEEPL))
        assertTrue(sink.text().contains(TokenRedaction.MASK))
    }

    @Test
    fun `desktop config keys stay in parity`() {
        assertEquals(listOf("aiTranslateModel", "aiDictModel", "aiAssistanceModel"), AiConfigKeys.PRIORITY)
        assertEquals("aiAssistanceModel", AiConfigKeys.ASSISTANCE)
        assertEquals(5, AiAssistant.DEFAULT_SUMMARY_SENTENCES)
    }
}
