package com.koodoreader.engine.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextRuleJsonTest {

    private val sample = listOf(
        TextRule(id = "1", pattern = "foo", replacement = "bar"),
        TextRule(
            id = "2",
            pattern = "(\\d+)",
            replacement = "\$1!",
            isRegex = true,
            highlightStyle = null,
        ),
        TextRule(
            id = "3",
            pattern = "secret",
            replacement = "",
            enabled = false,
            isRegex = false,
            type = TextRule.TYPE_DELETE,
        ),
        TextRule(
            id = "4",
            pattern = "note",
            isRegex = true,
            type = TextRule.TYPE_HIGHLIGHT,
            scope = TextRule.SCOPE_BOOK,
            bookKey = "book-key",
            bookName = "A Book",
            highlightStyle = "underline",
            highlightColor = "#ffff00",
        ),
    )

    @Test
    fun `rules round trip through the bare array form`() {
        val json = TextRuleJson.exportRules(sample)
        val result = TextRuleJson.importRules(json)
        assertEquals(sample, result.rules)
        assertFalse(result.hasWarnings)
    }

    @Test
    fun `rules round trip through the desktop config envelope`() {
        val json = TextRuleJson.exportConfig(sample)
        val result = TextRuleJson.importConfig(json)
        assertEquals(sample, result.rules)
        assertFalse(result.hasWarnings)
    }

    @Test
    fun `pretty printed output round trips too`() {
        val json = TextRuleJson.exportConfig(sample, pretty = true)
        assertTrue(json.contains("\n"))
        assertEquals(sample, TextRuleJson.importRules(json).rules)
    }

    @Test
    fun `exported json uses the desktop config keys`() {
        val json = TextRuleJson.exportConfig(sample)
        assertTrue(json.contains("\"${TextRuleJson.KEY_RULE_LIST}\""), json)
        assertTrue(json.contains("\"${TextRuleJson.KEY_RULES}\""), json)
        assertTrue(json.contains("\"matchType\":\"plain\""), json)
        assertTrue(json.contains("\"matchType\":\"regex\""), json)
        assertTrue(json.contains("\"type\":\"replace\""), json)
        assertTrue(json.contains("\"scope\":\"book\""), json)
        assertTrue(json.contains("\"enabled\":false"), json)
        assertTrue(json.contains("\"highlightColor\":\"#ffff00\""), json)

        // the list order is the application order
        val listIndex = json.indexOf("\"1\"")
        val secondIndex = json.indexOf("\"2\"", listIndex)
        assertTrue(listIndex in 0 until secondIndex)
    }

    @Test
    fun `imports a hand written desktop config`() {
        val desktop = """
            {"textRuleList":["a"],
             "textRules":{"a":{"id":"a","type":"replace","pattern":"x","replacement":"y","matchType":"plain","scope":"all"}}}
        """.trimIndent()
        val result = TextRuleJson.importRules(desktop)
        assertFalse(result.hasWarnings)
        assertEquals(1, result.rules.size)
        val rule = result.rules[0]
        assertEquals("a", rule.id)
        assertEquals("x", rule.pattern)
        assertEquals("y", rule.replacement)
        assertFalse(rule.isRegex)
        assertTrue(rule.enabled)
        assertEquals(TextRule.TYPE_REPLACE, rule.type)
    }

    @Test
    fun `imports isRegex flags and yes no style booleans`() {
        val json = """
            [{"id":"a","pattern":"x","isRegex":true,"enabled":"no"},
             {"id":"b","pattern":"y","matchType":"regex"}]
        """.trimIndent()
        val rules = TextRuleJson.importRules(json).rules
        assertEquals(2, rules.size)
        assertTrue(rules[0].isRegex)
        assertFalse(rules[0].enabled)
        assertTrue(rules[1].isRegex)
        assertTrue(rules[1].enabled)
    }

    @Test
    fun `malformed entries are skipped with warnings instead of throwing`() {
        val json = """[{"pattern":"no-id"},{"id":"b","pattern":"c"},42,"nope"]"""
        val result = TextRuleJson.importRules(json)
        assertEquals(1, result.rules.size)
        assertEquals("b", result.rules[0].id)
        assertEquals(3, result.warningCount)
    }

    @Test
    fun `invalid json yields an empty result with a warning`() {
        val result = TextRuleJson.importRules("{oops")
        assertTrue(result.rules.isEmpty())
        assertEquals(1, result.warningCount)
        assertTrue(result.warnings[0].startsWith("invalid JSON"))
    }

    @Test
    fun `rules missing from the id list are still imported`() {
        val json = """
            {"textRuleList":[],
             "textRules":{"z":{"id":"z","pattern":"p","matchType":"plain"}}}
        """.trimIndent()
        val result = TextRuleJson.importRules(json)
        assertEquals(1, result.rules.size)
        assertEquals("z", result.rules[0].id)
        assertTrue(result.hasWarnings)
    }

    @Test
    fun `empty rule list round trips`() {
        assertEquals(emptyList<TextRule>(), TextRuleJson.importRules(TextRuleJson.exportConfig(emptyList())).rules)
        assertEquals(emptyList<TextRule>(), TextRuleJson.importRules("[]").rules)
    }

    @Test
    fun `special characters survive the round trip`() {
        val rules = listOf(
            TextRule(
                id = "quote\"slash\\",
                pattern = "line1\nline2\ttab",
                replacement = "a\"b\\c\$d",
            ),
        )
        val json = TextRuleJson.exportRules(rules)
        assertEquals(rules, TextRuleJson.importRules(json).rules)
    }
}
