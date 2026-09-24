package com.koodoreader.feature.translate

/**
 * Dependency-free JSON reader/writer.
 *
 * Why not org.json / kotlinx.serialization:
 *  - `org.json` is part of the Android framework: its stubs throw
 *    `RuntimeException("Stub!")` in plain JVM unit tests, which would make the
 *    response-parsing tests Android-instrumented.
 *  - kotlinx.serialization would add a compiler plugin to a module whose only
 *    JSON needs are "read a nested field" and "write a small request body".
 *
 * The parser is a strict recursive-descent reader for RFC 8259 documents
 * (objects, arrays, strings with `\uXXXX` escapes, numbers, booleans, null).
 * It returns `null` for malformed input instead of throwing, because every
 * caller here maps a parse failure onto a user-visible "provider returned an
 * unexpected payload" state.
 */
sealed interface JsonValue {
    data class JsonString(val value: String) : JsonValue
    data class JsonNumber(val raw: String) : JsonValue {
        val value: Double get() = raw.toDoubleOrNull() ?: Double.NaN
        val longValue: Long get() = raw.toDoubleOrNull()?.toLong() ?: 0L
    }

    data class JsonBool(val value: Boolean) : JsonValue
    data object JsonNull : JsonValue
    data class JsonArray(val items: List<JsonValue>) : JsonValue
    data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue
}

private class JsonParseException(message: String) : Exception(message)

object MiniJson {

    /** Parses [text]; returns `null` when the document is malformed. */
    fun parse(text: String): JsonValue? {
        if (text.isBlank()) {
            return null
        }
        return try {
            val reader = JsonReader(text)
            val value = reader.readValue()
            reader.skipWhitespace()
            if (!reader.atEnd()) {
                null
            } else {
                value
            }
        } catch (_: JsonParseException) {
            null
        }
    }

    /** Parses and requires a top-level object (LLM/providers always answer with one). */
    fun parseObject(text: String): JsonValue.JsonObject? = parse(text) as? JsonValue.JsonObject

    /** Parses and requires a top-level array (Microsoft Translator answers with one). */
    fun parseArray(text: String): JsonValue.JsonArray? = parse(text) as? JsonValue.JsonArray

    /** Serialises one of `String` / `Number` / `Boolean` / `List<*>` / `JsonValue` / `null`. */
    fun write(value: Any?): String = buildString { writeValue(this, value) }

    /**
     * Builds a flat JSON object body, e.g.
     * `body("q" to text, "target" to to, "format" to "text")`.
     */
    fun body(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            "${quote(key)}:${write(value)}"
        }

    fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }

    private fun writeValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is JsonValue -> out.append(writeJsonValue(value))
            is String -> out.append(quote(value))
            is Boolean -> out.append(value.toString())
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> out.append(if (value.isFinite()) value.toString() else "0")
            is Float -> out.append(if (value.isFinite()) value.toString() else "0")
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { position, item ->
                    if (position > 0) {
                        out.append(',')
                    }
                    writeValue(out, item)
                }
                out.append(']')
            }
            is Map<*, *> -> out.append(
                value.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
                    "${quote(k.toString())}:${write(v)}"
                },
            )
            else -> out.append(quote(value.toString()))
        }
    }

    private fun writeJsonValue(value: JsonValue): String = when (value) {
        is JsonValue.JsonString -> quote(value.value)
        is JsonValue.JsonNumber -> value.raw
        is JsonValue.JsonBool -> value.value.toString()
        JsonValue.JsonNull -> "null"
        is JsonValue.JsonArray -> value.items.joinToString(prefix = "[", postfix = "]", separator = ",") { writeJsonValue(it) }
        is JsonValue.JsonObject -> value.fields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
            "${quote(it.key)}:${writeJsonValue(it.value)}"
        }
    }
}

/** Path lookup: `String` steps address object fields, `Int` steps address array indices. */
fun JsonValue?.at(vararg path: Any): JsonValue? {
    var current: JsonValue? = this
    for (step in path) {
        current = when (step) {
            is String -> (current as? JsonValue.JsonObject)?.fields?.get(step)
            is Int -> (current as? JsonValue.JsonArray)?.items?.getOrNull(step)
            else -> null
        }
        if (current == null) {
            return null
        }
    }
    return current
}

fun JsonValue?.stringAt(vararg path: Any): String? = (this.at(*path) as? JsonValue.JsonString)?.value

fun JsonValue?.intAt(vararg path: Any): Int? = (this.at(*path) as? JsonValue.JsonNumber)?.value?.toInt()

/** `&quot;`-style entities are returned verbatim by the Google v2 endpoint. */
object HtmlEntities {
    fun unescape(text: String): String {
        if (!text.contains('&')) {
            return text
        }
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch != '&') {
                out.append(ch)
                index++
                continue
            }
            val end = text.indexOf(';', startIndex = index + 1)
            if (end == -1 || end - index > 12) {
                out.append(ch)
                index++
                continue
            }
            val entity = text.substring(index + 1, end)
            val decoded = decodeEntity(entity)
            if (decoded == null) {
                out.append(ch)
                index++
            } else {
                out.append(decoded)
                index = end + 1
            }
        }
        return out.toString()
    }

    private fun decodeEntity(entity: String): String? = when {
        entity.startsWith("#x") || entity.startsWith("#X") ->
            entity.drop(2).toIntOrNull(16)?.let { codePointToString(it) }
        entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let { codePointToString(it) }
        else -> when (entity.lowercase()) {
            "quot" -> "\""
            "apos" -> "'"
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "nbsp" -> "\u00A0"
            "hellip" -> "\u2026"
            "mdash" -> "\u2014"
            "ndash" -> "\u2013"
            "lsquo" -> "\u2018"
            "rsquo" -> "\u2019"
            "ldquo" -> "\u201C"
            "rdquo" -> "\u201D"
            else -> null
        }
    }

    private fun codePointToString(codePoint: Int): String? =
        if (codePoint in 1..0x10FFFF) String(Character.toChars(codePoint)) else null
}

private class JsonReader(private val text: String) {
    private var index = 0

    fun atEnd(): Boolean = index >= text.length

    fun skipWhitespace() {
        while (index < text.length && text[index].isJsonWhitespace()) {
            index++
        }
    }

    fun readValue(): JsonValue {
        skipWhitespace()
        if (atEnd()) {
            throw JsonParseException("unexpected end of input")
        }
        return when (val ch = text[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.JsonString(readString())
            't' -> readLiteral("true", JsonValue.JsonBool(true))
            'f' -> readLiteral("false", JsonValue.JsonBool(false))
            'n' -> readLiteral("null", JsonValue.JsonNull)
            else -> if (ch == '-' || ch.isDigit()) readNumber() else throw JsonParseException("unexpected character '$ch'")
        }
    }

    private fun readObject(): JsonValue.JsonObject {
        expect('{')
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return JsonValue.JsonObject(fields)
        }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(':')
            fields[key] = readValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return JsonValue.JsonObject(fields)
                }
                else -> throw JsonParseException("expected ',' or '}' at $index")
            }
        }
    }

    private fun readArray(): JsonValue.JsonArray {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return JsonValue.JsonArray(items)
        }
        while (true) {
            items.add(readValue())
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return JsonValue.JsonArray(items)
                }
                else -> throw JsonParseException("expected ',' or ']' at $index")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (atEnd()) {
                throw JsonParseException("unterminated string")
            }
            val ch = text[index++]
            when {
                ch == '"' -> return out.toString()
                ch == '\\' -> {
                    if (atEnd()) {
                        throw JsonParseException("unterminated escape")
                    }
                    when (val esc = text[index++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) {
                                throw JsonParseException("truncated \\u escape")
                            }
                            val hex = text.substring(index, index + 4)
                            index += 4
                            val code = hex.toIntOrNull(16) ?: throw JsonParseException("bad \\u escape '$hex'")
                            out.append(code.toChar())
                        }
                        else -> throw JsonParseException("bad escape '\\$esc'")
                    }
                }
                else -> out.append(ch)
            }
        }
    }

    private fun readNumber(): JsonValue.JsonNumber {
        val start = index
        if (peek() == '-') {
            index++
        }
        while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) {
            index++
        }
        val raw = text.substring(start, index)
        if (raw.toDoubleOrNull() == null) {
            throw JsonParseException("bad number '$raw'")
        }
        return JsonValue.JsonNumber(raw)
    }

    private fun <T : JsonValue> readLiteral(literal: String, value: T): T {
        if (!text.startsWith(literal, index)) {
            throw JsonParseException("expected '$literal' at $index")
        }
        index += literal.length
        return value
    }

    private fun expect(ch: Char) {
        skipWhitespace()
        if (peek() != ch) {
            throw JsonParseException("expected '$ch' at $index")
        }
        index++
    }

    private fun peek(): Char = if (atEnd()) '\u0000' else text[index]
}

private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'
