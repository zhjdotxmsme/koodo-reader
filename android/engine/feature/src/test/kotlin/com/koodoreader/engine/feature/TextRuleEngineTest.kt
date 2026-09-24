package com.koodoreader.engine.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextRuleEngineTest {

    private val engine = TextRuleEngine()

    private fun rule(
        id: String = "r",
        pattern: String,
        replacement: String = "",
        enabled: Boolean = true,
        isRegex: Boolean = false,
        type: String = TextRule.TYPE_REPLACE,
    ) = TextRule(
        id = id,
        pattern = pattern,
        replacement = replacement,
        enabled = enabled,
        isRegex = isRegex,
        type = type,
    )

    @Test
    fun `plain rule replaces every literal occurrence`() {
        val result = engine.apply("foo bar foo", listOf(rule(pattern = "foo", replacement = "baz")))
        assertEquals("baz bar baz", result.text)
        assertFalse(result.hasWarnings)
    }

    @Test
    fun `plain rules treat the pattern literally`() {
        val result = engine.apply("a.b axb", listOf(rule(pattern = "a.b", replacement = "-")))
        assertEquals("- axb", result.text)
    }

    @Test
    fun `plain replacement does not interpret dollar group references`() {
        val result = engine.apply("price", listOf(rule(pattern = "price", replacement = "\$1")))
        assertEquals("\$1", result.text)
    }

    @Test
    fun `regex rule keeps group references in the replacement`() {
        val result = engine.apply(
            "2024-01-02",
            listOf(
                rule(
                    pattern = "(\\d{4})-(\\d{2})-(\\d{2})",
                    replacement = "\$3/\$2/\$1",
                    isRegex = true,
                ),
            ),
        )
        assertEquals("02/01/2024", result.text)
        assertFalse(result.hasWarnings)
    }

    @Test
    fun `rules are applied in order and feed the next rule`() {
        val result = engine.apply(
            "a",
            listOf(
                rule(id = "1", pattern = "a", replacement = "b"),
                rule(id = "2", pattern = "b", replacement = "c"),
            ),
        )
        assertEquals("c", result.text)
    }

    @Test
    fun `disabled rules are skipped`() {
        val result = engine.apply(
            "a",
            listOf(rule(pattern = "a", replacement = "b", enabled = false)),
        )
        assertEquals("a", result.text)
        assertFalse(result.hasWarnings)
    }

    @Test
    fun `highlight rules never mutate the text`() {
        val rules = listOf(
            rule(pattern = "a", replacement = "X", type = TextRule.TYPE_HIGHLIGHT),
            rule(id = "2", pattern = "a", replacement = "Y"),
        )
        assertEquals("Y", engine.apply("a", rules).text)
        assertEquals("a", engine.apply("a", rules.take(1)).text)
    }

    @Test
    fun `delete rules remove their matches`() {
        val result = engine.apply(
            "foobarbaz",
            listOf(rule(pattern = "bar", replacement = "IGNORED", type = TextRule.TYPE_DELETE)),
        )
        assertEquals("foobaz", result.text)
    }

    @Test
    fun `invalid regex is skipped and reported as a warning`() {
        val result = engine.apply(
            "abc",
            listOf(
                rule(id = "bad", pattern = "[", isRegex = true),
                rule(id = "good", pattern = "b", replacement = "B"),
            ),
        )
        assertEquals("aBc", result.text)
        assertEquals(1, result.warningCount)
        assertTrue(result.warnings[0].contains("invalid"))
        assertTrue(result.warnings[0].contains("bad"))
    }

    @Test
    fun `empty pattern is skipped and reported as a warning`() {
        val result = engine.apply("abc", listOf(rule(id = "empty", pattern = "")))
        assertEquals("abc", result.text)
        assertEquals(1, result.warningCount)
        assertTrue(result.warnings[0].contains("empty pattern"))
    }

    @Test
    fun `case sensitivity is configurable`() {
        val caseSensitive = TextRuleEngine(caseSensitive = true)
        val insensitive = TextRuleEngine(caseSensitive = false)
        val rules = listOf(rule(pattern = "foo", replacement = "bar"))

        assertEquals("FOO bar", caseSensitive.apply("FOO foo", rules).text)
        assertEquals("bar bar", insensitive.apply("FOO foo", rules).text)
    }

    @Test
    fun `blank and empty input is returned untouched`() {
        assertEquals("", engine.apply("", emptyList()).text)
        assertEquals("", engine.apply("", listOf(rule(pattern = "a", replacement = "b"))).text)
        assertEquals("   ", engine.apply("   ", listOf(rule(pattern = "a", replacement = "b"))).text)
    }

    @Test
    fun `validate reports compilable patterns`() {
        assertTrue(engine.validate("a+b", true))
        assertTrue(engine.validate("[", false))
        assertFalse(engine.validate("[", true))
        assertFalse(engine.validate("(unclosed", true))
    }
}
