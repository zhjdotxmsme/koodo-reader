package com.koodoreader.engine.feature

/** Flat key→JSON-string entries the desktop restore replays via ConfigService.setItem. */
data class DesktopPrefs(
    val rules: List<TextRule>,
    val customBookCss: String,
    val customBookCssEnabled: Boolean,
    val warnings: List<String> = emptyList(),
)

object DesktopConfigPrefs {

    const val KEY_READER_CONFIG = "readerConfig"
    const val KEY_CUSTOM_CSS = "customBookCSS"
    const val KEY_CUSTOM_CSS_ENABLED = "isCustomBookCSS"

    /** Values are JSON STRINGS (desktop ConfigService stores strings, not nested objects). */
    fun desktopConfigEntries(
        rules: List<TextRule>,
        customBookCss: String,
        customBookCssEnabled: Boolean,
    ): Map<String, String> {
        val envelope = TextRuleJson.exportConfig(rules)
        val parsed = Json.parse(envelope) as JsonValue.Obj
        val fields = LinkedHashMap<String, String>()
        fields[TextRuleJson.KEY_RULE_LIST] =
            Json.write(parsed.fields.getValue(TextRuleJson.KEY_RULE_LIST))
        fields[TextRuleJson.KEY_RULES] =
            Json.write(parsed.fields.getValue(TextRuleJson.KEY_RULES))
        fields[KEY_READER_CONFIG] = Json.write(
            JsonValue.Obj(linkedMapOf(
                KEY_CUSTOM_CSS to JsonValue.Str(customBookCss),
                KEY_CUSTOM_CSS_ENABLED to JsonValue.Str(if (customBookCssEnabled) "yes" else "no"),
            )),
        )
        return fields
    }

    /** Missing/invalid fields are skipped with a warning — never cleared silently. */
    fun parseDesktopConfig(configJson: String?): DesktopPrefs {
        if (configJson.isNullOrBlank()) return DesktopPrefs(emptyList(), "", false)
        val root = runCatching { Json.parse(configJson) as? JsonValue.Obj }.getOrNull()
            ?: return DesktopPrefs(emptyList(), "", false, listOf("config.json is not an object"))
        val warnings = mutableListOf<String>()
        fun stringField(key: String): String? = (root.fields[key] as? JsonValue.Str)?.value
        val ids = stringField(TextRuleJson.KEY_RULE_LIST)
        if (ids == null) warnings += "missing or invalid '${TextRuleJson.KEY_RULE_LIST}' string"
        val map = stringField(TextRuleJson.KEY_RULES)
        if (map == null) warnings += "missing or invalid '${TextRuleJson.KEY_RULES}' string"
        val rulesPart = if (ids == null || map == null) "" else buildString {
            append("{\"").append(TextRuleJson.KEY_RULE_LIST).append("\":").append(ids)
            append(",\"").append(TextRuleJson.KEY_RULES).append("\":").append(map).append('}')
        }
        val imported = if (rulesPart.isEmpty()) {
            TextRuleJson.ImportResult(emptyList(), emptyList())
        } else {
            runCatching { TextRuleJson.importRules(rulesPart) }.getOrElse {
                warnings += "textRules parse failed: ${it.message}"
                TextRuleJson.ImportResult(emptyList(), emptyList())
            }
        }
        warnings += imported.warnings
        var css = ""
        var enabled = false
        val rcText = stringField(KEY_READER_CONFIG)
        if (rcText == null) {
            warnings += "missing or invalid '$KEY_READER_CONFIG' string"
        } else {
            runCatching {
                val rc = Json.parse(rcText) as JsonValue.Obj
                val cssValue = rc.fields[KEY_CUSTOM_CSS] as? JsonValue.Str
                if (cssValue == null) {
                    warnings += "missing or invalid '$KEY_READER_CONFIG.$KEY_CUSTOM_CSS' string"
                }
                css = cssValue?.value.orEmpty()
                val enabledValue = rc.fields[KEY_CUSTOM_CSS_ENABLED] as? JsonValue.Str
                if (enabledValue == null) {
                    warnings += "missing or invalid '$KEY_READER_CONFIG.$KEY_CUSTOM_CSS_ENABLED' string"
                } else if (enabledValue.value != "yes" && enabledValue.value != "no") {
                    warnings += "invalid '$KEY_READER_CONFIG.$KEY_CUSTOM_CSS_ENABLED' value '${enabledValue.value}'; expected 'yes' or 'no'"
                }
                enabled = enabledValue?.value == "yes"
            }.onFailure { warnings += "readerConfig parse failed: ${it.message}" }
        }
        return DesktopPrefs(imported.rules, css, enabled, warnings)
    }
}
