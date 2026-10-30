package com.koodoreader.reader.shell

import com.koodoreader.engine.feature.DesktopConfigPrefs
import com.koodoreader.engine.feature.JsonValue
import com.koodoreader.engine.feature.TextRuleJson

object DesktopPrefsBridge {

    fun exportConfigJson(state: ReaderFeatureState): String {
        val rules = if (state.textRulesJson.isBlank()) {
            emptyList()
        } else {
            TextRuleJson.importRules(state.textRulesJson).rules
        }
        val entries = DesktopConfigPrefs.desktopConfigEntries(
            rules,
            state.customBookCss,
            state.customBookCssEnabled,
        )
        return TextRuleJson.render(JsonValue.Obj(entries.mapValues { (_, value) -> JsonValue.Str(value) }))
    }

    fun exportAndroidPrefsJson(feature: Map<String, String>, gestures: Map<String, String>): String =
        TextRuleJson.render(JsonValue.Obj(linkedMapOf(
            "version" to JsonValue.Num(1.0),
            "feature" to stringMap(feature),
            "gestures" to stringMap(gestures),
        )))

    private fun stringMap(values: Map<String, String>): JsonValue.Obj =
        JsonValue.Obj(values.mapValues { (_, value) -> JsonValue.Str(value) })
}
