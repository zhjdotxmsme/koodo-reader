package com.koodoreader.engine.pdf

/**
 * Password gate for encrypted PDFs (P3).
 *
 * The desktop kookit engine accepts a single `password` option at render time
 * and bails on `PasswordException`. The native reader has to do better:
 *   - if the user has never set a password for this book key, prompt;
 *   - if they tried before and the password was wrong, remember the failure
 *    (so we can show a clear "wrong password" toast instead of looping);
 *   - persist the successful password against the book key so a re-open
 *    doesn't re-prompt (desktop parity: `getPdfPassword(book)`,
 *     `src/utils/common.ts`).
 *
 * The gate is intentionally pure-Kotlin and free of UI: the caller owns the
 * prompt UI and just feeds candidates in. The gate records the outcome and
 * decides whether the next attempt is worth the user's time. The Android
 * WebView host handles the `PasswordException` thrown by pdf.js's
 * `getDocument({ password })` and calls [attempt] with the user's input.
 *
 * Thread model: every method is pure / single-threaded — the host marshals
 * prompts to the UI thread.
 */
class PasswordGate(
    /** Cache: bookKey -> stored password (desktop `pdfPasswords` parity). */
    private val store: PasswordStore = InMemoryPasswordStore(),
) {

    /**
     * One attempt outcome.
     *
     * The host consumes [shouldTry] only when [state] == [TRY_AGAIN]: it has
     * to prompt the user again. [lastError] carries a human-readable
     * diagnostic; pass it through to a Toast or inline form error.
     */
    sealed class Attempt {
        /** Book opened successfully. [password] is the one that worked. */
        data class Opened(val password: String) : Attempt()

        /** The supplied password was wrong; ask the user once more. */
        data class TryAgain(val password: String, val lastError: String) : Attempt()

        /** Too many failed attempts; tell the user to unlock the file elsewhere. */
        data class GiveUp(val password: String, val lastError: String) : Attempt()
    }

    /** Maximum number of consecutive wrong-password attempts before [GiveUp]. */
    var maxAttempts: Int = 3

    /**
     * @param bookKey stable key from `:core:importer` `BookRecord.key`.
     * @param candidate the password the user typed just now ("" if you want
     *   the cache-only fast path that skips the prompt entirely when the
     *   book is known-unencrypted).
     * @param threw the outcome of pdf.js's `getDocument({ password })` —
     *   pass the raw exception (or null on success).
     */
    fun attempt(
        bookKey: String,
        candidate: String,
        threw: Throwable?,
    ): Attempt {
        // No exception -> success. Cache the password (overwrites any prior
        // wrong one) so a re-open doesn't re-prompt.
        if (threw == null) {
            store.put(bookKey, candidate)
            failures.remove(bookKey)
            return Attempt.Opened(candidate)
        }
        val message = threw.message ?: threw::class.java.simpleName
        // Increment the per-book failure count; give up after [maxAttempts].
        val prior = failures[bookKey] ?: 0
        val next = prior + 1
        failures[bookKey] = next
        return if (next >= maxAttempts) {
            Attempt.GiveUp(candidate, message)
        } else {
            Attempt.TryAgain(candidate, message)
        }
    }

    /**
     * Check whether the host should prefill the password prompt with a
     * cached value (skips the prompt entirely when non-null). Empty-string
     * cached values are treated as "no cache".
     */
    fun cached(bookKey: String): String? = store.get(bookKey)?.takeIf { it.isNotEmpty() }

    /** Forget a book (on user request, or after a successful un-install). */
    fun forget(bookKey: String) {
        store.remove(bookKey)
        failures.remove(bookKey)
    }

    /** Per-book failed-attempt counter; reset on [attempt] success. */
    private val failures: MutableMap<String, Int> = HashMap()

    /** Storage contract for the cached passwords. In-memory by default. */
    interface PasswordStore {
        fun get(bookKey: String): String?
        fun put(bookKey: String, password: String)
        fun remove(bookKey: String)
    }

    /** Default non-persistent store (the room layer wires a real one). */
    class InMemoryPasswordStore : PasswordStore {
        private val map = HashMap<String, String>()
        override fun get(bookKey: String): String? = map[bookKey]
        override fun put(bookKey: String, password: String) { map[bookKey] = password }
        override fun remove(bookKey: String) { map.remove(bookKey) }
    }
}