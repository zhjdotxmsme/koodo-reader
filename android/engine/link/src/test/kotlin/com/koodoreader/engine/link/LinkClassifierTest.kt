package com.koodoreader.engine.link

import com.koodoreader.engine.cfi.Cfi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Branch-complete coverage of [LinkClassifier.classify]: every [LinkKind],
 * scheme case-insensitivity, the two EPUBCFI spellings (+ version marker +
 * malformed body), and the "relative path that merely contains a colon later"
 * distinction.
 *
 * EPUBCFI bodies are validated through the real `:engine:cfi` parser, so a
 * green test here means a link would actually be navigable — not merely that
 * a prefix matched.
 */
class LinkClassifierTest {

    private fun classify(raw: String): LinkTarget = LinkClassifier.classify(raw)

    @Nested
    @DisplayName("ANCHOR — in-page fragments")
    inner class Anchor {

        @Test
        @DisplayName("#fragment → ANCHOR with the fragment exposed")
        fun fragment() {
            val t = classify("#note1")
            assertEquals(LinkKind.ANCHOR, t.kind)
            assertEquals("note1", t.fragment)
            assertEquals("", t.uri)
        }

        @Test
        @DisplayName("a lone # → ANCHOR with an empty fragment")
        fun loneHash() {
            val t = classify("#")
            assertEquals(LinkKind.ANCHOR, t.kind)
            assertEquals("", t.fragment)
        }
    }

    @Nested
    @DisplayName("EPUBCFI — both spellings, validated via :engine:cfi")
    inner class EpubCfi {

        @Test
        @DisplayName("wrapped form epubcfi(...) parses to a Cfi")
        fun wrappedForm() {
            val t = classify("epubcfi(/6/4[chap01ref]!/4/2)")
            assertEquals(LinkKind.EPUBCFI, t.kind)
            assertEquals(true, t.cfiValid)
            assertTrue(t.cfi is Cfi.Point)
        }

        @Test
        @DisplayName("URI form epubcfi:/6/... parses")
        fun uriForm() {
            val t = classify("epubcfi:/6/4[chap01ref]!/4/2")
            assertEquals(LinkKind.EPUBCFI, t.kind)
            assertEquals(true, t.cfiValid)
            assertNotNull(t.cfi)
        }

        @Test
        @DisplayName("URI form with the EPUB 3 version marker (0?) parses")
        fun versionedUriForm() {
            val t = classify("epubcfi:0?/6/4[chap01ref]!/4/2")
            assertEquals(LinkKind.EPUBCFI, t.kind)
            assertEquals(true, t.cfiValid)
            assertNotNull(t.cfi)
        }

        @Test
        @DisplayName("epubcfi() with an empty body → EPUBCFI but invalid, never throws")
        fun emptyBody() {
            val t = classify("epubcfi()")
            assertEquals(LinkKind.EPUBCFI, t.kind)
            assertEquals(false, t.cfiValid)
            assertNull(t.cfi)
        }

        @Test
        @DisplayName("epubcfi: with a malformed body → EPUBCFI but invalid")
        fun malformedBody() {
            val t = classify("epubcfi:2")
            assertEquals(LinkKind.EPUBCFI, t.kind)
            assertEquals(false, t.cfiValid)
            assertNull(t.cfi)
        }
    }

    @Nested
    @DisplayName("EXTERNAL_HTTP — http / https, case-insensitive scheme")
    inner class ExternalHttp {

        @Test
        fun http() {
            val t = classify("http://example.com/a?b=1")
            assertEquals(LinkKind.EXTERNAL_HTTP, t.kind)
            assertEquals("http://example.com/a?b=1", t.uri)
        }

        @Test
        fun https() {
            val t = classify("https://example.com")
            assertEquals(LinkKind.EXTERNAL_HTTP, t.kind)
            assertEquals("https://example.com", t.uri)
        }

        @Test
        @DisplayName("scheme matching is case-insensitive")
        fun mixedCase() {
            val t = classify("HTTP://EXAMPLE.COM/Path")
            assertEquals(LinkKind.EXTERNAL_HTTP, t.kind)
            // The usable URI keeps the original casing.
            assertEquals("HTTP://EXAMPLE.COM/Path", t.uri)
        }
    }

    @Nested
    @DisplayName("MAILTO")
    inner class Mailto {

        @Test
        fun plainAddress() {
            val t = classify("mailto:a@b.com")
            assertEquals(LinkKind.MAILTO, t.kind)
            assertEquals("a@b.com", t.emailAddress)
            assertEquals("mailto:a@b.com", t.uri)
        }

        @Test
        @DisplayName("?params are stripped from the address but kept on the uri")
        fun withParams() {
            val t = classify("mailto:a@b.com?subject=Hi")
            assertEquals(LinkKind.MAILTO, t.kind)
            assertEquals("a@b.com", t.emailAddress)
            assertEquals("mailto:a@b.com?subject=Hi", t.uri)
        }

        @Test
        @DisplayName("scheme matching is case-insensitive")
        fun mixedCase() {
            val t = classify("MAILTO:A@B.COM")
            assertEquals(LinkKind.MAILTO, t.kind)
            assertEquals("A@B.COM", t.emailAddress)
        }
    }

    @Nested
    @DisplayName("SAF — content / file storage URIs")
    inner class Saf {

        @Test
        fun contentUri() {
            val t = classify("content://media/external/images/1")
            assertEquals(LinkKind.SAF, t.kind)
            assertEquals("content://media/external/images/1", t.uri)
        }

        @Test
        fun fileUri() {
            val t = classify("file:///sdcard/chapter.xhtml")
            assertEquals(LinkKind.SAF, t.kind)
            assertEquals("file:///sdcard/chapter.xhtml", t.uri)
        }
    }

    @Nested
    @DisplayName("OTHER — empty, unknown schemes, relative paths")
    inner class Other {

        @Test
        fun emptyHref() {
            assertEquals(LinkKind.OTHER, classify("").kind)
        }

        @Test
        fun blankHref() {
            assertEquals(LinkKind.OTHER, classify("   ").kind)
        }

        @Test
        fun javascriptScheme() {
            assertEquals(LinkKind.OTHER, classify("javascript:alert(1)").kind)
        }

        @Test
        fun aboutScheme() {
            assertEquals(LinkKind.OTHER, classify("about:blank").kind)
        }

        @Test
        @DisplayName("a vendor scheme with dots is still OTHER")
        fun vendorScheme() {
            assertEquals(LinkKind.OTHER, classify("vnd.android.cursor.item://x").kind)
        }

        @Test
        fun relativePath() {
            assertEquals(LinkKind.OTHER, classify("chap03.xhtml").kind)
        }

        @Test
        @DisplayName("relative path with a colon after a # is not scheme-bearing")
        fun relativePathWithLateColon() {
            assertEquals(LinkKind.OTHER, classify("chap03.xhtml#c:1").kind)
        }
    }

    @Test
    @DisplayName("the original raw href (incl. surrounding whitespace) is preserved")
    fun rawHrefPreserved() {
        val t = classify("  https://example.com  ")
        assertEquals(LinkKind.EXTERNAL_HTTP, t.kind)
        assertEquals("  https://example.com  ", t.href)
        assertFalse(t.uri == t.href)
    }
}
