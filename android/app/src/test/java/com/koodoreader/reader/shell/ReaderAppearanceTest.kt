package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAppearanceTest {

    @Test
    fun `js string escapes what would end the literal`() {
        assertEquals("\"a\\\\b\"", ReaderAppearance.jsString("a\\b"))
        assertEquals("\"say \\\"hi\\\"\"", ReaderAppearance.jsString("say \"hi\""))
        assertEquals("\"a\\nb\\rc\\td\"", ReaderAppearance.jsString("a\nb\rc\td"))
        // Line separators terminate a JS string literal before ES2019 and are
        // common in CJK documents.
        assertEquals("\"\\u2028\\u2029\"", ReaderAppearance.jsString("\u2028\u2029"))
    }

    @Test
    fun `css with quotes and newlines survives quoting`() {
        val css = "p { content: \"\\201C\"; }\nbody { margin: 0; }"
        val quoted = ReaderAppearance.jsString(css)
        assertTrue(quoted.startsWith("\""))
        assertTrue(quoted.endsWith("\""))
        // No raw newline inside the literal.
        assertTrue(!quoted.substring(1, quoted.length - 1).contains('\n'))
        assertTrue(quoted.contains("\\n"))
    }

    @Test
    fun `swap script swaps one element and restores scroll ratio`() {
        val js = ReaderAppearance.swapStyleJs("p{text-indent:2em}")
        assertTrue(js.contains("getElementById(\"koodo-reader-typography\")"))
        assertTrue(js.contains("s.id=\"koodo-reader-typography\""))
        assertTrue(js.contains("s.textContent=\"p{text-indent:2em}\""))
        // Ratio-based restore, not a hard-coded offset.
        assertTrue(js.contains("scrollHeight"))
        assertTrue(js.contains("ratio*maxY2"))
        assertTrue(js.startsWith("(function(){"))
        assertTrue(js.endsWith("})();"))
    }

    @Test
    fun `custom element id is honoured`() {
        val js = ReaderAppearance.swapStyleJs("body{}", elementId = "custom-typo")
        assertTrue(js.contains("\"custom-typo\""))
    }

    @Test
    fun `debounce stays inside the imperceptible band`() {
        // Long enough to collapse a slider drag, short enough to read as live.
        assertTrue(ReaderAppearance.SETTLE_MS in 80L..180L)
    }
}
