package com.koodoreader.engine.feature

/**
 * A single desktop-compatible text replacement rule.
 *
 * The first five fields are the P6 card contract
 * (`TextRule(id, pattern, replacement, enabled, isRegex)`); the remaining fields
 * mirror the desktop model 1:1 so a rule produced here can be written straight
 * back into the desktop config.
 *
 * Desktop sources:
 *  - `src/utils/common.ts` → `interface TextRule` + `getTextRules(bookKey)`
 *  - `src/containers/settings/textSetting/interface.tsx` → the superset with
 *    `type` / `scope` / `highlight*`
 *
 * Desktop **config keys** (verified by read-only search of `src/`):
 *  - `textRuleList` — list config holding the rule ids, **in application order**
 *  - `textRules`    — object config keyed by rule id, holding the rule object
 *    (`ConfigService.setObjectConfig(rule.id, rule, "textRules")` in
 *    `src/containers/settings/textSetting/component.tsx`).
 *  Both keys are used verbatim by [TextRuleJson].
 */
data class TextRule(
    val id: String,
    val pattern: String,
    val replacement: String = "",
    val enabled: Boolean = true,
    val isRegex: Boolean = false,
    /** Desktop `type`: `replace` | `delete` | `highlight`. */
    val type: String = TYPE_REPLACE,
    /** Desktop `scope`: `all` | `book`. */
    val scope: String = SCOPE_ALL,
    val bookKey: String? = null,
    val bookName: String? = null,
    val highlightStyle: String? = null,
    val highlightColor: String? = null,
) {
    /** Desktop `matchType` — the wire representation of [isRegex]. */
    val matchType: String get() = if (isRegex) MATCH_REGEX else MATCH_PLAIN

    /** True when this rule mutates the text (highlight rules are decoration only). */
    val isTransform: Boolean get() = enabled && type != TYPE_HIGHLIGHT

    companion object {
        const val TYPE_REPLACE = "replace"
        const val TYPE_DELETE = "delete"
        const val TYPE_HIGHLIGHT = "highlight"

        const val SCOPE_ALL = "all"
        const val SCOPE_BOOK = "book"

        const val MATCH_REGEX = "regex"
        const val MATCH_PLAIN = "plain"
    }
}
