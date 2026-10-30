package com.koodoreader.reader.shell

import com.koodoreader.engine.feature.JsonValue
import com.koodoreader.engine.feature.TextRuleJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopPrefsBridgeTest {

    @Test
    fun `config export embeds rules and CSS as flat JSON strings`() {
        val state = ReaderFeatureState(
            textRulesJson = """[{"id":"1","type":"replace","pattern":"foo","matchType":"plain","replacement":"bar","enabled":true}]""",
            customBookCss = "h1{color:red}\np::before{content:\"\\\"\"}",
            customBookCssEnabled = true,
        )

        val root = TextRuleJson.parse(DesktopPrefsBridge.exportConfigJson(state)) as JsonValue.Obj

        assertEquals(setOf("textRuleList", "textRules", "readerConfig"), root.fields.keys)
        assertEquals("[\"1\"]", root.string("textRuleList"))
        val rules = TextRuleJson.parse(root.string("textRules")) as JsonValue.Obj
        val rule = rules.fields.getValue("1") as JsonValue.Obj
        assertEquals("foo", rule.string("pattern"))
        assertEquals("bar", rule.string("replacement"))
        val readerConfig = TextRuleJson.parse(root.string("readerConfig")) as JsonValue.Obj
        assertEquals(state.customBookCss, readerConfig.string("customBookCSS"))
        assertEquals("yes", readerConfig.string("isCustomBookCSS"))
    }

    @Test
    fun `config export handles blank rules and disabled CSS`() {
        val root = TextRuleJson.parse(
            DesktopPrefsBridge.exportConfigJson(ReaderFeatureState(customBookCss = "body{}")),
        ) as JsonValue.Obj

        assertEquals("[]", root.string("textRuleList"))
        assertEquals("{}", root.string("textRules"))
        val readerConfig = TextRuleJson.parse(root.string("readerConfig")) as JsonValue.Obj
        assertEquals("body{}", readerConfig.string("customBookCSS"))
        assertEquals("no", readerConfig.string("isCustomBookCSS"))
    }

    @Test
    fun `Android prefs export uses numeric version and real string maps`() {
        val feature = linkedMapOf("fontScale" to "1.25", "textRulesJson" to "[\"x\"]")
        val gestures = linkedMapOf("volumePageTurn" to "false", "tapEdgeFraction" to "0.3")

        val root = TextRuleJson.parse(
            DesktopPrefsBridge.exportAndroidPrefsJson(feature, gestures),
        ) as JsonValue.Obj

        assertEquals(JsonValue.Num(1.0), root.fields.getValue("version"))
        assertEquals(feature, root.stringMap("feature"))
        assertEquals(gestures, root.stringMap("gestures"))
    }

    @Test
    fun `desktop import applies valid rules and CSS`() {
        val exported = DesktopPrefsBridge.exportConfigJson(
            ReaderFeatureState(
                textRulesJson = """[{"id":"1","pattern":"foo","replacement":"bar"}]""",
                customBookCss = "h1{color:red}",
                customBookCssEnabled = true,
            ),
        )

        val restored = DesktopPrefsBridge.applyDesktopConfig(exported, currentFeature())

        assertTrue(restored.textRulesJson.contains("\"pattern\":\"foo\""))
        assertEquals("h1{color:red}", restored.customBookCss)
        assertTrue(restored.customBookCssEnabled)
        assertEquals(currentFeature().fontScale, restored.fontScale)
    }

    @Test
    fun `valid empty desktop rule set clears current rules`() {
        val config = desktopConfig(ruleList = "[]", rules = "{}")

        val restored = DesktopPrefsBridge.applyDesktopConfig(config, currentFeature())

        assertEquals("[]", restored.textRulesJson)
    }

    @Test
    fun `valid empty desktop CSS clears current CSS`() {
        val config = desktopConfig(
            readerConfig = """{"customBookCSS":"","isCustomBookCSS":"yes"}""",
        )

        val restored = DesktopPrefsBridge.applyDesktopConfig(config, currentFeature())

        assertEquals("", restored.customBookCss)
        assertTrue(restored.customBookCssEnabled)
    }

    @Test
    fun `valid disabled desktop CSS flag disables current CSS`() {
        val config = desktopConfig(
            readerConfig = """{"customBookCSS":"h1{color:blue}","isCustomBookCSS":"no"}""",
        )

        val restored = DesktopPrefsBridge.applyDesktopConfig(config, currentFeature())

        assertEquals("h1{color:blue}", restored.customBookCss)
        assertFalse(restored.customBookCssEnabled)
    }

    @Test
    fun `missing desktop fields preserve current values`() {
        val current = currentFeature()

        assertEquals(current, DesktopPrefsBridge.applyDesktopConfig("{}", current))
        assertEquals(current, DesktopPrefsBridge.applyDesktopConfig(null, current))
    }

    @Test
    fun `malformed desktop rules preserve rules while valid CSS imports`() {
        val current = currentFeature()
        val config = desktopConfig(
            ruleList = "[",
            rules = "{}",
            readerConfig = """{"customBookCSS":"p{margin:0}","isCustomBookCSS":"no"}""",
        )

        val restored = DesktopPrefsBridge.applyDesktopConfig(config, current)

        assertEquals(current.textRulesJson, restored.textRulesJson)
        assertEquals("p{margin:0}", restored.customBookCss)
        assertFalse(restored.customBookCssEnabled)
    }

    @Test
    fun `missing or malformed CSS fields preserve their current counterparts`() {
        val current = currentFeature()
        for (readerConfig in listOf(
            "{}",
            """{"customBookCSS":42,"isCustomBookCSS":"maybe"}""",
        )) {
            val restored = DesktopPrefsBridge.applyDesktopConfig(
                desktopConfig(readerConfig = readerConfig),
                current,
            )
            assertEquals(current.customBookCss, restored.customBookCss)
            assertEquals(current.customBookCssEnabled, restored.customBookCssEnabled)
        }
    }

    @Test
    fun `Android prefs version one restores feature and gesture maps`() {
        val current = currentFeature()
        val currentGestures = ReaderGestureConfig(
            tapEdgeFraction = 0.4f,
            swipePageTurn = false,
            volumePageTurn = true,
            volumeInverted = false,
        )
        val json = DesktopPrefsBridge.exportAndroidPrefsJson(
            feature = mapOf(
                "fontScale" to "1.8",
                "bionic" to "false",
                "customBookCss" to "",
                "customBookCssEnabled" to "false",
            ),
            gestures = mapOf(
                "tapEdgeFraction" to "0.25",
                "swipePageTurn" to "true",
                "volumeInverted" to "true",
            ),
        )

        val restored = DesktopPrefsBridge.restoreAndroidPrefs(json, current, currentGestures)

        assertTrue(restored.accepted)
        assertEquals(1.8f, restored.feature.fontScale)
        assertFalse(restored.feature.bionic)
        assertEquals("", restored.feature.customBookCss)
        assertFalse(restored.feature.customBookCssEnabled)
        assertEquals(0.25f, restored.gestures.tapEdgeFraction)
        assertTrue(restored.gestures.swipePageTurn)
        assertTrue(restored.gestures.volumePageTurn)
        assertTrue(restored.gestures.volumeInverted)
    }

    @Test
    fun `Android prefs partial maps preserve unimported current settings`() {
        val current = currentFeature()
        val gestures = ReaderGestureConfig(
            tapEdgeFraction = 0.4f,
            swipePageTurn = false,
            volumePageTurn = true,
            volumeInverted = true,
        )
        val json = DesktopPrefsBridge.exportAndroidPrefsJson(
            feature = mapOf("fontScale" to "1.7"),
            gestures = mapOf("volumePageTurn" to "false"),
        )

        val restored = DesktopPrefsBridge.restoreAndroidPrefs(json, current, gestures)

        assertEquals(current.copy(fontScale = 1.7f), restored.feature)
        assertEquals(gestures.copy(volumePageTurn = false), restored.gestures)
    }

    @Test
    fun `Android prefs unknown keys and invalid values are skipped`() {
        val current = currentFeature()
        val gestures = ReaderGestureConfig(
            tapEdgeFraction = 0.4f,
            swipePageTurn = false,
            volumePageTurn = true,
            volumeInverted = true,
        )
        val json = DesktopPrefsBridge.exportAndroidPrefsJson(
            feature = linkedMapOf(
                "fontScale" to "not-a-number",
                "lineHeightRatio" to "1.8",
                "bionic" to "not-a-boolean",
                "speedWpm" to "fast",
                "perfTier" to "",
                "unknown" to "default",
            ),
            gestures = linkedMapOf(
                "tapEdgeFraction" to "wide",
                "swipePageTurn" to "perhaps",
                "volumePageTurn" to "false",
                "unknown" to "default",
            ),
        )

        val restored = DesktopPrefsBridge.restoreAndroidPrefs(json, current, gestures)

        assertTrue(restored.accepted)
        assertEquals(current.copy(lineHeightRatio = 1.8f), restored.feature)
        assertEquals(gestures.copy(volumePageTurn = false), restored.gestures)
        assertTrue(restored.warnings.isNotEmpty())
    }

    @Test
    fun `missing Android prefs skip restoration without a warning`() {
        val current = currentFeature()
        val gestures = ReaderGestureConfig(volumePageTurn = false)

        val restored = DesktopPrefsBridge.restoreAndroidPrefs(null, current, gestures)

        assertFalse(restored.accepted)
        assertEquals(current, restored.feature)
        assertEquals(gestures, restored.gestures)
        assertTrue(restored.warnings.isEmpty())
    }

    @Test
    fun `blank Android prefs reject restoration with a warning`() {
        val current = currentFeature()
        val gestures = ReaderGestureConfig(volumePageTurn = false)

        val restored = DesktopPrefsBridge.restoreAndroidPrefs("  \n", current, gestures)

        assertFalse(restored.accepted)
        assertEquals(current, restored.feature)
        assertEquals(gestures, restored.gestures)
        assertTrue(restored.warnings.isNotEmpty())
    }

    @Test
    fun `malformed Android prefs reject restoration with a warning`() {
        val current = currentFeature()
        val gestures = ReaderGestureConfig(volumePageTurn = false)

        val restored = DesktopPrefsBridge.restoreAndroidPrefs("{", current, gestures)

        assertFalse(restored.accepted)
        assertEquals(current, restored.feature)
        assertEquals(gestures, restored.gestures)
        assertTrue(restored.warnings.isNotEmpty())
    }

    @Test
    fun `unknown Android prefs version rejects restoration with a warning`() {
        val current = currentFeature()
        val gestures = ReaderGestureConfig(volumePageTurn = false)
        val json = """{"version":2,"feature":{"fontScale":"2"},"gestures":{}}"""

        val restored = DesktopPrefsBridge.restoreAndroidPrefs(json, current, gestures)

        assertFalse(restored.accepted)
        assertEquals(current, restored.feature)
        assertEquals(gestures, restored.gestures)
        assertTrue(restored.warnings.isNotEmpty())
    }

    private fun currentFeature() = ReaderFeatureState(
        fontScale = 1.3f,
        lineHeightRatio = 1.6f,
        bionic = true,
        speedWpm = 450,
        textRulesJson = """[{"id":"old","pattern":"old","replacement":"value"}]""",
        customBookCss = "body{color:black}",
        customBookCssEnabled = true,
        perfTier = ReaderPerformancePolicy.TIER_LITE,
    )

    private fun desktopConfig(
        ruleList: String? = null,
        rules: String? = null,
        readerConfig: String? = null,
    ): String {
        val fields = linkedMapOf<String, JsonValue>()
        ruleList?.let { fields["textRuleList"] = JsonValue.Str(it) }
        rules?.let { fields["textRules"] = JsonValue.Str(it) }
        readerConfig?.let { fields["readerConfig"] = JsonValue.Str(it) }
        return TextRuleJson.render(JsonValue.Obj(fields))
    }

    private fun JsonValue.Obj.string(key: String): String =
        (fields.getValue(key) as JsonValue.Str).value

    private fun JsonValue.Obj.stringMap(key: String): Map<String, String> =
        (fields.getValue(key) as JsonValue.Obj).fields.mapValues { (_, value) ->
            (value as JsonValue.Str).value
        }
}
