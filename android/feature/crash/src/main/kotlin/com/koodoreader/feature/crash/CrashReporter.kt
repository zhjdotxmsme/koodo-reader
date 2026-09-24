package com.koodoreader.feature.crash

/**
 * The crash reporting pipeline: breadcrumbs → event → before-send callbacks →
 * backend.
 *
 * Thread-safe; every public entry point is safe to call from any thread, and the
 * fatal path ([install]) is re-entrancy tolerant. Nothing here touches a backend
 * SDK directly, so this whole class is unit-tested on a plain JVM.
 *
 * @param backend transport; [NoopCrashBackend] means "collect nothing", which is
 *   a supported configuration (and the only one available until an adapter is
 *   wired — see [CrashBackends]).
 * @param beforeSend callbacks, applied in order. A callback returning `null`
 *   drops the event (the default [RedactionOnlyCallback] never does).
 * @param breadcrumbCapacity ring size; the oldest breadcrumb is evicted first.
 */
class CrashReporter(
    private val backend: CrashBackend = NoopCrashBackend,
    private val beforeSend: List<BeforeSendCallback> = listOf(RedactionOnlyCallback()),
    private val clock: () -> Long = System::currentTimeMillis,
    breadcrumbCapacity: Int = DEFAULT_BREADCRUMB_CAPACITY,
) {
    private val lock = Any()
    private val capacity = breadcrumbCapacity.coerceAtLeast(1)
    private val crumbs = ArrayDeque<CrashBreadcrumb>()

    @Volatile
    private var installedHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var closed = false

    /** `false` when no real backend is attached (then nothing is transmitted). */
    val isEnabled: Boolean get() = backend !== NoopCrashBackend

    /** Backend id, for logs and the settings screen. */
    val backendId: String get() = backend.id

    /** `true` while the process-wide uncaught-exception handler is ours. */
    val isInstalled: Boolean get() = installedHandler != null

    // ---------------------------------------------------------------- crumbs

    fun addBreadcrumb(category: String, message: String, level: CrashLevel = CrashLevel.INFO) {
        synchronized(lock) {
            if (crumbs.size == capacity) crumbs.removeFirst()
            crumbs.addLast(CrashBreadcrumb(category, message, level, clock()))
        }
    }

    /** Snapshot of the breadcrumbs attached to the next event, oldest first. */
    fun breadcrumbs(): List<CrashBreadcrumb> = synchronized(lock) { crumbs.toList() }

    // ---------------------------------------------------------------- capture

    fun captureException(
        throwable: Throwable,
        handled: Boolean = true,
        level: CrashLevel = if (handled) CrashLevel.ERROR else CrashLevel.FATAL,
        tags: Map<String, String> = emptyMap(),
        extras: Map<String, String> = emptyMap(),
    ): CrashEvent {
        val event = CrashEventFactory.from(
            throwable = throwable,
            level = level,
            handled = handled,
            atMillis = clock(),
            breadcrumbs = breadcrumbs(),
            tags = tags,
            extras = extras,
        )
        return deliver(event) ?: event
    }

    fun captureMessage(
        message: String,
        level: CrashLevel = CrashLevel.INFO,
        tags: Map<String, String> = emptyMap(),
        extras: Map<String, String> = emptyMap(),
    ): CrashEvent {
        val event = CrashEvent(
            exceptionType = null,
            exceptionMessage = message,
            level = level,
            handled = true,
            breadcrumbs = breadcrumbs(),
            tags = tags,
            extras = extras,
            threadName = Thread.currentThread().name,
            atMillis = clock(),
        )
        return deliver(event) ?: event
    }

    /**
     * Runs the before-send chain and hands the result to the backend.
     *
     * @return the event that was actually transmitted, or `null` when a callback
     *   dropped it, the reporter is closed, or the backend threw.
     */
    fun deliver(event: CrashEvent): CrashEvent? {
        if (closed) return null
        var current: CrashEvent = event
        for (callback in beforeSend) {
            current = callback.beforeSend(current) ?: return null
        }
        return try {
            backend.send(current)
            current
        } catch (ignored: Throwable) {
            // A broken backend must never replace the original crash.
            null
        }
    }

    // ------------------------------------------------------------ fatal path

    /**
     * Installs the process-wide uncaught-exception handler. Idempotent.
     *
     * The chain is preserved: after reporting (and a bounded flush) the handler
     * that was installed before us still runs, so the platform/default behaviour
     * is unchanged.
     */
    fun install() {
        synchronized(lock) {
            if (installedHandler != null || closed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            val mine = Thread.UncaughtExceptionHandler { thread, error ->
                try {
                    val event = CrashEventFactory.from(
                        throwable = error,
                        level = CrashLevel.FATAL,
                        handled = false,
                        atMillis = clock(),
                        breadcrumbs = breadcrumbs(),
                        tags = mapOf("thread" to thread.name),
                    )
                    deliver(event)
                    backend.flush(FLUSH_TIMEOUT_MS)
                } catch (ignored: Throwable) {
                    // Never mask the original failure.
                } finally {
                    previous?.uncaughtException(thread, error)
                }
            }
            previousHandler = previous
            installedHandler = mine
            Thread.setDefaultUncaughtExceptionHandler(mine)
        }
    }

    /**
     * Removes our handler, restoring the previous one. Safe to call twice; a
     * handler installed by someone else in the meantime is left alone.
     */
    fun uninstall() {
        synchronized(lock) {
            val mine = installedHandler ?: return
            if (Thread.getDefaultUncaughtExceptionHandler() === mine) {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
            installedHandler = null
            previousHandler = null
        }
    }

    /** Bounded flush, e.g. before the process is killed. Never throws. */
    fun flush(timeoutMillis: Long = FLUSH_TIMEOUT_MS): Boolean = try {
        backend.flush(timeoutMillis)
    } catch (ignored: Throwable) {
        false
    }

    /** Uninstalls, closes the backend and rejects further events. */
    fun close() {
        uninstall()
        closed = true
        try {
            backend.close()
        } catch (ignored: Throwable) {
            // ignore
        }
    }

    companion object {
        const val DEFAULT_BREADCRUMB_CAPACITY = 64

        /** Bounded so a fatal path cannot hang the dying process. */
        const val FLUSH_TIMEOUT_MS = 2_000L
    }
}
