package com.koodoreader.engine.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PasswordGateTest {

    @Test
    fun successfulAttemptCachesAndClearsFailures() {
        val gate = PasswordGate()
        val attempt = gate.attempt("book1", "secret", null)
        assertTrue(attempt is PasswordGate.Attempt.Opened)
        assertEquals("secret", gate.cached("book1"))
    }

    @Test
    fun failurePromptsTryAgainUntilMaxAttempts() {
        val gate = PasswordGate()
        gate.maxAttempts = 3
        val e = RuntimeException("PasswordException")
        assertTrue(gate.attempt("book1", "wrong", e) is PasswordGate.Attempt.TryAgain)
        assertTrue(gate.attempt("book1", "wrong", e) is PasswordGate.Attempt.TryAgain)
        assertTrue(gate.attempt("book1", "wrong", e) is PasswordGate.Attempt.GiveUp)
    }

    @Test
    fun forgetClearsCachedAndCounter() {
        val gate = PasswordGate()
        gate.attempt("book1", "ok", null)
        gate.forget("book1")
        assertNull(gate.cached("book1"))
    }

    @Test
    fun emptyCachedValueIsNotReturned() {
        val store = object : PasswordGate.PasswordStore {
            override fun get(bookKey: String): String? = ""
            override fun put(bookKey: String, password: String) {}
            override fun remove(bookKey: String) {}
        }
        val gate = PasswordGate(store)
        assertNull(gate.cached("book1"))
    }

    @Test
    fun attemptOnDifferentBookDoesNotShareFailureCount() {
        val gate = PasswordGate()
        gate.maxAttempts = 2
        val e = RuntimeException("nope")
        gate.attempt("book1", "x", e)
        gate.attempt("book1", "x", e)
        // book1 exhausted, but book2 starts fresh
        assertFalse(gate.attempt("book2", "y", e) is PasswordGate.Attempt.GiveUp)
    }

    @Test
    fun notNullIsOnlyForgivingForCached() {
        val gate = PasswordGate()
        // Returns null when never stored
        assertNull(gate.cached("nope"))
    }
}