package com.koodoreader.engine.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BionicReadingTest {

    private fun runs(text: String, config: BionicConfig = BionicConfig()): List<BionicRun> =
        BionicReading.split(text, config)

    private fun rebuild(text: String, config: BionicConfig = BionicConfig()): String =
        runs(text, config).joinToString("") { it.text }

    @Test
    fun `empty text yields no runs`() {
        assertTrue(runs("").isEmpty())
    }

    @Test
    fun `latin word splits into emphasised prefix and plain tail`() {
        // boldChars = 3 → "hello" → "hel" + "lo"
        assertEquals(
            listOf(
                BionicRun("hel", true),
                BionicRun("lo ", false),
                BionicRun("wor", true),
                BionicRun("ld", false),
            ),
            runs("hello world"),
        )
    }

    @Test
    fun `word shorter than the emphasis length is a single emphasised run`() {
        assertEquals(
            listOf(
                BionicRun("hi ", true),
                BionicRun("the", true),
                BionicRun("re", false),
            ),
            runs("hi there"),
        )
    }

    @Test
    fun `bold char count is configurable and never below one`() {
        assertEquals(
            listOf(BionicRun("h", true), BionicRun("ello", false)),
            runs("hello", BionicConfig(boldChars = 1)),
        )
        // 0 clamps to 1 instead of producing a non-emphasised run.
        assertEquals(
            listOf(BionicRun("h", true), BionicRun("ello", false)),
            runs("hello", BionicConfig(boldChars = 0)),
        )
        assertEquals(
            listOf(BionicRun("hell", true), BionicRun("o", false)),
            runs("hello", BionicConfig(boldChars = 4)),
        )
    }

    @Test
    fun `half word strategy matches the desktop isBionic behaviour`() {
        assertEquals(
            listOf(BionicRun("read", true), BionicRun("ing", false)),
            runs("reading", BionicConfig.DESKTOP_PARITY),
        )
        // length 2 → ceil(1) = 1
        assertEquals(
            listOf(BionicRun("h", true), BionicRun("i", false)),
            runs("hi", BionicConfig.DESKTOP_PARITY),
        )
        assertEquals(4, BionicReading.emphasisLength("reading", BionicConfig.DESKTOP_PARITY))
        assertEquals(0, BionicReading.emphasisLength("", BionicConfig.DESKTOP_PARITY))
    }

    @Test
    fun `cjk characters are split per character and punctuation joins the previous run`() {
        assertEquals(
            listOf(
                BionicRun("\u4f60", false),
                BionicRun("\u597d\uff0c", false),
                BionicRun("\u4e16", false),
                BionicRun("\u754c\u3002", false),
            ),
            runs("\u4f60\u597d\uff0c\u4e16\u754c\u3002"),
        )
    }

    @Test
    fun `mixed cjk and latin text keeps both scripts intact`() {
        assertEquals(
            listOf(
                BionicRun("Hel", true),
                BionicRun("lo ", false),
                BionicRun("\u4e16", false),
                BionicRun("\u754c", false),
            ),
            runs("Hello \u4e16\u754c"),
        )
    }

    @Test
    fun `cjk emphasis policies`() {
        val text = "\u4f60\u597d\u4e16\u754c"
        assertEquals(
            listOf(false, false, false, false),
            runs(text, BionicConfig(cjkEmphasis = CjkEmphasis.NONE)).map { it.emphasized },
        )
        assertEquals(
            listOf(true, true, true, true),
            runs(text, BionicConfig(cjkEmphasis = CjkEmphasis.ALL)).map { it.emphasized },
        )
        assertEquals(
            listOf(true, true, true, false),
            runs(text, BionicConfig(cjkEmphasis = CjkEmphasis.SEQUENCE_LEADING)).map { it.emphasized },
        )
        assertEquals(
            listOf(true, false, false, false),
            runs(text, BionicConfig(boldChars = 1, cjkEmphasis = CjkEmphasis.SEQUENCE_LEADING))
                .map { it.emphasized },
        )
    }

    @Test
    fun `punctuation only input yields one plain run`() {
        assertEquals(listOf(BionicRun("...", false)), runs("..."))
        assertEquals(listOf(BionicRun(" ", false)), runs(" "))
        assertFalse(runs("!!!")[0].emphasized)
    }

    @Test
    fun `super long word only emphasises the configured prefix`() {
        val word = "a".repeat(100)
        val result = runs(word)
        assertEquals(2, result.size)
        assertEquals(3, result[0].text.length)
        assertTrue(result[0].emphasized)
        assertEquals(97, result[1].text.length)
        assertFalse(result[1].emphasized)
    }

    @Test
    fun `word internal apostrophe stays inside the word`() {
        // "don't" is one word of length 5 → "don" + "'t"
        assertEquals(
            listOf(BionicRun("don", true), BionicRun("'t", false)),
            runs("don't"),
        )
    }

    @Test
    fun `split is lossless for a batch of samples`() {
        val samples = listOf(
            "",
            " ",
            "hello",
            "hello world",
            "a",
            "Hello \u4e16\u754c\uff01",
            "\u4f60\u597d\u4e16\u754c\u3002",
            "line1\nline2",
            "supercalifragilisticexpialidocious",
            "  leading spaces  ",
            "mixed \u4e2d\u6587 and English, with punctuation!",
        )
        for (sample in samples) {
            assertEquals(sample, rebuild(sample), "lossless split failed for '$sample'")
            assertEquals(
                sample,
                rebuild(sample, BionicConfig(emphasis = BionicEmphasis.HALF_WORD)),
                "lossless half-word split failed for '$sample'",
            )
            assertEquals(
                sample,
                rebuild(sample, BionicConfig(cjkEmphasis = CjkEmphasis.ALL)),
                "lossless cjk split failed for '$sample'",
            )
        }
    }

    @Test
    fun `toHtml wraps emphasised runs and escapes markup`() {
        assertEquals("<b>goo</b>d", BionicReading.toHtml("good"))
        assertEquals("<b>a&lt;</b><b>b</b>", BionicReading.toHtml("a<b"))
        assertEquals("", BionicReading.toHtml(""))
    }
}
