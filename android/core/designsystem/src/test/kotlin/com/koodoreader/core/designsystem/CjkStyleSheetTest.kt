package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the injected reader stylesheet.
 *
 * These assertions are intentionally literal: the CSS is a contract with the
 * WebView, and a selector typo (a trailing comma, a missing `!important`)
 * fails silently in production — the page simply stops being typeset.
 */
class CjkStyleSheetTest {

    private fun build(
        gap: Float = 10f,
        indent: Boolean = true,
        letterSpacing: Float = 0f,
        justify: Boolean = true,
    ) = CjkStyleSheet.build(
        CjkStyleSheet.Rule(
            paragraphGapPx = gap,
            firstLineIndentEm = 2f,
            letterSpacingPx = letterSpacing,
            justify = justify,
            indentFirstLine = indent,
        ),
    )

    @Test
    fun `paragraphs are indented by two em and gapped below`() {
        val css = build()

        assertTrue(
            "p,blockquote,dd{margin-top:0 !important;margin-bottom:10px !important;text-indent:2em !important;" in css,
            css,
        )
    }

    @Test
    fun `no tracking is emitted at the CJK default`() {
        assertFalse("letter-spacing" in build(), "zero tracking must not be emitted at all")
    }

    @Test
    fun `explicit tracking is emitted on the body`() {
        assertTrue("body{letter-spacing:1.5px;text-align:justify;}" in build(letterSpacing = 1.5f))
    }

    @Test
    fun `justification can be turned off`() {
        assertFalse("text-align:justify" in build(justify = false))
    }

    @Test
    fun `indent can be turned off without losing the paragraph gap`() {
        val css = build(indent = false)

        assertFalse("text-indent:2em" in css)
        assertTrue("margin-bottom:10px !important;" in css)
    }

    @Test
    fun `zero gap collapses paragraph spacing but keeps the top reset`() {
        val css = build(gap = 0f)

        assertTrue("margin-top:0 !important;margin-bottom:0px !important;" in css, css)
        assertTrue("text-indent:2em !important;" in css)
    }

    @Test
    fun `structural div paragraphs are a separate rule so old webviews keep p rules`() {
        val css = build()

        // The :has() rule must stand alone: an unsupported selector drops only
        // that one rule, never the <p> rule above it.
        assertTrue("div:not(:has(*:not(" in css)
        assertTrue(css.lines().any { it.startsWith("div:not(:has(") }, css)
    }

    @Test
    fun `headings and list items never receive body indentation`() {
        val css = build()

        assertTrue("hgroup p,header p,li p,ol p,ul p,td p,th p,p.leading-image" in css)
        assertTrue("text-indent:0 !important;margin:0 !important;" in css)
    }

    @Test
    fun `cjk relaxes orphan and widow control`() {
        val css = build()

        assertTrue("widows:2;orphans:2;" in css, "Latin default must keep 2/2")
        assertTrue(":lang(zh),:lang(ja),:lang(ko){widows:1;orphans:1;" in css)
    }

    @Test
    fun `fractional values are formatted without a locale comma`() {
        val css = CjkStyleSheet.build(CjkStyleSheet.Rule(paragraphGapPx = 10.5f))

        assertTrue("margin-bottom:10.5px" in css, css)
    }
}
