package com.koodoreader.feature.translate

/**
 * Logging seam. Implemented by an `android.util.Log` wrapper in `:app` and by a
 * recording fake in unit tests.
 */
interface Logger {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}

object NoopLogger : Logger {
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, throwable: Throwable?) = Unit
}

/**
 * Token masking helpers.
 *
 * Project rule (CLAUDE.md / P6 acceptance #2): **a full token must never reach
 * an info-level log line**. Two independent defences are applied by
 * [RedactedLogger]:
 *
 *  1. value-based — every secret the app has ever loaded (API keys, bearer
 *     tokens) is replaced literally, wherever it appears;
 *  2. shape-based — anything that *looks* like `key=…`, `Authorization: Bearer …`,
 *     `sk-…`, `AIza…` is masked even if the app never saw that secret.
 */
object TokenRedaction {
    const val MASK = "***"

    private val KEY_VALUE = Regex(
        "(?i)\\b(api[-_]?key|apikey|subscription[-_]?key|auth[-_]?key|access[-_]?token|refresh[-_]?token|token|secret|password|passwd|passphrase)\\b(\"?\\s*[:=]\\s*\"?|\\s+)([A-Za-z0-9\\-._~+/=]{6,})",
    )

    private val PATTERNS: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // key=value / key: value / key value pairs (query strings, JSON, headers)
        KEY_VALUE to { match -> "${match.groupValues[1]}${match.groupValues[2]}$MASK" },
        // Authorization schemes
        Regex("(?i)\\b(bearer|deepl-auth-key|basic|token)\\s+([A-Za-z0-9\\-._~+/=]{6,})") to { match ->
            "${match.groupValues[1]} $MASK"
        },
        // Well-known token shapes (OpenAI/Anthropic/HuggingFace, Google API keys)
        Regex("\\b(sk|pk|rk|hf)-[A-Za-z0-9\\-_]{8,}\\b") to { MASK },
        Regex("\\bAIza[0-9A-Za-z\\-_]{20,}\\b") to { MASK },
        // Long base64-ish blobs that are not part of a word
        Regex("\\b[A-Za-z0-9+/]{40,}={0,2}\\b") to { MASK },
    )

    /**
     * @param secrets values that must never appear in the output, matched
     *   literally and case-sensitively (they are compared byte-for-byte).
     */
    fun redact(message: String, secrets: Collection<String> = emptyList()): String {
        var result = message
        for (secret in secrets) {
            if (secret.length >= MIN_SECRET_LENGTH && result.contains(secret)) {
                result = result.replace(secret, MASK)
            }
        }
        for ((pattern, replacement) in PATTERNS) {
            result = pattern.replace(result, replacement)
        }
        return result
    }

    /** `sk-abcd…wxyz` -> `***wxyz`; short values are masked entirely. */
    fun mask(secret: String?): String {
        if (secret.isNullOrEmpty()) {
            return ""
        }
        return if (secret.length <= KEEP_SUFFIX) {
            MASK
        } else {
            MASK + secret.takeLast(KEEP_SUFFIX)
        }
    }

    /** Number of trailing characters preserved by [mask] for operator support. */
    const val KEEP_SUFFIX = 4

    /** Secrets shorter than this are not value-matched (too many false hits). */
    const val MIN_SECRET_LENGTH = 6
}

/**
 * [Logger] decorator that redacts before delegating.
 *
 * The [secretSource] lambda is re-evaluated on every call, so secrets that are
 * saved *after* the logger was constructed are covered as well.
 */
class RedactedLogger(
    private val delegate: Logger,
    private val secretSource: () -> Collection<String> = { emptyList() },
) : Logger {

    override fun info(message: String) {
        delegate.info(redact(message))
    }

    override fun warn(message: String) {
        delegate.warn(redact(message))
    }

    override fun error(message: String, throwable: Throwable?) {
        // The message is sanitised; a throwable's own message is not rewritten
        // (Java throwables are immutable). Network layers therefore translate
        // IO failures into HttpTransportException with a token-free message.
        delegate.error(redact(message), throwable)
    }

    fun redact(message: String): String = TokenRedaction.redact(message, secretSource())
}
