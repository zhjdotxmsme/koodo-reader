package com.koodoreader.engine.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Coverage of [LinkRouter]: the dispatcher-parity DEFAULT table
 * (http/https/mailto → system open; everything else → none), the two native
 * capability upgrades (valid EPUBCFI → internal jump; whitelisted SAF → open),
 * every policy kill-switch, and the event-level footnote-wins-over-href
 * contract.
 */
class LinkRouterTest {

    private val router = LinkRouter()

    @Nested
    @DisplayName("DEFAULT policy — parity with NativeEventDispatcher.handleLinkClick")
    inner class DefaultParity {

        @Test
        fun httpOpensSystemBrowser() {
            val a = router.route("http://example.com")
            assertTrue(a is LinkAction.OpenBrowser)
            assertEquals("http://example.com", (a as LinkAction.OpenBrowser).uri)
            assertFalse(a.viaSafWhitelist)
        }

        @Test
        fun httpsOpensSystemBrowser() {
            val a = router.route("https://example.com")
            assertTrue(a is LinkAction.OpenBrowser)
        }

        @Test
        fun mailtoOpensSystemBrowser() {
            val a = router.route("mailto:a@b.com")
            assertTrue(a is LinkAction.OpenBrowser)
            assertEquals("mailto:a@b.com", (a as LinkAction.OpenBrowser).uri)
        }

        @Test
        fun anchorIsNone() {
            assertEquals(LinkAction.None, router.route("#note1"))
        }

        @Test
        fun unknownSchemeIsNone() {
            assertEquals(LinkAction.None, router.route("javascript:alert(1)"))
            assertEquals(LinkAction.None, router.route("about:blank"))
        }

        @Test
        fun relativePathIsNone() {
            assertEquals(LinkAction.None, router.route("chap03.xhtml"))
        }

        @Test
        fun safIsClosedByDefault() {
            assertEquals(
                LinkAction.None,
                router.route("content://media/external/images/1"),
            )
        }

        @Test
        fun invalidEpubCfiIsNone() {
            assertEquals(LinkAction.None, router.route("epubcfi:2"))
            assertEquals(LinkAction.None, router.route("epubcfi()"))
        }
    }

    @Nested
    @DisplayName("native capability upgrades")
    inner class Upgrades {

        @Test
        @DisplayName("a valid EPUBCFI link → InternalJump carrying the parsed Cfi")
        fun epubCfiJump() {
            val a = router.route("epubcfi(/6/4[chap01ref]!/4/2)")
            assertTrue(a is LinkAction.InternalJump)
            val jump = a as LinkAction.InternalJump
            assertEquals("epubcfi(/6/4[chap01ref]!/4/2)", jump.sourceHref)
        }

        @Test
        @DisplayName("a whitelisted SAF URI → OpenBrowser flagged viaSafWhitelist")
        fun whitelistedSaf() {
            val uri = "content://koodo.local/chapters/1"
            val r = LinkRouter(LinkPolicy(safWhitelist = setOf(uri)))
            val a = r.route(uri)
            assertTrue(a is LinkAction.OpenBrowser)
            assertTrue((a as LinkAction.OpenBrowser).viaSafWhitelist)
            assertEquals(uri, a.uri)
        }

        @Test
        @DisplayName("a non-whitelisted SAF URI stays closed even with a non-empty list")
        fun nonWhitelistedSaf() {
            val r = LinkRouter(LinkPolicy(safWhitelist = setOf("content://koodo.local/chapters/1")))
            assertEquals(LinkAction.None, r.route("content://koodo.local/chapters/2"))
        }
    }

    @Nested
    @DisplayName("policy kill-switches")
    inner class KillSwitches {

        @Test
        fun blockHttpButAllowHttps() {
            val r = LinkRouter(LinkPolicy(allowHttp = false))
            assertEquals(LinkAction.None, r.route("http://example.com"))
            assertTrue(r.route("https://example.com") is LinkAction.OpenBrowser)
        }

        @Test
        fun blockHttps() {
            val r = LinkRouter(LinkPolicy(allowHttps = false))
            assertEquals(LinkAction.None, r.route("https://example.com"))
        }

        @Test
        fun blockMailto() {
            val r = LinkRouter(LinkPolicy(allowMailto = false))
            assertEquals(LinkAction.None, r.route("mailto:a@b.com"))
        }

        @Test
        fun blockEpubCfiJump() {
            val r = LinkRouter(LinkPolicy(allowEpubCfiJump = false))
            assertEquals(LinkAction.None, r.route("epubcfi(/6/4[chap01ref]!/4/2)"))
        }

        @Test
        @DisplayName("explicitBrowserIntent emits OpenBrowserIntent instead")
        fun explicitBrowserIntent() {
            val r = LinkRouter(LinkPolicy(explicitBrowserIntent = true))
            val a = r.route("https://example.com")
            assertTrue(a is LinkAction.OpenBrowserIntent)
            assertEquals("https://example.com", (a as LinkAction.OpenBrowserIntent).uri)
        }

        @Test
        @DisplayName("disabling footnote falls through to the href")
        fun disableFootnote() {
            val r = LinkRouter(LinkPolicy(allowFootnote = false))
            val a = r.routeEvent(
                LinkClickEvent(href = "https://example.com", footnote = "some note"),
            )
            assertTrue(a is LinkAction.OpenBrowser)
        }
    }

    @Nested
    @DisplayName("event-level contract — footnote wins over href")
    inner class EventContract {

        @Test
        @DisplayName("a non-blank footnote → Footnote, href ignored")
        fun footnoteWins() {
            val a = router.routeEvent(
                LinkClickEvent(href = "https://example.com", footnote = "See reference 1"),
            )
            assertTrue(a is LinkAction.Footnote)
            assertEquals("See reference 1", (a as LinkAction.Footnote).text)
        }

        @Test
        @DisplayName("a blank footnote → the href is classified normally")
        fun blankFootnoteFallsThrough() {
            val a = router.routeEvent(LinkClickEvent(href = "https://example.com", footnote = "  "))
            assertTrue(a is LinkAction.OpenBrowser)
        }

        @Test
        @DisplayName("a footnote with an unopenable href and no footnote text → None")
        fun neither() {
            val a = router.routeEvent(LinkClickEvent(href = "chap03.xhtml", footnote = ""))
            assertEquals(LinkAction.None, a)
        }

        @Test
        @DisplayName("the two-arg routeEvent matches the event-object form")
        fun twoArgForm() {
            val a = router.routeEvent("mailto:a@b.com", "")
            assertTrue(a is LinkAction.OpenBrowser)
        }
    }
}
