package com.koodoreader.engine.feature

/**
 * Ordered text-replacement engine — the native counterpart of the desktop
 * reader pipeline (`getTextRules()` → apply each rule in list order).
 *
 * Behaviour parity notes:
 *  - Rules are applied **in the given order**; the output of one rule feeds the
 *    next (`String.replace` chaining on the desktop).
 *  - `plain` rules match literally (the pattern is escaped, like the desktop
 *    `escapeRegExp` usage) and their replacement is taken literally too, so a
 *    `$` in the replacement is not read as a group reference.
 *  - `regex` rules keep group references (`$1`) in the replacement, matching the
 *    desktop comment in `src/utils/reader/ttsUtil.ts`.
 *  - An invalid regex must never break the reader: it is skipped and reported in
 *    [Result.warnings].
 *  - `highlight` rules are decoration only and are ignored here.
 *
 * Not thread-safe by itself (stateless, so instances are freely shareable).
 *
 * @param caseSensitive when false, matching ignores case (P6 card requirement;
 *   the desktop pipeline is always case sensitive).
 */
class TextRuleEngine(
    private val caseSensitive: Boolean = true,
) {

    /** Outcome of an [apply] call. [warnings] is empty when everything was applied. */
    data class Result(val text: String, val warnings: List<String>) {
        val hasWarnings: Boolean get() = warnings.isNotEmpty()
        val warningCount: Int get() = warnings.size
    }

    /**
     * Apply every enabled, transforming rule to [text] in order.
     *
     * @return the transformed text plus one warning per skipped rule.
     */
    fun apply(text: String, rules: List<TextRule>): Result {
        if (rules.isEmpty()) return Result(text, emptyList())

        var current = text
        val warnings = ArrayList<String>()

        for (rule in rules) {
            if (!rule.enabled) continue
            if (rule.type == TextRule.TYPE_HIGHLIGHT) continue

            val pattern = rule.pattern
            if (pattern.isEmpty()) {
                warnings += "rule '${rule.id}': empty pattern skipped"
                continue
            }

            val regex = try {
                compile(pattern, rule.isRegex)
            } catch (e: Exception) {
                warnings += "rule '${rule.id}': invalid ${if (rule.isRegex) "regex" else "pattern"} " +
                    "'$pattern' skipped (${e.message ?: e::class.simpleName})"
                continue
            }

            val replacement = if (rule.type == TextRule.TYPE_DELETE) "" else rule.replacement
            current = try {
                regex.replace(current, escapeReplacement(replacement, rule.isRegex))
            } catch (e: Exception) {
                warnings += "rule '${rule.id}': replacement failed " +
                    "(${e.message ?: e::class.simpleName}) skipped"
                current
            }
        }
        return Result(current, warnings)
    }

    /** Convenience wrapper when the caller does not care about warnings. */
    fun applyToText(text: String, rules: List<TextRule>): String = apply(text, rules).text

    /** True when [pattern] compiles under the rule's mode (used by the settings UI). */
    fun validate(pattern: String, isRegex: Boolean): Boolean = try {
        compile(pattern, isRegex)
        true
    } catch (e: Exception) {
        false
    }

    private fun compile(pattern: String, isRegex: Boolean): Regex {
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return if (isRegex) Regex(pattern, options) else Regex(Regex.escape(pattern), options)
    }

    /**
     * Kotlin's [Regex.replace] treats `$` as a group reference and `\` as an
     * escape. Regex rules keep `$` (desktop parity) but backslashes are escaped;
     * plain rules escape both so `$` stays literal.
     */
    private fun escapeReplacement(replacement: String, isRegex: Boolean): String {
        if (replacement.isEmpty()) return ""
        val sb = StringBuilder(replacement.length + 8)
        for (c in replacement) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '$' && !isRegex -> sb.append("\\$")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
