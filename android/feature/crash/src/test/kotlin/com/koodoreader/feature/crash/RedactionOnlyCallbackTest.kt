package com.koodoreader.feature.crash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The redaction contract (P8): no user data leaves the process, and no event is
 * ever dropped because redaction could not classify it.
 */
class RedactionOnlyCallbackTest {

    private val callback = RedactionOnlyCallback()

    private fun redact(message: String): String =
        callback.beforeSend(CrashEvent(exceptionMessage = message)).exceptionMessage!!

    @Test
    fun `absolute book paths are replaced by a placeholder`() {
        val out = redact("failed to open /storage/emulated/0/Android/data/com.koodoreader.reader/files/books/dune.epub")
        assertFalse(out.contains("emulated"), out)
        assertFalse(out.contains("dune"), out)
        assertTrue(out.contains(PiiRedactor.PATH), out)
    }

    @Test
    fun `windows paths are replaced by a placeholder`() {
        val out = redact("""cannot read C:\Users\54389\Documents\books\dune.epub""")
        assertFalse(out.contains("54389"), out)
        assertTrue(out.contains(PiiRedactor.PATH), out)
    }

    @Test
    fun `content and deep-link uris are replaced by a placeholder`() {
        assertEquals(
            "open failed: ${PiiRedactor.URI}",
            redact("open failed: content://com.android.providers.downloads/document/1234"),
        )
        assertEquals(
            "handoff ${PiiRedactor.URI}",
            redact("handoff koodo-reader://book/9f8e7d6c5b4a3210"),
        )
    }

    @Test
    fun `emails, ipv4 addresses and book keys are redacted`() {
        val out = redact("user reader@example.com sync to 192.168.1.24 bookKey 5d41402abc4b2a76b9719d911017c592")
        assertFalse(out.contains("reader@example.com"), out)
        assertFalse(out.contains("192.168.1.24"), out)
        assertFalse(out.contains("5d41402abc4b2a76b9719d911017c592"), out)
        assertTrue(out.contains(PiiRedactor.EMAIL), out)
        assertTrue(out.contains(PiiRedactor.IP), out)
        assertTrue(out.contains(PiiRedactor.HASH), out)
    }

    @Test
    fun `credentials are redacted before anything else`() {
        val out = redact("Authorization: Bearer abcdef1234567890 token=supersecret")
        assertFalse(out.contains("abcdef1234567890"), out)
        assertFalse(out.contains("supersecret"), out)
        assertTrue(out.contains(PiiRedactor.TOKEN), out)
    }

    @Test
    fun `denied extras keys are dropped whole, unknown keys survive`() {
        val event = callback.beforeSend(
            CrashEvent(
                exceptionMessage = "boom",
                extras = mapOf(
                    "bookTitle" to "Sapiens",
                    "selection" to "a highlighted sentence",
                    "page" to "42",
                    "format" to "epub",
                ),
            ),
        )
        assertNotNull(event)
        assertEquals(setOf("page", "format"), event!!.extras.keys)
        assertEquals("42", event.extras["page"])
    }

    @Test
    fun `stack frame file names lose their directory`() {
        val event = callback.beforeSend(
            CrashEvent(
                frames = listOf(
                    StackFrame("com.koodoreader.reader.shell.LibraryScreen", "onOpen", "/storage/emulated/0/a/LibraryScreen.kt", 42),
                    StackFrame("com.koodoreader.reader.shell.LibraryScreen", "onOpen", null, 43),
                ),
            ),
        )!!
        assertEquals("LibraryScreen.kt", event.frames[0].fileName)
        assertEquals(null, event.frames[1].fileName)
        assertEquals(42, event.frames[0].lineNumber)
    }

    @Test
    fun `oversized messages and breadcrumb floods are bounded, not dropped`() {
        val fat = "x".repeat(5_000)
        val event = callback.beforeSend(
            CrashEvent(
                exceptionMessage = fat,
                // The timestamp is irrelevant to this bound check; CrashBreadcrumb
                // has no default for it (CrashReporter always stamps `clock()`).
                breadcrumbs = (1..40).map { CrashBreadcrumb("reader", "page $it", atMillis = 0L) },
            ),
        )
        assertNotNull(event) // never dropped
        assertTrue(event!!.exceptionMessage!!.length < fat.length)
        assertTrue(event.exceptionMessage!!.endsWith("<truncated>"))
        assertEquals(32, event.breadcrumbs.size)
        assertEquals("page 9", event.breadcrumbs.first().message) // last 32 kept
    }

    @Test
    fun `the callback documents that it never drops events`() {
        assertFalse(callback.dropsEvents)
    }
}
