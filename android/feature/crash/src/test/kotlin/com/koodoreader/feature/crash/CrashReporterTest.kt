package com.koodoreader.feature.crash

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Reporter semantics: ordering, never-throw guarantees, and the fatal path. */
class CrashReporterTest {

    private val originalDefaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    @AfterEach
    fun restoreDefaultHandler() {
        Thread.setDefaultUncaughtExceptionHandler(originalDefaultHandler)
    }

    @Test
    fun `before-send callbacks run in order and a null drops the event`() {
        val backend = RecordingCrashBackend()
        val order = mutableListOf<String>()
        val first = BeforeSendCallback { event ->
            order += "first"
            event.copy(tags = event.tags + ("first" to "1"))
        }
        val second = BeforeSendCallback { event ->
            order += "second"
            event.copy(tags = event.tags + ("second" to "2"))
        }
        val reporter = CrashReporter(backend = backend, beforeSend = listOf(first, second), clock = { 7L })

        val delivered = reporter.captureMessage("hello")

        assertEquals(listOf("first", "second"), order)
        assertEquals(mapOf("first" to "1", "second" to "2"), delivered.tags)
        assertEquals(7L, delivered.atMillis)
        assertEquals(1, backend.recorded().size)

        val dropping = CrashReporter(backend = backend, beforeSend = listOf(BeforeSendCallback { null }))
        assertNull(dropping.deliver(CrashEvent(exceptionMessage = "dropped")))
        assertEquals(1, backend.recorded().size)
    }

    @Test
    fun `a throwing backend is swallowed and never replaces the original failure`() {
        val exploding = object : CrashBackend {
            override val id = "exploding"
            override fun send(event: CrashEvent): Boolean = throw IllegalStateException("backend down")
            override fun flush(timeoutMillis: Long): Boolean = throw IllegalStateException("backend down")
            override fun close() = Unit
        }
        val reporter = CrashReporter(backend = exploding, clock = { 1L })

        val event = reporter.captureMessage("still fine")

        assertEquals("still fine", event.exceptionMessage)
        assertFalse(reporter.flush(10L))
    }

    @Test
    fun `breadcrumbs are a bounded ring and ride along with the next event`() {
        val backend = RecordingCrashBackend()
        val reporter = CrashReporter(backend = backend, clock = { 5L }, breadcrumbCapacity = 3)
        repeat(5) { reporter.addBreadcrumb("reader", "page $it") }

        assertEquals(listOf("page 2", "page 3", "page 4"), reporter.breadcrumbs().map { it.message })

        val event = reporter.captureMessage("boom")
        assertEquals(3, event.breadcrumbs.size)
        assertEquals(5L, event.breadcrumbs.first().atMillis)
        assertEquals(1, backend.recorded().size)
    }

    @Test
    fun `events reach the backend already redacted`() {
        val backend = RecordingCrashBackend()
        val reporter = CrashReporter(backend = backend, clock = { 1L }, breadcrumbCapacity = 4)
        reporter.addBreadcrumb("import", "copying /storage/emulated/0/Download/dune.epub")

        reporter.captureException(IllegalStateException("open failed for /storage/emulated/0/books/dune.epub"))

        val sent = backend.recorded().single()
        assertFalse(sent.exceptionMessage!!.contains("emulated"), sent.exceptionMessage!!)
        assertTrue(sent.exceptionMessage!!.contains(PiiRedactor.PATH))
        assertFalse(sent.breadcrumbs.single().message.contains("emulated"))
    }

    @Test
    fun `uncaught exceptions are reported and the previous handler still runs`() {
        val backend = RecordingCrashBackend()
        val seen = AtomicReference<Throwable?>(null)
        val previousRan = CountDownLatch(1)
        val sentinelHandler = Thread.UncaughtExceptionHandler { _, error ->
            seen.set(error)
            previousRan.countDown()
        }
        Thread.setDefaultUncaughtExceptionHandler(sentinelHandler)

        val reporter = CrashReporter(backend = backend, clock = { 1L })
        reporter.install()
        val error = IllegalStateException("boom in /storage/emulated/0/books/dune.epub")
        try {
            val dying = Thread { throw error }
            dying.start()
            dying.join(TimeUnit.SECONDS.toMillis(5))

            assertTrue(previousRan.await(5, TimeUnit.SECONDS), "previous handler must still run")
            assertSame(error, seen.get(), "the previous handler sees the original throwable")

            val sent = backend.recorded().single()
            assertEquals(CrashLevel.FATAL, sent.level)
            assertFalse(sent.handled)
            assertFalse(sent.exceptionMessage!!.contains("emulated"), sent.exceptionMessage!!)
            assertTrue(reporter.isInstalled)
        } finally {
            reporter.uninstall()
        }
        assertSame(sentinelHandler, Thread.getDefaultUncaughtExceptionHandler())
        assertFalse(reporter.isInstalled)
    }

    @Test
    fun `install is idempotent and close stops reporting`() {
        val backend = RecordingCrashBackend()
        val reporter = CrashReporter(backend = backend, clock = { 1L })
        reporter.install()
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        reporter.install()
        assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())

        reporter.close()
        assertNull(reporter.deliver(CrashEvent(exceptionMessage = "after close")))
        assertEquals(0, backend.recorded().size)
    }
}
