package com.koodoreader.engine.toc

/**
 * Codec for [ReadingPosition] ↔ JSON string.
 *
 * Used to store / retrieve reading positions in Room. The JSON keys are named
 * after the desktop engine's `recordLocation` schema so the two platforms stay
 * compatible at the database level.
 *
 * Self-contained: no external JSON library dependency (matches :engine:cfi's
 * zero-dependency design). Manual string-builded JSON with escape rules matching
 * the JSON spec.
 *
 * Round-trip is guaranteed: `decode(encode(pos)) == pos`.
 */
object PositionCodec {
    private const val KEY_BOOK_KEY = "bookKey"
    private const val KEY_SPINE_INDEX = "spineIndex"
    private const val KEY_CFI = "cfi"
    private const val KEY_CHAPTER_PERCENT = "chapterPercent"
    private const val KEY_TOTAL_PERCENT = "totalPercent"

    /**
     * Encode a [ReadingPosition] to a JSON string suitable for Room storage.
     *
     * Every key is quoted: JSON requires it, and the payload is meant to be read by
     * the desktop side too — not only by [decode].
     */
    fun encode(pos: ReadingPosition): String = buildString {
        append('{')
        stringField(KEY_BOOK_KEY, pos.bookKey)
        append(',')
        numberField(KEY_SPINE_INDEX, pos.spineIndex.toString())
        append(',')
        stringField(KEY_CFI, pos.cfi)
        append(',')
        // `Float.toString` is used instead of `toDouble()`: the latter widens the
        // float and emits artefacts like 0.44999998807907104 for 0.45f, while
        // Float.toString produces the shortest value that parses back identically.
        numberField(KEY_CHAPTER_PERCENT, pos.chapterPercent.toString())
        append(',')
        numberField(KEY_TOTAL_PERCENT, pos.totalPercent.toString())
        append('}')
    }

    /**
     * Encode a string field, adding quotes and JSON-escaping the value.
     */
    private fun Appendable.stringField(key: String, value: String) {
        append('"')
        append(key)
        append('"')
        append(':')
        append('"')
        append(escapeJsonString(value))
        append('"')
    }

    /**
     * Encode a numeric field. The key still needs its quotes — this was the bug that
     * made [encode] emit invalid JSON (`{"bookKey":"b",spineIndex:3,...}`).
     */
    private fun Appendable.numberField(key: String, rawValue: String) {
        append('"')
        append(key)
        append('"')
        append(':')
        append(rawValue)
    }

    /**
     * Minimal JSON string escape (covers the characters that can appear in CFI / titles).
     * Does NOT handle Unicode code points above \uFFFF (not expected in CFI strings).
     */
    private fun escapeJsonString(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    /**
     * Decode a JSON string back into a [ReadingPosition].
     *
     * @throws IllegalArgumentException when the JSON is malformed or missing required fields.
     */
    fun decode(json: String): ReadingPosition {
        require(json.startsWith("{")) { "Invalid JSON object: $json" }
        require(json.endsWith("}")) { "Invalid JSON object: $json" }

        val fields = mutableMapOf<String, String>()
        val content = json.substring(1, json.length - 1)

        var i = 0
        while (i < content.length) {
            // Skip whitespace
            while (i < content.length && content[i].isWhitespace()) i++
            if (i >= content.length) break

            // Parse key (must be quoted)
            require(content[i] == '"') { "Expected '\"' at position $i in $json" }
            val (key, keyEnd) = parseString(content, i)
            i = keyEnd

            // Skip whitespace and colon
            while (i < content.length && content[i].isWhitespace()) i++
            require(i < content.length && content[i] == ':') { "Expected ':' at position $i in $json" }
            i++

            // Skip whitespace
            while (i < content.length && content[i].isWhitespace()) i++

            // Parse value
            val (value, valueEnd) = parseValue(content, i)
            fields[key] = value
            i = valueEnd

            // Skip whitespace and comma
            while (i < content.length && content[i].isWhitespace()) i++
            if (i < content.length && content[i] == ',') i++
        }

        return ReadingPosition(
            bookKey = fields[KEY_BOOK_KEY] ?: error("Missing field: $KEY_BOOK_KEY in $json"),
            spineIndex = fields[KEY_SPINE_INDEX]?.toIntOrNull()
                ?: error("Invalid $KEY_SPINE_INDEX in $json"),
            cfi = fields[KEY_CFI] ?: error("Missing field: $KEY_CFI in $json"),
            chapterPercent = fields[KEY_CHAPTER_PERCENT]?.toFloatOrNull()
                ?: error("Invalid $KEY_CHAPTER_PERCENT in $json"),
            totalPercent = fields[KEY_TOTAL_PERCENT]?.toFloatOrNull()
                ?: error("Invalid $KEY_TOTAL_PERCENT in $json"),
        )
    }

    /**
     * Parse a quoted JSON string starting at [start] (the opening `"`).
     * Returns the string content (unescaped) and the index of the closing `"`.
     */
    private fun parseString(s: String, start: Int): Pair<String, Int> {
        require(s[start] == '"') { "Expected '\"' at $start" }
        val builder = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> return builder.toString() to i + 1
                '\\' -> {
                    i++
                    require(i < s.length) { "Unterminated escape at $i" }
                    when (s[i]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            require(i + 4 < s.length) { "Invalid unicode escape at $i" }
                            val hex = s.substring(i + 1, i + 5)
                            builder.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> builder.append(s[i])
                    }
                }
                else -> builder.append(c)
            }
            i++
        }
        error("Unterminated string starting at $start")
    }

    /**
     * Parse a JSON value (string, number, boolean, null) starting at [start].
     * Returns the string representation and the index after the value.
     */
    private fun parseValue(s: String, start: Int): Pair<String, Int> {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++

        when {
            s.startsWith("null", i) -> return "null" to (i + 4)
            s.startsWith("true", i) -> return "true" to (i + 4)
            s.startsWith("false", i) -> return "false" to (i + 5)
            s[i] == '"' -> {
                // Return the *unquoted* content: the map holds raw field values and
                // every consumer converts them itself (`toIntOrNull`, `toFloatOrNull`,
                // or uses the string as-is for bookKey / cfi). Re-quoting here made
                // `decode(encode(pos)).bookKey` come back as `"my-book"`.
                val (str, end) = parseString(s, i)
                return str to end
            }
            s[i].isDigit() || s[i] == '-' -> {
                val startDigit = i
                if (s[i] == '-') i++
                while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) {
                    // Note: last case is for signed exponents only (correct here)
                    i++
                }
                return s.substring(startDigit, i) to i
            }
            else -> error("Unexpected character '${s[i]}' at position $i")
        }
    }
}
