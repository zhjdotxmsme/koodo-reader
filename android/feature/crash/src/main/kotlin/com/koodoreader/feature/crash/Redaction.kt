package com.koodoreader.feature.crash

/**
 * Before-send hook.
 *
 * This is the seam both real backends expose: Sentry calls it
 * `BeforeSendCallback` (`SentryOptions.beforeSend`, `(SentryEvent, Hint) ->
 * SentryEvent?`), Crashlytics has no direct equivalent (the adapter applies the
 * same pass in its custom-key/log formatter). Returning `null` drops the event.
 */
fun interface BeforeSendCallback {
    fun beforeSend(event: CrashEvent): CrashEvent?
}

/**
 * Ordered PII redaction rules. Every match becomes a stable placeholder, so
 * crash grouping and dashboards stay comparable across devices while no user
 * data leaves the process.
 *
 * Rule order matters: specific patterns (credentials, URIs) run before the broad
 * "looks like a path" ones.
 */
class PiiRedactor(
    private val rules: List<Rule> = DEFAULT_RULES,
    private val maxChars: Int = 4_000,
) {
    /** One regex → replacement pair. [Rule.replacement] is literal except for `$1`-style groups. */
    data class Rule(val name: String, val regex: Regex, val replacement: String)

    /** Applies every rule in order, then truncates to [maxChars]. */
    fun redact(text: String): String {
        var out = text
        for (rule in rules) {
            out = rule.regex.replace(out, rule.replacement)
        }
        return if (out.length > maxChars) out.take(maxChars) + "<truncated>" else out
    }

    /** Redacts values, keeps keys. Keys are our own vocabulary, never user text. */
    fun redactMap(values: Map<String, String>): Map<String, String> =
        values.mapValues { (_, value) -> redact(value) }

    /** Redacts a file name / path down to a bare, path-free token. */
    fun redactFileName(fileName: String): String {
        val bare = fileName.substringAfterLast('/').substringAfterLast('\\')
        return redact(bare)
    }

    companion object {
        const val PATH = "<path>"
        const val URI = "<uri>"
        const val EMAIL = "<email>"
        const val IP = "<ip>"
        const val TOKEN = "<token>"
        const val HASH = "<hash>"

        /** Book keys, data-urls, etc. — library identifiers, not "noise". */
        private val MD5_OR_LONG_HEX = Regex("\\b[0-9a-fA-F]{32,}\\b")

        val DEFAULT_RULES: List<Rule> = listOf(
            // --- credentials first: they may contain '/', ':' or '@' -----------
            Rule("authorization-header", Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}"), "$1 $TOKEN"),
            Rule("jwt", Regex("\\beyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{4,}\\b"), TOKEN),
            Rule("key-value-secret", Regex("(?i)\\b(password|passwd|pwd|secret|api[_-]?key|apikey|access[_-]?token|token|authorization|cookie)\\b\\s*[:=]\\s*\\S+"), "$1=$TOKEN"),
            Rule("url-query-secret", Regex("(?i)([?&](?:token|key|pwd|password|signature|sig|access_token|apikey)=)[^&\\s]+"), "$1$TOKEN"),
            // --- identifiers ----------------------------------------------------
            Rule("email", Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), EMAIL),
            Rule("ipv4", Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), IP),
            Rule("hash", MD5_OR_LONG_HEX, HASH),
            // --- locations ------------------------------------------------------
            Rule("uri", Regex("(?i)\\b(?:content|file|android|koodo-reader)://[^\\s\"'<>)]*"), URI),
            Rule("windows-path", Regex("[A-Za-z]:\\\\[^\\s\"'<>)]*"), PATH),
            Rule("posix-path", Regex("(?:/(?:storage|sdcard|data|mnt|media|home|Users|var|tmp|private|opt)/[^\\s\"'<>:)]*)"), PATH),
        )
    }
}

/**
 * The **only** before-send callback installed by default: it rewrites an event
 * so no personal data is transmitted, and it never drops one.
 *
 * "Redaction-only" is the contract, not a hint:
 *  - [dropsEvents] is `false` and [beforeSend] never returns `null` — a crash is
 *    never lost because redaction could not classify it;
 *  - the only transformations are (a) regex replacement via [PiiRedactor],
 *    (b) truncation of oversized strings, (c) removal of whole `extras` keys in
 *    [deniedExtraKeys] (book paths, reading position, selections, notes …), and
 *    (d) breadcrumb trimming to [maxBreadcrumbs].
 *
 * @param deniedExtraKeys lower-cased `extras` keys removed entirely, because for
 *   them even a redacted value would be user content (a book title, a note, a
 *   search query).
 */
class RedactionOnlyCallback(
    private val redactor: PiiRedactor = PiiRedactor(),
    private val deniedExtraKeys: Set<String> = DEFAULT_DENIED_EXTRA_KEYS,
    private val maxBreadcrumbs: Int = 32,
    private val maxMessageChars: Int = 1_000,
) : BeforeSendCallback {

    /** Always `false`: redaction must never turn into event loss. */
    val dropsEvents: Boolean = false

    override fun beforeSend(event: CrashEvent): CrashEvent = event.copy(
        exceptionMessage = event.exceptionMessage?.let { clamp(redactor.redact(it)) },
        frames = event.frames.map { it.copy(fileName = it.fileName?.let(redactor::redactFileName)) },
        causes = event.causes.map { CauseNode(it.type, it.message?.let { m -> clamp(redactor.redact(m)) }) },
        breadcrumbs = event.breadcrumbs.takeLast(maxBreadcrumbs.coerceAtLeast(0)).map {
            it.copy(message = clamp(redactor.redact(it.message)))
        },
        tags = redactor.redactMap(event.tags),
        extras = redactor.redactMap(
            event.extras.filterKeys { key -> key.lowercase() !in deniedExtraKeys },
        ),
    )

    private fun clamp(text: String): String =
        if (text.length > maxMessageChars) text.take(maxMessageChars) + "<truncated>" else text

    companion object {
        /**
         * Keys whose value is user content rather than incidental metadata. Kept
         * as a stable, reviewable list so adding a breadcrumb/extras call site is
         * a conscious decision (see docs/p8-rollout-checklist.md §4.3).
         */
        val DEFAULT_DENIED_EXTRA_KEYS: Set<String> = setOf(
            "path", "file", "filename", "filepath", "bookpath", "bookpaths", "storagepath",
            "uri", "url", "lastbook", "currentbook", "openbook",
            "title", "booktitle", "author", "publisher", "isbn", "bookkey", "md5",
            "search", "query", "keyword", "selection", "selectedtext", "clipboard",
            "text", "content", "excerpt", "note", "notes", "annotation", "highlight",
            "email", "token", "cookie", "user", "userid", "account", "syncserver",
        )
    }
}

/**
 * Re-hydrates a redacted [CrashEvent] into a `Throwable` for SDKs that require
 * one (Sentry's `captureException`). The stack trace is rebuilt from
 * [CrashEvent.frames]; the wrapper type is intentionally generic so it never
 * collides with a real app class.
 */
fun CrashEvent.toThrowable(): Throwable {
    val carrier = IllegalStateException(
        buildString {
            append(exceptionType ?: "KoodoCrash")
            exceptionMessage?.let { append(": ").append(it) }
        },
    )
    if (frames.isNotEmpty()) {
        carrier.stackTrace = frames
            .map { StackTraceElement(it.className, it.methodName, it.fileName, it.lineNumber) }
            .toTypedArray()
    }
    return carrier
}
