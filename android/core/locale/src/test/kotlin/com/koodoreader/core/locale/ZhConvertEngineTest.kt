package com.koodoreader.core.locale

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tri-state 简繁转换 behaviour (P6 acceptance item ①): 自动 / 繁 / 简, desktop
 * wire compatibility, OpenCC chain semantics and the documented ambiguities.
 */
class ZhConvertEngineTest {

    @Test
    @DisplayName("tri-state wire values match the desktop reader config exactly")
    fun `mode wire values match the desktop reader config`() {
        assertEquals("", ZhConvertMode.AUTO.wire) // desktop dropdown "Default"
        assertEquals("Simplified To Traditional", ZhConvertMode.TRADITIONAL.wire)
        assertEquals("Traditional To Simplified", ZhConvertMode.SIMPLIFIED.wire)

        assertEquals(ZhConvertMode.TRADITIONAL, ZhConvertMode.fromWire("Simplified To Traditional"))
        assertEquals(ZhConvertMode.SIMPLIFIED, ZhConvertMode.fromWire(" Traditional To Simplified "))
        assertEquals(ZhConvertMode.AUTO, ZhConvertMode.fromWire(""))
        assertEquals(ZhConvertMode.AUTO, ZhConvertMode.fromWire(null))
        assertEquals(ZhConvertMode.AUTO, ZhConvertMode.fromWire("no such mode"))
    }

    @Test
    @DisplayName("简 → 繁 converts characters and disambiguating phrases")
    fun `simplified converts to traditional`() {
        assertEquals(TRADITIONAL, engine.toTraditional(SIMPLIFIED))
    }

    @Test
    @DisplayName("繁 → 简 converts characters and disambiguating phrases")
    fun `traditional converts to simplified`() {
        assertEquals(SIMPLIFIED, engine.toSimplified(TRADITIONAL))
    }

    @Test
    @DisplayName("s2tw runs the Taiwan phrase stage after the character stage")
    fun `taiwan localisation applies after the character stage`() {
        assertEquals(TRADITIONAL_TW, engine.toTraditional(SIMPLIFIED, taiwan = true))
    }

    @Test
    @DisplayName("tw2s reverses Taiwan terms before simplifying")
    fun `tw2s maps taiwan terms back before simplifying`() {
        assertEquals(SIMPLIFIED, engine.toSimplified(TRADITIONAL_TW, fromTaiwan = true))
    }

    @Test
    @DisplayName("phrase table wins over the character table (干/后/只/面/划/复/准/脏/历/于)")
    fun `phrase table wins over the character table`() {
        assertEquals("乾淨", engine.toTraditional("干净"))
        assertEquals("幹活", engine.toTraditional("干活"))
        assertEquals("餅乾", engine.toTraditional("饼干"))
        assertEquals("干擾", engine.toTraditional("干扰")) // 干 stays 干 in 干擾
        assertEquals("干涉", engine.toTraditional("干涉"))
        assertEquals("後來", engine.toTraditional("后来"))
        assertEquals("皇后", engine.toTraditional("皇后")) // 后 (queen) must NOT become 後
        assertEquals("一隻", engine.toTraditional("一只"))
        assertEquals("只有", engine.toTraditional("只有"))
        assertEquals("麵條", engine.toTraditional("面条"))
        assertEquals("計劃", engine.toTraditional("计划"))
        assertEquals("複雜", engine.toTraditional("复杂"))
        assertEquals("準備", engine.toTraditional("准备"))
        assertEquals("心臟", engine.toTraditional("心脏"))
        assertEquals("日曆", engine.toTraditional("日历"))
        assertEquals("由於", engine.toTraditional("由于"))
    }

    @Test
    @DisplayName("ambiguous standalone characters stay unchanged (documented subset limit)")
    fun `ambiguous standalone characters stay unchanged`() {
        // 干/里/只/台/面/划 are resolved by the phrase table only: the curated
        // seed cannot decide them in isolation, and guessing corrupts text
        // (公里, 皇后, 台风). The full upstream STPhrases pack resolves these.
        assertEquals("干", engine.toTraditional("干"))
        assertEquals("里", engine.toTraditional("里"))
        assertEquals("只", engine.toTraditional("只"))
        assertEquals("台", engine.toTraditional("台"))
        assertEquals("面", engine.toTraditional("面"))
        assertEquals("划", engine.toTraditional("划"))
    }

    @Test
    @DisplayName("auto follows the reader language and never touches non-Chinese")
    fun `auto follows the reader language`() {
        // zh-CN reader + traditional text → simplified
        assertEquals(SIMPLIFIED, engine.convert(TRADITIONAL, ZhConvertMode.AUTO, "zh-CN"))
        // zh-TW reader + simplified text → traditional, Taiwan terms included
        assertEquals(TRADITIONAL_TW, engine.convert(SIMPLIFIED, ZhConvertMode.AUTO, "zh-TW"))
        // zh-MO / zh-HK are traditional too
        assertTrue(engine.convert(SIMPLIFIED, ZhConvertMode.AUTO, "zh-HK").startsWith("後來"))
        // non-Chinese reader language: byte-for-byte no-op (desktop "Default")
        assertEquals(SIMPLIFIED, engine.convert(SIMPLIFIED, ZhConvertMode.AUTO, "en"))
        assertEquals(SIMPLIFIED, engine.convert(SIMPLIFIED, ZhConvertMode.AUTO, null))
        assertEquals(TRADITIONAL, engine.convert(TRADITIONAL, ZhConvertMode.AUTO, "de"))
    }

    @Test
    @DisplayName("auto is a no-op when the text already matches the reader language")
    fun `auto is a no-op when the script matches`() {
        // zh-CN reader + simplified text, zh-TW reader + Taiwan text,
        // zh-HK reader + traditional text: already the target script.
        assertEquals(SIMPLIFIED, engine.convert(SIMPLIFIED, ZhConvertMode.AUTO, "zh-CN"))
        assertEquals(TRADITIONAL_TW, engine.convert(TRADITIONAL_TW, ZhConvertMode.AUTO, "zh-TW"))
        assertEquals(TRADITIONAL, engine.convert(TRADITIONAL, ZhConvertMode.AUTO, "zh-HK"))
    }

    @Test
    @DisplayName("forced modes convert regardless of detection and language")
    fun `forced modes ignore detection`() {
        assertEquals(TRADITIONAL, engine.convert(SIMPLIFIED, ZhConvertMode.TRADITIONAL, "en"))
        assertEquals(SIMPLIFIED, engine.convert(TRADITIONAL, ZhConvertMode.SIMPLIFIED, "en"))
        assertEquals(TRADITIONAL, engine.convert(SIMPLIFIED, ZhConvertMode.TRADITIONAL, null))
        assertEquals(TRADITIONAL_TW, engine.convert(SIMPLIFIED, ZhConvertMode.TRADITIONAL, "zh-TW"))
        assertEquals(SIMPLIFIED, engine.convert(TRADITIONAL_TW, ZhConvertMode.SIMPLIFIED, "zh-TW"))
    }

    @Test
    @DisplayName("script detection needs a decisive signal (min 4 hits, 2x lead)")
    fun `script detection needs decisive evidence`() {
        assertEquals(ChineseScript.SIMPLIFIED, engine.detect(SIMPLIFIED))
        assertEquals(ChineseScript.TRADITIONAL, engine.detect(TRADITIONAL))
        assertEquals(ChineseScript.UNKNOWN, engine.detect("后来")) // 1 hit < MIN_SIGNAL
        assertEquals(ChineseScript.UNKNOWN, engine.detect("Hello 世界 123"))
        assertEquals(ChineseScript.UNKNOWN, engine.detect(""))

        val signal = engine.signal(SIMPLIFIED)
        assertEquals(0, signal.traditional)
        assertTrue(signal.simplified >= ScriptSignal.MIN_SIGNAL)
        assertEquals(ChineseScript.SIMPLIFIED, signal.script())
    }

    @Test
    @DisplayName("non-Han text, punctuation and surrogate pairs are preserved")
    fun `non-han content is preserved`() {
        val rare = "Hello, world! 123 — " + String(Character.toChars(0x20000)) +
            " " + String(Character.toChars(0x2A6B2)) + " ok"
        assertEquals(rare, engine.toTraditional(rare))
        assertEquals(rare, engine.toSimplified(rare))
        assertEquals("", engine.toTraditional(""))
        assertEquals("   ", engine.toTraditional("   "))
    }

    @Test
    @DisplayName("user dictionary overrides built-in tables and its spans are protected")
    fun `user dictionary overrides and is protected`() {
        val custom = ZhConvertEngine.fromSeed("计划\t計畫")
        assertTrue(custom.hasUserDictionary)
        // built-in phrase would give 計劃; the user dictionary wins
        assertEquals(
            "後來我在計畫裏面寫了一個軟件",
            custom.toTraditional("后来我在计划里面写了一个软件"),
        )

        // identity rule = "keep this word as it is": only a protected span can
        // stop the built-in 里面 → 裏面 rule from firing.
        val keeper = ZhConvertEngine.fromSeed("里面\t里面")
        assertEquals(
            "後來我在計劃里面寫了一個軟件",
            keeper.toTraditional("后来我在计划里面写了一个软件"),
        )

        // user rules also apply to the T→S direction, and win over the built-in
        // chain: without the rule the character table yields the Taiwan spelling
        // 计画, with it the user keeps 计划.
        val reverseRule = ZhConvertEngine.fromSeed("計畫\t计划")
        assertEquals("计划", reverseRule.toSimplified("計畫"))
        assertEquals("计画", ZhConvertEngine.fromSeed().toSimplified("計畫"))
        // ... while the tw2s direction resolves the Taiwan term via TWPhrasesRev
        assertEquals("计划", ZhConvertEngine.fromSeed().toSimplified("計畫", fromTaiwan = true))
    }

    @Test
    @DisplayName("blank user dictionary means no user stage")
    fun `blank user dictionary is ignored`() {
        assertFalse(ZhConvertEngine.fromSeed().hasUserDictionary)
        assertFalse(ZhConvertEngine.fromSeed("   ").hasUserDictionary)
        assertFalse(ZhConvertEngine.fromSeed("# only a comment\n").hasUserDictionary)
        assertEquals("計劃", ZhConvertEngine.fromSeed("   ").toTraditional("计划"))
    }

    @Test
    @DisplayName("convertWire accepts the desktop config value directly")
    fun `convertWire accepts desktop values`() {
        assertEquals(TRADITIONAL, engine.convertWire(SIMPLIFIED, "Simplified To Traditional"))
        assertEquals(SIMPLIFIED, engine.convertWire(TRADITIONAL, "Traditional To Simplified"))
        assertEquals(SIMPLIFIED, engine.convertWire(SIMPLIFIED, "")) // desktop default / auto
        assertEquals(TRADITIONAL, engine.convertWire(SIMPLIFIED, null, "zh-TW"))
    }

    @Test
    @DisplayName("language → script mapping covers the desktop Chinese locales")
    fun `language to script mapping`() {
        assertEquals(ChineseScript.SIMPLIFIED, ZhConvertEngine.scriptOf("zh-CN"))
        assertEquals(ChineseScript.SIMPLIFIED, ZhConvertEngine.scriptOf("zh_cn"))
        assertEquals(ChineseScript.TRADITIONAL, ZhConvertEngine.scriptOf("zh-TW"))
        assertEquals(ChineseScript.TRADITIONAL, ZhConvertEngine.scriptOf("zh-MO"))
        assertEquals(ChineseScript.UNKNOWN, ZhConvertEngine.scriptOf("en"))
        assertEquals(ChineseScript.UNKNOWN, ZhConvertEngine.scriptOf(null))
        assertTrue(ZhConvertEngine.isTaiwan("zh-TW"))
        assertFalse(ZhConvertEngine.isTaiwan("zh-CN"))
    }

    private companion object {
        /** 简: 后来我在计划里面写了一个软件，网络也很好。 */
        const val SIMPLIFIED = "后来我在计划里面写了一个软件，网络也很好。"

        /** 繁: 後來我在計劃裏面寫了一個軟件，網絡也很好。 */
        const val TRADITIONAL = "後來我在計劃裏面寫了一個軟件，網絡也很好。"

        /** 繁（台湾用词）: 後來我在計畫裏面寫了一個軟體，網路也很好。 */
        const val TRADITIONAL_TW = "後來我在計畫裏面寫了一個軟體，網路也很好。"

        val engine: ZhConvertEngine = ZhConvertEngine.fromSeed()
    }
}
