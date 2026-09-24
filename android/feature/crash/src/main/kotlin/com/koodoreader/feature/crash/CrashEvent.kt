package com.koodoreader.feature.crash

/**
 * Severity. Values are the common denominator of Sentry (`SentryLevel`) and
 * Firebase Crashlytics (fatal / non-fatal + custom keys), so adapters map 1:1.
 */
enum class CrashLevel { DEBUG, INFO, WARNING, ERROR, FATAL }

/** One stack frame. [fileName] is reduced to a bare name by [PiiRedactor]. */
data class StackFrame(
    val className: String,
    val methodName: String,
    val fileName: String?,
    val lineNumber: Int,
)

/** One element of the `cause` chain (bounded by [CrashEventFactory.MAX_CAUSES]). */
data class CauseNode(
    val type: String,
    val message: String?,
)

/** What the app was doing before the crash. Attached to the next event. */
data class CrashBreadcrumb(
    val category: String,
    val message: String,
    val level: CrashLevel = CrashLevel.INFO,
    val atMillis: Long,
)

/**
 * A backend-agnostic crash event — plain data, **no live `Throwable`**.
 *
 * That is deliberate: keeping the payload to strings is what makes
 * [RedactionOnlyCallback] total, i.e. every byte that could leave the process
 * passes through [PiiRedactor] first. Backends that insist on a `Throwable`
 * re-hydrate one with [toThrowable].
 */
data class CrashEvent(
    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
    val level: CrashLevel = CrashLevel.ERROR,
    val handled: Boolean = true,
    val frames: List<StackFrame> = emptyList(),
    val causes: List<CauseNode> = emptyList(),
    val breadcrumbs: List<CrashBreadcrumb> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
    val extras: Map<String, String> = emptyMap(),
    val threadName: String? = null,
    val atMillis: Long = 0L,
) {
    /**
     * Stable grouping key (exception type + top frame). Contains no user data, so
     * it is safe to ship as-is even when redaction is relaxed later.
     */
    val fingerprint: String
        get() = buildString {
            append(exceptionType ?: "message")
            frames.firstOrNull()?.let { append('@').append(it.className).append('#').append(it.methodName) }
        }
}

/** Turns live exceptions into [CrashEvent]s; nothing but strings is retained. */
object CrashEventFactory {
    /** Stack frames are truncated: a pathological stack must not inflate the payload. */
    const val MAX_FRAMES = 64

    /** Cause chains are truncated for the same reason. */
    const val MAX_CAUSES = 8

    fun from(
        throwable: Throwable,
        level: CrashLevel = CrashLevel.ERROR,
        handled: Boolean = true,
        atMillis: Long = System.currentTimeMillis(),
        breadcrumbs: List<CrashBreadcrumb> = emptyList(),
        tags: Map<String, String> = emptyMap(),
        extras: Map<String, String> = emptyMap(),
    ): CrashEvent {
        val causes = ArrayList<CauseNode>(MAX_CAUSES)
        var cursor: Throwable? = throwable.cause
        while (cursor != null && causes.size < MAX_CAUSES) {
            causes += CauseNode(cursor.javaClass.name, cursor.message)
            cursor = cursor.cause
        }
        val frames = throwable.stackTrace
            .take(MAX_FRAMES)
            .map { StackFrame(it.className, it.methodName, it.fileName, it.lineNumber) }
        return CrashEvent(
            exceptionType = throwable.javaClass.name,
            exceptionMessage = throwable.message,
            level = level,
            handled = handled,
            frames = frames,
            causes = causes,
            breadcrumbs = breadcrumbs,
            tags = tags,
            extras = extras,
            threadName = Thread.currentThread().name,
            atMillis = atMillis,
        )
    }
}
