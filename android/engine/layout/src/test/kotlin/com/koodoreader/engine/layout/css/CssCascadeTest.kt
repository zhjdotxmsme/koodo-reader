package com.koodoreader.engine.layout.css

import com.koodoreader.engine.layout.TextAlign
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class CssCascadeTest {

    @Test
    fun `inline style overrides external rule`() {
        val rules = CssParser.parse("p { font-size: 18px; color: #111; }")
        val element = CssElement(tag = "p", inline = "font-size: 22px")
        assertEquals(22f, CssCascade.styleFor(element, rules).fontSizePx)
    }

    @Test
    fun `bad rule does not hide following valid rule`() {
        val rules = CssParser.parse("p { ??? } h1 { text-align: center; }")
        assertEquals(1, rules.size)
        assertEquals("h1", rules.single().selector)
    }

    @Test
    fun `class selector applies to matching element`() {
        val rules = CssParser.parse(".lead { font-size: 20px; }")
        val element = CssElement(tag = "p", classes = setOf("lead"))
        assertEquals(20f, CssCascade.styleFor(element, rules).fontSizePx)
    }

    @Test
    fun `display none is surfaced`() {
        val rules = CssParser.parse("p.hidden { display: none; }")
        val element = CssElement(tag = "p", classes = setOf("hidden"))
        assertEquals(true, CssCascade.styleFor(element, rules).displayNone)
    }

    @Test
    fun `text-align maps to enum`() {
        val rules = CssParser.parse("h1 { text-align: center; }")
        val element = CssElement(tag = "h1")
        // Native v1 has no center column math; CSS center degrades to left.
        assertEquals(TextAlign.LEFT, CssCascade.styleFor(element, rules).textAlign)
    }
}
