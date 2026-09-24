package com.koodoreader.engine.feature

/**
 * JSON import / export for [TextRule] lists, using the **desktop config keys
 * verbatim** so a file exported here can be dropped into the desktop config and
 * vice versa.
 *
 * Two shapes are produced and accepted:
 *
 * 1. A bare array (the "export rules" file):
 *    ```json
 *    [{"id":"1","type":"replace","pattern":"foo","matchType":"plain","enabled":true,"replacement":"bar"}]
 *    ```
 * 2. The desktop config envelope — exactly the two keys read by
 *    `src/utils/common.ts`:
 *    ```json
 *    {"textRuleList":["1"],"textRules":{"1":{ ...same rule object... }}}
 *    ```
 *
 * Wire field names are desktop names (`type`, `pattern`, `matchType`,
 * `replacement`, `scope`, `bookKey`, `bookName`, `highlightStyle`,
 * `highlightColor`); `enabled` is additive (the desktop model has no such field,
 * an unknown key is harmless there). `isRegex` is transmitted as `matchType`
 * (`"regex"`/`"plain"`) and also accepted as a literal `isRegex` boolean.
 */
object TextRuleJson {

    /** Desktop list config key: rule ids in application order. */
    const val KEY_RULE_LIST = "textRuleList"

    /** Desktop object config key: rule id → rule object. */
    const val KEY_RULES = "textRules"

    /** Result of an import: the decoded rules plus a warning per skipped entry. */
    data class ImportResult(val rules: List<TextRule>, val warnings: List<String>) {
        val hasWarnings: Boolean get() = warnings.isNotEmpty()
        val warningCount: Int get() = warnings.size
    }

    /** Serialise [rules] as a bare JSON array. */
    fun exportRules(rules: List<TextRule>, pretty: Boolean = false): String =
        render(JsonValue.Arr(rules.map { encodeRule(it) }), pretty)

    /** Serialise [rules] as the desktop `{textRuleList, textRules}` envelope. */
    fun exportConfig(rules: List<TextRule>, pretty: Boolean = false): String {
        val ids = JsonValue.Arr(rules.map { JsonValue.Str(it.id) })
        val map = LinkedHashMap<String, JsonValue>()
        for (rule in rules) map[rule.id] = encodeRule(rule)
        val fields = LinkedHashMap<String, JsonValue>()
        fields[KEY_RULE_LIST] = ids
        fields[KEY_RULES] = JsonValue.Obj(map)
        return render(JsonValue.Obj(fields), pretty)
    }

    /** Alias of [exportConfig] — the name used by the settings export action. */
    fun export(rules: List<TextRule>, pretty: Boolean = false): String = exportConfig(rules, pretty)

    /** Parse either a bare array or a desktop envelope. */
    fun importRules(json: String): ImportResult = decode(json)

    /** Alias of [importRules] — the name used by the settings import action. */
    fun importConfig(json: String): ImportResult = decode(json)

    /** Parse [json], returning an empty result with a warning on malformed input. */
    private fun decode(json: String): ImportResult {
        val warnings = ArrayList<String>()
        val root = try {
            Json.parse(json)
        } catch (e: Exception) {
            return ImportResult(emptyList(), listOf("invalid JSON: ${e.message ?: "parse error"}"))
        }

        return when (root) {
            is JsonValue.Arr -> {
                val rules = ArrayList<TextRule>()
                root.items.forEachIndexed { index, item ->
                    val obj = item.asObj()
                    if (obj == null) {
                        warnings += "entry #$index: not an object, skipped"
                        return@forEachIndexed
                    }
                    decodeRule(obj, null, warnings)?.let { rules += it }
                }
                ImportResult(rules, warnings)
            }

            is JsonValue.Obj -> decodeEnvelope(root, warnings)

            else -> ImportResult(emptyList(), listOf("root is not an array or object"))
        }
    }

    private fun decodeEnvelope(root: JsonValue.Obj, warnings: MutableList<String>): ImportResult {
        val map = root.fields[KEY_RULES].asObj()
        val ids = (root.fields[KEY_RULE_LIST] as? JsonValue.Arr)?.items?.mapNotNull { it.asStr() }

        if (map == null) {
            if (ids == null) {
                return ImportResult(emptyList(), listOf("missing '$KEY_RULES' and '$KEY_RULE_LIST'"))
            }
            warnings += "missing '$KEY_RULES' object"
            return ImportResult(emptyList(), warnings)
        }

        val rules = ArrayList<TextRule>()
        val consumed = HashSet<String>()

        // `textRuleList` defines the application order; the object map is only a lookup.
        for (id in ids.orEmpty()) {
            val obj = map.fields[id].asObj()
            if (obj == null) {
                warnings += "'$KEY_RULE_LIST' references '$id' but '$KEY_RULES' has no such entry"
                continue
            }
            consumed += id
            decodeRule(obj, id, warnings)?.let { rules += it }
        }

        // Rules present in the map but missing from the list still get applied,
        // appended after the ordered ones (defensive: never silently drop config).
        for ((id, value) in map.fields) {
            if (id in consumed) continue
            val obj = value.asObj()
            if (obj == null) {
                warnings += "'$KEY_RULES.$id' is not an object, skipped"
                continue
            }
            warnings += "'$KEY_RULES.$id' is not listed in '$KEY_RULE_LIST'; appended"
            decodeRule(obj, id, warnings)?.let { rules += it }
        }

        return ImportResult(rules, warnings)
    }

    // ---------------------------------------------------------------- mapping

    private fun encodeRule(rule: TextRule): JsonValue {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["id"] = JsonValue.Str(rule.id)
        fields["type"] = JsonValue.Str(rule.type)
        fields["pattern"] = JsonValue.Str(rule.pattern)
        fields["matchType"] = JsonValue.Str(rule.matchType)
        fields["replacement"] = JsonValue.Str(rule.replacement)
        fields["scope"] = JsonValue.Str(rule.scope)
        fields["enabled"] = JsonValue.Bool(rule.enabled)
        rule.bookKey?.let { fields["bookKey"] = JsonValue.Str(it) }
        rule.bookName?.let { fields["bookName"] = JsonValue.Str(it) }
        rule.highlightStyle?.let { fields["highlightStyle"] = JsonValue.Str(it) }
        rule.highlightColor?.let { fields["highlightColor"] = JsonValue.Str(it) }
        return JsonValue.Obj(fields)
    }

    private fun decodeRule(
        obj: JsonValue.Obj,
        mapKey: String?,
        warnings: MutableList<String>,
    ): TextRule? {
        val id = obj.fields["id"].asStr() ?: mapKey
        if (id.isNullOrEmpty()) {
            warnings += "rule without 'id' skipped"
            return null
        }
        val pattern = obj.fields["pattern"].asStr()
        if (pattern == null) {
            warnings += "rule '$id': missing 'pattern' skipped"
            return null
        }

        val matchType = obj.fields["matchType"].asStr()?.lowercase()
        val isRegex = when {
            matchType != null -> matchType == TextRule.MATCH_REGEX
            else -> obj.fields["isRegex"].asBool() ?: false
        }

        val type = obj.fields["type"].asStr()
            ?.lowercase()
            ?.takeIf { it == TextRule.TYPE_REPLACE || it == TextRule.TYPE_DELETE || it == TextRule.TYPE_HIGHLIGHT }
            ?: TextRule.TYPE_REPLACE

        val scope = obj.fields["scope"].asStr()
            ?.lowercase()
            ?.takeIf { it == TextRule.SCOPE_ALL || it == TextRule.SCOPE_BOOK }
            ?: TextRule.SCOPE_ALL

        if (matchType != null && matchType != TextRule.MATCH_REGEX && matchType != TextRule.MATCH_PLAIN) {
            warnings += "rule '$id': unknown matchType '$matchType', treated as plain"
        }

        return TextRule(
            id = id,
            pattern = pattern,
            replacement = obj.fields["replacement"].asStr() ?: "",
            enabled = obj.fields["enabled"].asBool() ?: true,
            isRegex = isRegex,
            type = type,
            scope = scope,
            bookKey = obj.fields["bookKey"].asStr(),
            bookName = obj.fields["bookName"].asStr(),
            highlightStyle = obj.fields["highlightStyle"].asStr(),
            highlightColor = obj.fields["highlightColor"].asStr(),
        )
    }

    private fun render(value: JsonValue, pretty: Boolean): String =
        if (pretty) Json.writePretty(value) else Json.write(value)
}
