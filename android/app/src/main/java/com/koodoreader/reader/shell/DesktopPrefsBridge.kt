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

    fun applyDesktopConfig(configJson: String?, current: ReaderFeatureState): ReaderFeatureState {
        if (configJson.isNullOrBlank()) return current
        val root = runCatching { TextRuleJson.parse(configJson) as? JsonValue.Obj }.getOrNull()
            ?: return current
        val parsed = DesktopConfigPrefs.parseDesktopConfig(configJson)
        var next = current

        val ruleList = root.fields[TextRuleJson.KEY_RULE_LIST].stringValue()
        val rules = root.fields[TextRuleJson.KEY_RULES].stringValue()
        if (ruleList != null && rules != null && validRuleEnvelope(ruleList, rules)) {
            next = next.copy(textRulesJson = TextRuleJson.exportRules(parsed.rules))
        }

        val readerConfig = root.fields[DesktopConfigPrefs.KEY_READER_CONFIG].stringValue()
            ?.let { runCatching { TextRuleJson.parse(it) as? JsonValue.Obj }.getOrNull() }
        if (readerConfig != null) {
            if (readerConfig.fields[DesktopConfigPrefs.KEY_CUSTOM_CSS] is JsonValue.Str) {
                next = next.copy(customBookCss = parsed.customBookCss)
            }
            val enabled = readerConfig.fields[DesktopConfigPrefs.KEY_CUSTOM_CSS_ENABLED].stringValue()
            if (enabled == "yes" || enabled == "no") {
                next = next.copy(customBookCssEnabled = parsed.customBookCssEnabled)
            }
        }
        return next
    }

    fun parseAndroidPrefsVersion(json: String?): Int? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { TextRuleJson.parse(json) as? JsonValue.Obj }.getOrNull()
            ?: return null
        val version = (root.fields["version"] as? JsonValue.Num)?.value ?: return null
        val integer = version.toInt()
        return integer.takeIf { version.isFinite() && version == it.toDouble() }
    }

    data class AndroidPrefsRestore(
        val feature: ReaderFeatureState,
        val gestures: ReaderGestureConfig,
        val accepted: Boolean,
        val warnings: List<String>,
    )

    fun restoreAndroidPrefs(
        json: String?,
        currentFeature: ReaderFeatureState,
        currentGestures: ReaderGestureConfig,
    ): AndroidPrefsRestore {
        fun rejected(warning: String) = AndroidPrefsRestore(
            currentFeature,
            currentGestures,
            accepted = false,
            warnings = listOf(warning),
        )
        if (json == null) {
            return AndroidPrefsRestore(currentFeature, currentGestures, accepted = false, warnings = emptyList())
        }
        if (json.isBlank()) {
            return rejected("Android private preferences were not restored: koodo-android.json is blank")
        }
        val root = runCatching { TextRuleJson.parse(json) as? JsonValue.Obj }.getOrNull()
            ?: return rejected("Android private preferences were not restored: koodo-android.json is malformed")
        val version = parseAndroidPrefsVersion(json)
            ?: return rejected("Android private preferences were not restored: version is missing or invalid")
        if (version != 1) {
            return rejected("Android private preferences were not restored: unsupported version $version")
        }

        val warnings = mutableListOf<String>()
        val feature = ReaderFeatureCodec.fromMap(mergeValidValues(
            current = ReaderFeatureCodec.toMap(currentFeature),
            imported = stringEntries(root, "feature", warnings),
            section = "feature",
            warnings = warnings,
            isValid = ::validFeatureValue,
        ))
        val gestures = ReaderGestureCodec.fromMap(mergeValidValues(
            current = ReaderGestureCodec.toMap(currentGestures),
            imported = stringEntries(root, "gestures", warnings),
            section = "gestures",
            warnings = warnings,
            isValid = ::validGestureValue,
        ))
        return AndroidPrefsRestore(feature, gestures, accepted = true, warnings = warnings)
    }

    private fun validRuleEnvelope(ruleList: String, rules: String): Boolean = runCatching {
        TextRuleJson.parse(ruleList) is JsonValue.Arr && TextRuleJson.parse(rules) is JsonValue.Obj
    }.getOrDefault(false)

    private fun JsonValue?.stringValue(): String? = (this as? JsonValue.Str)?.value

    private fun stringEntries(
        root: JsonValue.Obj,
        section: String,
        warnings: MutableList<String>,
    ): Map<String, String> {
        val value = root.fields[section] ?: return emptyMap()
        val map = value as? JsonValue.Obj
        if (map == null) {
            warnings += "Android private preferences '$section' is not an object; skipped"
            return emptyMap()
        }
        return map.fields.mapNotNull { (key, entry) ->
            val string = entry.stringValue()
            if (string == null) {
                warnings += "Android private preference '$section.$key' is not a string; skipped"
                null
            } else {
                key to string
            }
        }.toMap()
    }

    private fun mergeValidValues(
        current: Map<String, String>,
        imported: Map<String, String>,
        section: String,
        warnings: MutableList<String>,
        isValid: (String, String) -> Boolean,
    ): Map<String, String> {
        val merged = current.toMutableMap()
        for ((key, value) in imported) {
            when {
                key !in current -> warnings += "Unknown Android private preference '$section.$key'; skipped"
                !isValid(key, value) -> warnings += "Invalid Android private preference '$section.$key'; skipped"
                else -> merged[key] = value
            }
        }
        return merged
    }

    private fun validFeatureValue(key: String, value: String): Boolean = when (key) {
        ReaderFeatureCodec.KEY_FONT_SCALE,
        ReaderFeatureCodec.KEY_LINE_HEIGHT,
        ReaderFeatureCodec.KEY_MARGIN,
        ReaderFeatureCodec.KEY_RULER_LINES,
        ReaderFeatureCodec.KEY_PDF_CROP -> value.toFloatOrNull()?.isFinite() == true
        ReaderFeatureCodec.KEY_SPEED_WPM,
        ReaderFeatureCodec.KEY_COLUMNS -> value.toIntOrNull() != null
        ReaderFeatureCodec.KEY_BIONIC,
        ReaderFeatureCodec.KEY_RULER,
        ReaderFeatureCodec.KEY_PARAGRAPH,
        ReaderFeatureCodec.KEY_SPEED,
        ReaderFeatureCodec.KEY_DOUBLE_PAGE,
        ReaderFeatureCodec.KEY_SCROLLED,
        ReaderFeatureCodec.KEY_CUSTOM_BOOK_CSS_ENABLED,
        ReaderFeatureCodec.KEY_PDF_REFLOW -> value.isBooleanValue()
        ReaderFeatureCodec.KEY_PERF_TIER ->
            value.equals(ReaderPerformancePolicy.TIER_NORMAL, ignoreCase = true) ||
                value.equals(ReaderPerformancePolicy.TIER_LITE, ignoreCase = true)
        ReaderFeatureCodec.KEY_TEXT_RULES -> value.isEmpty() || runCatching {
            TextRuleJson.parse(value) is JsonValue.Arr || TextRuleJson.parse(value) is JsonValue.Obj
        }.getOrDefault(false)
        else -> true
    }

    private fun validGestureValue(key: String, value: String): Boolean = when (key) {
        ReaderGestureCodec.KEY_TAP_EDGE -> value.toFloatOrNull()?.isFinite() == true
        ReaderGestureCodec.KEY_SWIPE,
        ReaderGestureCodec.KEY_VOLUME,
        ReaderGestureCodec.KEY_VOLUME_INVERT -> value.isBooleanValue()
        else -> true
    }

    private fun String.isBooleanValue(): Boolean =
        equals("true", ignoreCase = true) || equals("false", ignoreCase = true) || this == "1" || this == "0"

    private fun stringMap(values: Map<String, String>): JsonValue.Obj =
        JsonValue.Obj(values.mapValues { (_, value) -> JsonValue.Str(value) })
}
