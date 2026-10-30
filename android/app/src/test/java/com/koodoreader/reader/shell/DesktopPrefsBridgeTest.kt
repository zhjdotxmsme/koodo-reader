package com.koodoreader.reader.shell

import com.koodoreader.engine.feature.JsonValue
import com.koodoreader.engine.feature.TextRuleJson
import org.junit.jupiter.api.Assertions.assertEquals
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

    private fun JsonValue.Obj.string(key: String): String =
        (fields.getValue(key) as JsonValue.Str).value

    private fun JsonValue.Obj.stringMap(key: String): Map<String, String> =
        (fields.getValue(key) as JsonValue.Obj).fields.mapValues { (_, value) ->
            (value as JsonValue.Str).value
        }
}
