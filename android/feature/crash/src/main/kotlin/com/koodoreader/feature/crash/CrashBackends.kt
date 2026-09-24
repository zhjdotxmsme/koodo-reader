package com.koodoreader.feature.crash

import java.util.concurrent.atomic.AtomicLong

/**
 * Transport seam between the reporter and a real crash backend.
 *
 * Implementations must be thin and must never throw: [CrashReporter] wraps every
 * call, and an exception thrown out of a crash path would replace the original
 * crash with a reporting crash.
 *
 * NO backend SDK is a dependency of this module. Two adapters are envisioned
 * (dependency coordinates are documentation, not build configuration — add them
 * in `android/app/build.gradle` only when a project actually wires one up):
 *
 * ```kotlin
 * // Sentry (io.sentry:sentry-android:7.x, MIT) — event-level control matches
 * // this module 1:1, so RedactionOnlyCallback can be installed directly:
 * //   SentryOptions.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
 * //       redaction.beforeSend(event.toCrashEvent())?.toSentryEvent()
 * //   }
 * //   Sentry.captureException(crashEvent.toThrowable())
 *
 * // Firebase Crashlytics (com.google.firebase:firebase-crashlytics:19.x) — has
 * // no before-send hook, so the adapter applies PiiRedactor to every custom key
 * // and log line before it reaches the SDK and uses
 * // FirebaseCrashlytics.getInstance().recordException(crashEvent.toThrowable()).
 * ```
 */
interface CrashBackend {
    /** Stable id for logs and for the settings screen ("none" when disabled). */
    val id: String

    /** @return `true` when the backend accepted the (already redacted) event. */
    fun send(event: CrashEvent): Boolean

    /** Best-effort flush, used on the fatal path. Must not throw. */
    fun flush(timeoutMillis: Long): Boolean

    /** Releases SDK resources. Must not throw. */
    fun close()
}

/**
 * The default backend: nothing is transmitted. It exists so that
 * `CrashReporter()` with no arguments is fully functional (breadcrumbs, handler
 * installation, redaction) — which is what makes the module usable in tests, in
 * debug builds and in forks that must not ship telemetry.
 */
object NoopCrashBackend : CrashBackend {
    override val id: String = "none"
    override fun send(event: CrashEvent): Boolean = false
    override fun flush(timeoutMillis: Long): Boolean = true
    override fun close() = Unit
}

/**
 * In-memory backend. Useful for local verification ("did the crash path fire?")
 * and as the null-object in unit tests; it never leaves the process.
 */
class RecordingCrashBackend(private val capacity: Int = 32) : CrashBackend {
    override val id: String = "recording"

    private val lock = Any()
    private val events = ArrayDeque<CrashEvent>()
    private val sent = AtomicLong()

    override fun send(event: CrashEvent): Boolean {
        synchronized(lock) {
            if (events.size == capacity) events.removeFirst()
            events.addLast(event)
        }
        sent.incrementAndGet()
        return true
    }

    override fun flush(timeoutMillis: Long): Boolean = true
    override fun close() = Unit

    /** Events kept so far, oldest first. */
    fun recorded(): List<CrashEvent> = synchronized(lock) { events.toList() }

    val sentCount: Long get() = sent.get()
}
