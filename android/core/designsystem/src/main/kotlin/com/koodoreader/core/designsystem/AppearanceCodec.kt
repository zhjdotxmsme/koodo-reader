package com.koodoreader.core.designsystem

/**
 * JSON encode / decode for [ReaderAppearanceConfig].
 *
 * No external JSON library — written by hand so the JVM tests and the Android
 * app can decode the same wire format without dragging in kotlinx.serialization
 * or org.json.  The encoder produces a flat JSON object whose keys are
 * compatible with the desktop ConfigService reader-config keys so that the
 * same backup-zip / Room row can be read on both platforms.
 *
 * Wire format (example):
 * ```json
 * {
 *   "fontSizeSp": 17.0,
 *   "lineHeightRatio": 1.5,
 *   "letterSpacingPx": 0.0,
 *   "textIndentPx": 0,
 *   "paragraphSpacingBeforePx": 0,
 *   "paragraphSpacingAfterPx": 0,
 *   "marginLeftPx": 0,
 *   "marginRightPx": 0,
 *   "marginTopPx": 0,
 *   "marginBottomPx": 0,
 *   "textAlign": "left",
 *   "themeKind": "DEFAULT",
 *   "backgroundColor": "rgba(255,255,255,1)",
 *   "backgroundImage": null,
 *   "foregroundColor": "rgba(0,0,0,1)",
 *   "themeName": "Default",
 *   "fontKey": "Built-in font",
 *   "fontDisplayName": "Built-in font",
 *   "fontFamily": null,
 *   "marginHorizontalPx": 0,
 *   "marginVerticalPx": 0,
 *   "letterSpacingPxTop": 0.0
 * }
 * ```
 */
object AppearanceCodec {

    // ─── Encoding ────────────────────────────────────────────────────────────

    /**
     * Encode [config] to a JSON string suitable for Room storage or a
     * backup zip entry.
     */
    fun encode(config: ReaderAppearanceConfig): String = buildString {
        append('{')
        encodeField("fontSizeSp", config.typography.fontSizeSp)
        comma()
        encodeField("lineHeightRatio", config.typography.lineHeightRatio)
        comma()
        encodeField("letterSpacingPx", config.typography.letterSpacingPx)
        comma()
        encodeField("textIndentPx", config.typography.textIndentPx)
        comma()
        encodeField("paragraphSpacingBeforePx", config.typography.paragraphSpacingBeforePx)
        comma()
        encodeField("paragraphSpacingAfterPx", config.typography.paragraphSpacingAfterPx)
        comma()
        encodeField("marginLeftPx", config.typography.marginLeftPx)
        comma()
        encodeField("marginRightPx", config.typography.marginRightPx)
        comma()
        encodeField("marginTopPx", config.typography.marginTopPx)
        comma()
        encodeField("marginBottomPx", config.typography.marginBottomPx)
        comma()
        encodeField("textAlign", config.typography.textAlign)
        comma()
        encodeField("themeKind", config.theme.kind.name)
        comma()
        encodeField("backgroundColor", config.theme.backgroundColor)
        comma()
        encodeField("backgroundImage", config.theme.backgroundImage)
        comma()
        encodeField("foregroundColor", config.theme.foregroundColor)
        comma()
        encodeField("themeName", config.theme.name)
        comma()
        encodeField("fontKey", config.fontCatalogEntry.key)
        comma()
        encodeField("fontDisplayName", config.fontCatalogEntry.displayName)
        comma()
        encodeField("fontFamily", config.fontCatalogEntry.fontFamily)
        comma()
        encodeField("marginHorizontalPx", config.marginHorizontalPx)
        comma()
        encodeField("marginVerticalPx", config.marginVerticalPx)
        comma()
        encodeField("letterSpacingPxTop", config.letterSpacingPx)
        append('}')
    }

    private fun StringBuilder.comma() {
        if (length > 1 && last() != '{') append(',')
    }

    private fun StringBuilder.encodeField(name: String, value: Any?) {
        append('"').append(name).append("\":")
        when (value) {
            null -> append("null")
            is String -> append('"').append(escapeString(value)).append('"')
            is Float -> append(value.toString())
            is Double -> append(value.toString())
            is Int -> append(value.toString())
            is Long -> append(value.toString())
            is Boolean -> append(value.toString())
            else -> append('"').append(escapeString(value.toString())).append('"')
        }
    }

    /** Escape a JSON string value: " → \" , \ → \\ , newlines → \n */
    private fun escapeString(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    // ─── Decoding ────────────────────────────────────────────────────────────

    /**
     * Decode a JSON string produced by [encode] back into a
     * [ReaderAppearanceConfig].
     *
     * @throws AppearanceCodecException on malformed input.
     */
    fun decode(json: String): ReaderAppearanceConfig {
        val m = parseJsonObject(json)

        val typography = TypographyTokens(
            fontSizeSp = m["fontSizeSp"]?.toFloatOrNull() ?: 17f,
            lineHeightRatio = m["lineHeightRatio"]?.toFloatOrNull() ?: 1.5f,
            letterSpacingPx = m["letterSpacingPx"]?.toFloatOrNull() ?: 0f,
            textIndentPx = m["textIndentPx"]?.toIntOrNull() ?: 0,
            paragraphSpacingBeforePx = m["paragraphSpacingBeforePx"]?.toIntOrNull() ?: 0,
            paragraphSpacingAfterPx = m["paragraphSpacingAfterPx"]?.toIntOrNull() ?: 0,
            marginLeftPx = m["marginLeftPx"]?.toIntOrNull() ?: 0,
            marginRightPx = m["marginRightPx"]?.toIntOrNull() ?: 0,
            marginTopPx = m["marginTopPx"]?.toIntOrNull() ?: 0,
            marginBottomPx = m["marginBottomPx"]?.toIntOrNull() ?: 0,
            textAlign = m["textAlign"] ?: "left",
        )

        val themeKind = try {
            ThemeKind.valueOf(m["themeKind"] ?: "DEFAULT")
        } catch (_: IllegalArgumentException) {
            ThemeKind.DEFAULT
        }

        val theme = ThemeSpec(
            kind = themeKind,
            backgroundColor = m["backgroundColor"] ?: "rgba(255,255,255,1)",
            backgroundImage = m["backgroundImage"]?.takeIf { it != "null" && it != "undefined" },
            foregroundColor = m["foregroundColor"] ?: "rgba(0,0,0,1)",
            name = m["themeName"] ?: "Default",
        )

        val font = FontCatalogEntry(
            key = m["fontKey"] ?: "Built-in font",
            displayName = m["fontDisplayName"] ?: "Built-in font",
            fontFamily = m["fontFamily"]?.takeIf { it != "null" && it != "undefined" },
        )

        return ReaderAppearanceConfig(
            typography = typography,
            theme = theme,
            fontCatalogEntry = font,
            marginHorizontalPx = m["marginHorizontalPx"]?.toIntOrNull() ?: 0,
            marginVerticalPx = m["marginVerticalPx"]?.toIntOrNull() ?: 0,
            letterSpacingPx = m["letterSpacingPxTop"]?.toFloatOrNull() ?: 0f,
        )
    }

    /**
     * Parse a minimal flat JSON object string: `{ "key": "value", "num": 123, ...}`.
     * Ignores nested objects/arrays (they do not appear in our wire format).
     * Throws [AppearanceCodecException] on malformed input.
     */
    private fun parseJsonObject(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val s = text.trim()
        if (!s.startsWith("{")) throw AppearanceCodecException("not a JSON object")
        var i = 1
        while (true) {
            i = skipWs(s, i)
            if (i >= s.length) throw AppearanceCodecException("unterminated object at $i")
            val c = s[i]
            if (c == '}') return out
            if (c == ',') { i++; continue }
            if (c != '"') throw AppearanceCodecException("expected '\"' at $i, got '$c'")
            val key = readString(s, i).also { i = it.second }.first
            i = skipWs(s, i)
            if (i >= s.length || s[i] != ':') throw AppearanceCodecException("expected ':' at $i")
            i = skipWs(s, i + 1)
            val value = readJsonValue(s, i).also { i = it.second }.first
            out[key] = value
        }
    }

    private fun skipWs(s: String, i: Int): Int {
        var j = i
        while (j < s.length && s[j].isWhitespace()) j++
        return j
    }

    /** Read a JSON string starting at [start] (on the opening quote). */
    private fun readString(s: String, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> return sb.toString() to (i + 1)
                c == '\\' -> {
                    if (i + 1 >= s.length) throw AppearanceCodecException("bad escape at $i")
                    when (val e = s[i + 1]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 6 > s.length) throw AppearanceCodecException("bad unicode escape at $i")
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                                ?: throw AppearanceCodecException("bad unicode escape: $hex")
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> throw AppearanceCodecException("unknown escape \\$e at $i")
                    }
                    i += 2
                }
                else -> { sb.append(c); i++ }
            }
        }
        throw AppearanceCodecException("unterminated string")
    }

    /**
     * Read any JSON value (string / number / bool / null / object / array)
     * starting at index [i] (on the first non-ws character of the value).
     * Returns the string representation plus the index of the next character.
     */
    private fun readJsonValue(s: String, i: Int): Pair<String, Int> {
        val j = skipWs(s, i)
        if (j >= s.length) return "" to j
        when (s[j]) {
            '"' -> {
                val (value, next) = readString(s, j)
                return "\"$value\"" to next
            }
            '{' -> {
                // Skip nested object
                var depth = 0
                var k = j
                while (k < s.length) {
                    if (s[k] == '{') depth++
                    if (s[k] == '}') { depth--; if (depth == 0) return s.substring(j, k + 1) to (k + 1) }
                    k++
                }
                throw AppearanceCodecException("unterminated nested object at $j")
            }
            '[' -> {
                var depth = 0
                var k = j
                while (k < s.length) {
                    if (s[k] == '[') depth++
                    if (s[k] == ']') { depth--; if (depth == 0) return s.substring(j, k + 1) to (k + 1) }
                    k++
                }
                throw AppearanceCodecException("unterminated nested array at $j")
            }
            else -> {
                // Number, boolean, null
                var k = j
                while (k < s.length && s[k] != ',' && s[k] != '}' && !s[k].isWhitespace()) k++
                val raw = s.substring(j, k)
                return raw to k
            }
        }
    }
}

/** Raised by [AppearanceCodec] on decode errors. */
class AppearanceCodecException(message: String) : RuntimeException(message)
