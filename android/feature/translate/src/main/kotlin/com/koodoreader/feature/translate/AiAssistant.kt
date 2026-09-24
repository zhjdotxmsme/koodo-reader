package com.koodoreader.feature.translate

/**
 * OpenAI-compatible chat model configuration, field-for-field with the desktop
 * `AiModelConfig` (`src/utils/request/aiBridge.ts`).
 *
 * Desktop stores one model per AI feature and picks the first usable one in the
 * order translate → dict → assistance ([AiConfigKeys]); the Android settings
 * screen keeps the same reader-config keys so an exported desktop config maps
 * 1:1.
 */
data class AiModelConfig(
    val endpoint: String = "",
    val providerId: String = "",
    val apiKey: String = "",
    val modelId: String = "",
) {
    /** Desktop strips trailing slashes before appending `/chat/completions`. */
    fun normalizedEndpoint(): String = endpoint.trim().trimEnd('/')

    fun isUsable(): Boolean = normalizedEndpoint().isNotEmpty() && modelId.isNotBlank()
}

object AiConfigKeys {
    const val TRANSLATE = "aiTranslateModel"
    const val DICT = "aiDictModel"
    const val ASSISTANCE = "aiAssistanceModel"

    /** Desktop priority order for "the one model available to everything". */
    val PRIORITY = listOf(TRANSLATE, DICT, ASSISTANCE)
}

sealed interface AiOutcome {
    data class Success(val text: String) : AiOutcome

    /** No endpoint/model configured — the UI shows "configure an AI service". */
    data object NotConfigured : AiOutcome

    data class Failure(val reason: FailureReason, val detail: String) : AiOutcome
}

/**
 * Prompt templates. Kept byte-identical to the desktop wording so summaries and
 * answers stay comparable across platforms, and so the prompts are unit-testable
 * without a model.
 */
object AiPrompts {

    fun summaryPrompt(text: String, language: String, maxSentences: Int): String = listOf(
        "You are a reading assistant inside an ebook reader.",
        "Summarise the chapter excerpt below in ${sentences(maxSentences)} or fewer sentences, " +
            "in the language identified by the tag \"$language\".",
        "Keep names, numbers and the author's intent; do not add information that is not in the text.",
        "Return plain text only, no markdown, no bullet list, no preamble.",
        "Chapter excerpt:",
        text,
    ).joinToString("\n")

    fun questionPrompt(question: String, context: String): String = listOf(
        "You are a reading assistant inside an ebook reader.",
        "Answer the reader's question using ONLY the provided book context.",
        "If the context does not contain the answer, say so explicitly and do not invent facts.",
        "Answer in the language of the question. Return plain text only, no markdown.",
        "Book context:",
        context,
        "Reader's question:",
        question,
    ).joinToString("\n")

    private fun sentences(count: Int): String = if (count <= 1) "1 sentence" else "$count sentences"
}

/**
 * AI assistant (P6 acceptance #3): chapter summary + question answering over the
 * current book context.
 *
 * This is the *接入路径* (integration path): the reader passes the selected
 * chapter/excerpt, [AiAssistant] builds the request, executes it through the
 * injected [HttpTransport] and returns an [AiOutcome]. Streaming (`chatStream`
 * with SSE on desktop) is a follow-up: the non-streaming shape below is the
 * contract the UI is built against, and `parseCompletion` already tolerates both
 * the `message.content` and legacy `text` response shapes.
 *
 * Logging: [logger] is a [RedactedLogger] bound to the configured API key, so
 * the key can never reach an info-level line (CLAUDE.md rule).
 */
class AiAssistant(
    private val config: AiModelConfig,
    private val transport: HttpTransport,
    rawLogger: Logger = NoopLogger,
) {

    val logger: Logger = RedactedLogger(
        rawLogger,
        secretSource = { listOf(config.apiKey).filter { it.isNotBlank() } },
    )

    fun isConfigured(): Boolean = config.isUsable()

    /** `POST {endpoint}/chat/completions`, OpenAI-compatible request body. */
    fun buildChatRequest(prompt: String): HttpRequest {
        val body = MiniJson.body(
            "model" to config.modelId,
            "stream" to false,
            "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
        )
        return HttpRequest(
            method = "POST",
            url = "${config.normalizedEndpoint()}/chat/completions",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer ${config.apiKey}",
            ),
            body = body,
        )
    }

    /** `choices[0].message.content`, falling back to the legacy `choices[0].text`. */
    fun parseCompletion(body: String): String? {
        val json = MiniJson.parse(body) ?: return null
        val content = json.stringAt("choices", 0, "message", "content")
            ?: json.stringAt("choices", 0, "text")
            ?: return null
        return content.trim().ifEmpty { null }
    }

    suspend fun complete(prompt: String): AiOutcome {
        if (prompt.isBlank()) {
            return AiOutcome.Failure(FailureReason.EMPTY_INPUT, "empty prompt")
        }
        if (!isConfigured()) {
            return AiOutcome.NotConfigured
        }
        logger.info("ai request: provider=${config.providerId} model=${config.modelId} promptChars=${prompt.length}")
        return try {
            val response = transport.execute(buildChatRequest(prompt))
            if (!response.isSuccess) {
                AiOutcome.Failure(FailureReason.HTTP_STATUS, "AI endpoint returned HTTP ${response.statusCode}")
            } else {
                val text = parseCompletion(response.body)
                if (text == null) {
                    AiOutcome.Failure(FailureReason.MALFORMED_RESPONSE, "AI endpoint returned no completion text")
                } else {
                    AiOutcome.Success(text)
                }
            }
        } catch (e: HttpTransportException) {
            AiOutcome.Failure(FailureReason.NETWORK, e.message ?: "network error")
        }
    }

    /** Chapter/selection summary. */
    suspend fun summarize(text: String, language: String = "zh-CN", maxSentences: Int = DEFAULT_SUMMARY_SENTENCES): AiOutcome {
        if (text.isBlank()) {
            return AiOutcome.Failure(FailureReason.EMPTY_INPUT, "nothing to summarise")
        }
        return complete(AiPrompts.summaryPrompt(text, language, maxSentences))
    }

    /** Question answering grounded in [context]. */
    suspend fun ask(question: String, context: String): AiOutcome {
        if (question.isBlank()) {
            return AiOutcome.Failure(FailureReason.EMPTY_INPUT, "empty question")
        }
        return complete(AiPrompts.questionPrompt(question, context))
    }

    companion object {
        const val DEFAULT_SUMMARY_SENTENCES = 5
    }
}
