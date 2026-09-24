package com.koodoreader.engine.annotate

/**
 * A minimal, explicitly-typed JSON value model.
 *
 * Why a hand-rolled JSON model instead of a library: this is a pure Kotlin
 * JVM engine module with no third-party runtime dependencies (mirroring
 * :engine:cfi / :engine:link), and backup-zip records plus the desktop
 * `date` ("object") / `tag` ("array") columns need a small, lossless JSON
 * representation. Concrete subtypes (never [Any]) keep callers honest.
 *
 * Numbers keep two Kotlin forms because JSON does not distinguish int from
 * double but our columns do:
 *  - [JsonNum] stores a Double and remembers whether the token carried a
 *    fraction/exponent. [isIntegral] lets the codec bind a Long to Room's
 *    INTEGER columns while still round-tripping decimal percentages.
 */
sealed class JsonValue {
    data class JsonObject(val entries: LinkedHashMap<String, JsonValue>) : JsonValue() {
        constructor(vararg pairs: Pair<String, JsonValue>) : this(LinkedHashMap<String, JsonValue>().apply {
            pairs.forEach { (k, v) -> entries[k] = v }
        })

        operator fun get(key: String): JsonValue? = entries[key]
    }

    data class JsonArray(val items: List<JsonValue>) : JsonValue() {
        constructor(vararg values: JsonValue) : this(values.toList())
    }

    data class JsonStr(val value: String) : JsonValue()
    data class JsonNum(val value: Double, val isIntegral: Boolean) : JsonValue()
    data class JsonBool(val value: Boolean) : JsonValue()
    data object JsonNull : JsonValue()

    /** Convenience constructors. */
    companion object {
        fun of(value: String): JsonValue = JsonStr(value)
        fun of(value: Int): JsonValue = JsonNum(value.toDouble(), isIntegral = true)
        fun of(value: Long): JsonValue = JsonNum(value.toDouble(), isIntegral = true)
        fun of(value: Double): JsonValue =
            JsonNum(value, isIntegral = value.isFinite() && value % 1.0 == 0.0)

        fun of(value: Boolean): JsonValue = JsonBool(value)

        /** Convert a Kotlin list of strings to a JSON array. */
        fun stringArray(values: List<String>): JsonValue = JsonArray(values.map { JsonStr(it) })
    }
}

/** Accessor helpers that return plain Kotlin values (null when absent/wrong type). */
fun JsonValue?.asString(): String? = (this as? JsonValue.JsonStr)?.value

fun JsonValue?.asLong(): Long? {
    val num = this as? JsonValue.JsonNum ?: return null
    return if (num.isIntegral) num.value.toLong() else null
}

fun JsonValue?.asDouble(): Double? = (this as? JsonValue.JsonNum)?.value

fun JsonValue?.asBoolean(): Boolean? = (this as? JsonValue.JsonBool)?.value

fun JsonValue?.asObject(): JsonValue.JsonObject? = this as? JsonValue.JsonObject

fun JsonValue?.asArrayItems(): List<JsonValue>? = (this as? JsonValue.JsonArray)?.items

/** Decode [text] as JSON. @throws IllegalArgumentException on malformed input. */
fun parseJson(text: String): JsonValue = JsonReader(text).parseValue()

/** Serialize [value] to compact JSON text. */
fun toJsonString(value: JsonValue): String = JsonWriter().write(value).build()

private class JsonReader(private val text: String) {
    private var pos = 0

    fun parseValue(): JsonValue {
        skipWs()
        if (pos >= text.length) fail("unexpected end of input")
        return when (text[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.JsonStr(parseString())
            't' -> parseLiteral("true", JsonValue.JsonBool(true))
            'f' -> parseLiteral("false", JsonValue.JsonBool(false))
            'n' -> parseLiteral("null", JsonValue.JsonNull)
            else -> if (text[pos] == '-' || text[pos].isDigit()) parseNumber() else fail("unexpected character '${text[pos]}'")
        }
    }

    private fun parseObject(): JsonValue.JsonObject {
        expect('{')
        val map = LinkedHashMap<String, JsonValue>()
        skipWs()
        if (peek() == '}') { pos++; return JsonValue.JsonObject(map) }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            expect(':')
            map[key] = parseValue()
            skipWs()
            when (peek()) {
                ',' -> { pos++; continue }
                '}' -> { pos++; break }
                else -> fail("expected ',' or '}'")
            }
        }
        return JsonValue.JsonObject(map)
    }

    private fun parseArray(): JsonValue.JsonArray {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWs()
        if (peek() == ']') { pos++; return JsonValue.JsonArray(items) }
        while (true) {
            items += parseValue()
            skipWs()
            when (peek()) {
                ',' -> { pos++; continue }
                ']' -> { pos++; break }
                else -> fail("expected ',' or ']'")
            }
        }
        return JsonValue.JsonArray(items)
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (pos < text.length) {
            val c = text[pos++]
            when {
                c == '"' -> return builder.toString()
                c == '\\' -> {
                    if (pos >= text.length) fail("unterminated escape")
                    when (val escaped = text[pos++]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\f')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) fail("bad unicode escape")
                            val hex = text.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16) ?: fail("bad unicode escape '$hex'")
                            builder.append(code.toChar())
                            pos += 4
                        }
                        else -> fail("bad escape '\\$escaped'")
                    }
                }
                else -> builder.append(c)
            }
        }
        fail("unterminated string")
    }

    private fun parseNumber(): JsonValue.JsonNum {
        val start = pos
        if (peek() == '-') pos++
        when {
            peek() == '0' -> pos++
            peek()?.isDigit() == true -> while (peek()?.isDigit() == true) pos++
            else -> fail("bad number")
        }
        var fractional = false
        if (peek() == '.') {
            fractional = true
            pos++
            if (peek()?.isDigit() != true) fail("bad fraction")
            while (peek()?.isDigit() == true) pos++
        }
        if (peek() == 'e' || peek() == 'E') {
            fractional = true
            pos++
            if (peek() == '+' || peek() == '-') pos++
            if (peek()?.isDigit() != true) fail("bad exponent")
            while (peek()?.isDigit() == true) pos++
        }
        val token = text.substring(start, pos)
        val value = token.toDoubleOrNull() ?: fail("number out of range '$token'")
        return JsonValue.JsonNum(value, isIntegral = !fractional)
    }

    private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
        if (!text.startsWith(literal, pos)) fail("expected '$literal'")
        pos += literal.length
        return value
    }

    private fun skipWs() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun peek(): Char? = text.getOrNull(pos)

    private fun expect(c: Char) {
        if (pos >= text.length || text[pos] != c) fail("expected '$c'")
        pos++
    }

    private fun fail(message: String): Nothing =
        throw IllegalArgumentException("invalid JSON at $pos: $message")
}

private class JsonWriter {
    private val builder = StringBuilder()

    fun write(value: JsonValue): JsonWriter {
        when (value) {
            is JsonValue.JsonNull -> builder.append("null")
            is JsonValue.JsonBool -> builder.append(value.value)
            is JsonValue.JsonNum -> writeNumber(value)
            is JsonValue.JsonStr -> writeString(value.value)
            is JsonValue.JsonArray -> {
                builder.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) builder.append(',')
                    write(item)
                }
                builder.append(']')
            }
            is JsonValue.JsonObject -> {
                builder.append('{')
                var i = 0
                for ((k, v) in value.entries) {
                    if (i++ > 0) builder.append(',')
                    writeString(k)
                    builder.append(':')
                    write(v)
                }
                builder.append('}')
            }
        }
        return this
    }

    private fun writeNumber(num: JsonValue.JsonNum) {
        if (!num.value.isFinite()) {
            // JSON cannot represent NaN/Infinity; emit null like most JS engines.
            builder.append("null")
        } else if (num.isIntegral && num.value % 1.0 == 0.0) {
            builder.append(num.value.toLong())
        } else {
            builder.append(num.value.toString())
        }
    }

    private fun writeString(value: String) {
        builder.append('"')
        for (c in value) {
            when (c) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else -> if (c.code < 0x20) {
                    builder.append("\\u%04x".format(c.code))
                } else {
                    builder.append(c)
                }
            }
        }
        builder.append('"')
    }

    fun build(): String = builder.toString()
}
