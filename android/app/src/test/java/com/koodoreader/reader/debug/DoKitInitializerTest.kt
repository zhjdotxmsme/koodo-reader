package com.koodoreader.reader.debug

import android.app.Application
import com.koodoreader.reader.BuildConfig
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [DoKitInitializer].
 *
 * DoKit itself touches the Android framework, so these tests verify the wrapper
 * policy (debug-only, error swallowing) by replacing the real installer with a
 * test double. The [Application] instance is only used as an opaque token.
 */
class DoKitInitializerTest {

    private val application: Application = createApplicationStub()

    private fun createApplicationStub(): Application {
        // The android.jar stubs in JVM unit tests throw from most methods, but
        // object identity is enough for the initializer to pass it along.
        return Application::class.java.getDeclaredConstructor().newInstance()
    }

    @After
    fun tearDown() {
        DoKitInitializer.enabledProvider = { BuildConfig.DEBUG }
        DoKitInitializer.installer = { _ -> }
    }

    @Test
    fun `install returns false when DoKit is disabled`() {
        DoKitInitializer.enabledProvider = { false }
        var called = false
        DoKitInitializer.installer = { _ -> called = true }

        val result = DoKitInitializer.install(application)

        assertFalse("installer must not run when disabled", called)
        assertFalse("install must report skipped", result)
    }

    @Test
    fun `install returns true when enabled and installer succeeds`() {
        DoKitInitializer.enabledProvider = { true }
        var called = false
        var received: Application? = null
        DoKitInitializer.installer = { app ->
            called = true
            received = app
        }

        val result = DoKitInitializer.install(application)

        assertTrue("installer must run when enabled", called)
        assertTrue("installer receives the application", received === application)
        assertTrue("install must report success", result)
    }

    @Test
    fun `install swallows installer exceptions and returns false`() {
        DoKitInitializer.enabledProvider = { true }
        DoKitInitializer.installer = { _ -> throw RuntimeException("DoKit exploded") }

        val result = DoKitInitializer.install(application)

        assertFalse("install must report failure when installer throws", result)
    }
}
