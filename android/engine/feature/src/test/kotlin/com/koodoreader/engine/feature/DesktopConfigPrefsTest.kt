package com.koodoreader.engine.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopConfigPrefsTest {

    private fun rule(id: String) = TextRule(
        id = id, type = "replace", pattern = "foo", isRegex = false,
        replacement = "bar", scope = "all", enabled = true,
    )

    @Test
    fun `export produces desktop flat string map`() {
        val entries = DesktopConfigPrefs.desktopConfigEntries(listOf(rule("1")), "h1{color:red}", true)
        assertEquals("[\"1\"]", entries["textRuleList"])
        assertTrue(entries["textRules"]!!.contains("\"pattern\":\"foo\""))
        val rc = entries["readerConfig"]!!
        assertTrue(rc.contains("\"customBookCSS\":\"h1{color:red}\""))
        assertTrue(rc.contains("\"isCustomBookCSS\":\"yes\""))
    }

    @Test
    fun `round trip preserves rules and css`() {
        val entries = DesktopConfigPrefs.desktopConfigEntries(listOf(rule("1")), "p{margin:0}", false)
        val flat = entries.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":${Json.write(JsonValue.Str(v))}"
        }
        val parsed = DesktopConfigPrefs.parseDesktopConfig(flat)
        assertEquals(1, parsed.rules.size)
        assertEquals("foo", parsed.rules[0].pattern)
        assertEquals("p{margin:0}", parsed.customBookCss)
        assertEquals(false, parsed.customBookCssEnabled)
    }

    @Test
    fun `missing fields are skipped not cleared`() {
        val parsed = DesktopConfigPrefs.parseDesktopConfig("{\"unrelated\":\"{}\"}")
        assertTrue(parsed.rules.isEmpty())
        assertEquals("", parsed.customBookCss)
        assertEquals(false, parsed.customBookCssEnabled)
    }

    @Test
    fun `null or blank config yields empty prefs`() {
        assertTrue(DesktopConfigPrefs.parseDesktopConfig(null).rules.isEmpty())
        assertTrue(DesktopConfigPrefs.parseDesktopConfig("").rules.isEmpty())
    }
}
