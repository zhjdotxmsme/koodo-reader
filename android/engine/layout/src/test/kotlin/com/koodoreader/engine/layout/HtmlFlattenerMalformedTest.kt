package com.koodoreader.engine.layout

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Malformed-input tolerance (card acceptance [3]): 20+ corrupt inputs — none
 * may throw, and every one must degrade to sane linear text blocks.
 */
class HtmlFlattenerMalformedTest {

    private val flattener = DefaultHtmlFlattener()

    /** Runs [html] through the flattener; returns texts (crash = test failure). */
    private fun texts(html: String): List<String> = flattener.blocks(html).map { it.text }

    @Test
    fun `empty input yields no blocks`() {
        assertEquals(0, texts("").size)
    }

    @Test
    fun `plain text without tags becomes one linear block`() {
        assertEquals(listOf("plain text"), texts("plain text"))
    }

    @Test
    fun `bare less-than at eof is literal`() {
        assertEquals(listOf("<"), texts("<"))
    }

    @Test
    fun `comparison looks like text not markup`() {
        assertEquals(listOf("a < b"), texts("a < b"))
    }

    @Test
    fun `digit after less-than stays literal`() {
        assertEquals(listOf("5 <6 books"), texts("5 <6 books"))
    }

    @Test
    fun `unclosed paragraph still yields its text`() {
        assertEquals(listOf("unclosed"), texts("<p>unclosed"))
    }

    @Test
    fun `unclosed div with text degrades to a block`() {
        assertEquals(listOf("a"), texts("<div><p>a"))
    }

    @Test
    fun `paragraph auto-closes on nested paragraph`() {
        val bs = flattener.blocks("<div><p>a<p>b")
        assertEquals(listOf("a", "b"), bs.map { it.text })
    }

    @Test
    fun `stray close tag before text degrades linearly`() {
        assertTrue(texts("</p>orphan</div>").joinToString(" ").contains("orphan"))
    }

    @Test
    fun `mismatched close splits instead of crashing`() {
        val t = texts("<p>a</div>b</p>")
        assertEquals(listOf("a", "b"), t)
    }

    @Test
    fun `attribute with no value does not break the tag`() {
        assertEquals(listOf("c"), texts("<p hidden>c</p>"))
    }

    @Test
    fun `unquoted attribute value is accepted`() {
        val b = flattener.blocks("<p id=unq>x</p>").single()
        assertEquals("unq", b.elementId)
        assertEquals("x", b.text)
    }

    @Test
    fun `mangled quote soup does not crash`() {
        assertTrue(texts("<p \"a\">x</p>").contains("x"))
    }

    @Test
    fun `unterminated comment swallows the rest`() {
        // Text BEFORE the comment survives; the comment and everything after
        // (no `-->` terminator) is dropped.
        assertEquals(listOf("keep"), texts("keep<!--comment"))
    }

    @Test
    fun `unterminated comment swallows tags inside it`() {
        assertEquals(listOf("keep"), texts("keep<!-- <p>no</p>"))
    }

    @Test
    fun `unterminated script swallows the rest`() {
        assertEquals(0, texts("<script>alert(1)</scr").size)
    }

    @Test
    fun `unterminated style swallows the rest`() {
        assertEquals(0, texts("<style>p{}</sty").size)
    }

    @Test
    fun `unterminated tag at eof is dropped`() {
        assertEquals(listOf("safe"), texts("<p>safe</p><div"))
    }

    @Test
    fun `tag soup garbage stays text`() {
        val t = texts("<<<>>>")
        assertTrue(t.joinToString("").contains("<"))
    }

    @Test
    fun `broken numeric entity stays literal`() {
        assertEquals(listOf("&#"), texts("&#"))
    }

    @Test
    fun `hex entity with bad digits stays literal`() {
        assertEquals(listOf("&#xZZ;"), texts("&#xZZ;"))
    }

    @Test
    fun `legacy uppercase amp entity decodes like browsers`() {
        // HTML5 named references are case-insensitive; &AMP; is legacy-valid.
        assertEquals(listOf("&"), texts("&AMP;"))
    }

    @Test
    fun `deeply nested divs flatten without stack overflow`() {
        val depth = 500
        val html = "<div>".repeat(depth) + "core" + "</div>".repeat(depth)
        assertEquals(listOf("core"), texts(html))
    }

    @Test
    fun `newline and tab soup collapses`() {
        assertEquals(listOf("a b c"), texts("<p>a\n\t b\r\nc</p>"))
    }

    @Test
    fun `close tag with junk attributes is tolerated`() {
        assertEquals(listOf("x"), texts("<p>x</p weird>"))
    }

    @Test
    fun `entity at eof without semicolon degrades`() {
        assertTrue(texts("amp &copy").joinToString("").isNotEmpty())
    }

    @Test
    fun `null-ish control characters do not crash`() {
        val html = "<p>a\u0000b</p>"
        assertTrue(texts(html).single().contains("a"))
    }

    @Test
    fun `thousand broken tags still terminate fast`() {
        // One unterminated <p tag running to EOF (browser behaviour: the
        // whole token is dropped), terminated, then real text.
        val html = "<p <p <p ".repeat(1000) + ">end"
        assertEquals(listOf("end"), texts(html))
    }
}


