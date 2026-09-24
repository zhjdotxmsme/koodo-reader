package com.koodoreader.feature.crash

/**
 * Process-wide access point for crash reporting.
 *
 * Android wiring (the `:app` side — `Application.onCreate`):
 *
 * ```kotlin
 * override fun onCreate() {
 *     super.onCreate()
 *     // 1. Redaction-only, no backend: breadcrumbs + handler, nothing transmitted.
 *     CrashMonitoring.install()
 *     CrashMonitoring.breadcrumb("app", "onCreate")
 *
 *     // 2. Opt in to a backend once the user has consented (the default is off):
 *     //    CrashMonitoring.useBackend(SentryBackend(...))
 * }
 * ```
 *
 * Swapping the backend rebuilds the reporter, so the installed handler always
 * sees the current one.
 */
object CrashMonitoring {

    @Volatile
    private var reporter: CrashReporter = CrashReporter()

    /** The reporter currently installed. Never `null`; may be backend-less. */
    val current: CrashReporter get() = reporter

    /** Installs the default (backend-less, redaction-only) reporter. Idempotent. */
    fun install(): CrashReporter {
        reporter.install()
        return reporter
    }

    /**
     * Replaces the reporter with one bound to [backend] and installs it.
     * Any previously installed handler is uninstalled first.
     */
    fun useBackend(
        backend: CrashBackend,
        beforeSend: List<BeforeSendCallback> = listOf(RedactionOnlyCallback()),
    ): CrashReporter {
        reporter.uninstall()
        reporter = CrashReporter(backend = backend, beforeSend = beforeSend)
        reporter.install()
        return reporter
    }

    /** Convenience breadcrumb on the current reporter. */
    fun breadcrumb(category: String, message: String, level: CrashLevel = CrashLevel.INFO) =
        reporter.addBreadcrumb(category, message, level)

    fun captureException(
        throwable: Throwable,
        handled: Boolean = true,
        extras: Map<String, String> = emptyMap(),
    ): CrashEvent = reporter.captureException(throwable, handled = handled, extras = extras)

    fun flush(timeoutMillis: Long = CrashReporter.FLUSH_TIMEOUT_MS): Boolean =
        reporter.flush(timeoutMillis)

    /** Stops reporting without discarding the reporter (e.g. user opt-out). */
    fun disable() {
        reporter.uninstall()
    }
}
