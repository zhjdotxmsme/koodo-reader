package com.koodoreader.core.locale

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The canonical pack-hash rule (P6 locale packs).
 *
 * `docs/p6-zh-locale-design.md` carries a sha256 per remote locale — that table
 * is the download manifest, and `scripts/check-locales.mjs` part C asserts it is
 * current. The table is generated from the desktop sources in `src/assets/locales`,
 * which git stores with **LF** while a Windows checkout has **CRLF**
 * (`core.autocrlf=true` rewrites only the working tree). Hashing raw bytes
 * therefore produced two different hashes for the same locale — the table
 * matched a Windows box and failed on CI, which is why the guard could never be
 * wired into CI.
 */
class Sha256CanonicalisationTest {

    private val content = """{"Books":"Books","Language":"Language"}"""

    @Test
    @DisplayName("CRLF and LF produce the same pack hash")
    fun crlfAndLfHashIdentically() {
        val lf = content
        val crlf = content.replace("\n", "\r\n")
        assertEquals(Sha256.hex(lf), Sha256.hex(crlf))
    }

    @Test
    @DisplayName("a UTF-8 BOM does not change the pack hash")
    fun bomIsIgnored() {
        assertEquals(Sha256.hex(content), Sha256.hex("\uFEFF$content"))
    }

    @Test
    @DisplayName("content changes still change the hash")
    fun realChangesStillDiffer() {
        assertNotEquals(Sha256.hex(content), Sha256.hex(content.replace("Books", "Livres")))
    }

    @Test
    @DisplayName("canonicalBytes is the LF-normalised UTF-8 form")
    fun canonicalBytesAreNormalised() {
        val bytes = Sha256.canonicalBytes("\uFEFFa\r\nb")
        assertEquals("a\nb", String(bytes, Charsets.UTF_8))
    }
}
