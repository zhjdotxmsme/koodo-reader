package com.koodoreader.engine.feature

/**
 * Dependency-free JSON tree, parser and writer.
 *
 * `:engine:feature` has **zero runtime dependencies** (see build.gradle), so the
 * text-rule import/export format is hand-rolled here instead of pulling in
 * kotlinx.serialization / Gson / org.json (the latter is not even on a pure JVM
 * classpath).
 *
 * The accepted language is strict JSON — no comments, no trailing commas —
 * which is exactly what `JSON.stringify` produces on the desktop side.
 */
internal sealed class JsonValue {
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    object Null : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
}

internal fun JsonValue?.asStr(): String? = (this as? JsonValue.Str)?.value

/** Accepts a real JSON boolean as well as the desktop `"yes"` / `"no"` strings. */
internal fun JsonValue?.asBool(): Boolean? = when (this) {
    is JsonValue.Bool -> value
    is JsonValue.Str -> when (value.lowercase()) {
        "yes", "true", "1" -> true
        "no", "false", "0" -> false
        else -> null
    }
    else -> null
}

internal fun JsonValue?.asObj(): JsonValue.Obj? = this as? JsonValue.Obj

internal class JsonParseException(message: String) : Exception(message)

internal object Json {

    fun parse(text: String): JsonValue = JsonParser(text).parse()

    fun write(value: JsonValue): String = StringBuilder().also { writeTo(it, value) }.toString()

    fun writePretty(value: JsonValue, indent: String = "  "): String =
        StringBuilder().also { writePretty(it, value, indent, 0) }.toString()

    // ---------------------------------------------------------------- writing

    private fun indentTo(sb: StringBuilder, indent: String, depth: Int) {
        sb.append('\n')
        repeat(depth) { sb.append(indent) }
    }

    private fun writePretty(sb: StringBuilder, value: JsonValue, indent: String, depth: Int) {
        when (value) {
            is JsonValue.Arr -> {
                if (value.items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    indentTo(sb, indent, depth + 1)
                    writePretty(sb, item, indent, depth + 1)
                }
                indentTo(sb, indent, depth)
                sb.append(']')
            }
            is JsonValue.Obj -> {
                if (value.fields.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((key, item) in value.fields) {
                    if (!first) sb.append(',')
                    first = false
                    indentTo(sb, indent, depth + 1)
                    writeString(sb, key)
                    sb.append(": ")
                    writePretty(sb, item, indent, depth + 1)
                }
                indentTo(sb, indent, depth)
                sb.append('}')
            }
            else -> writeTo(sb, value)
        }
    }

    private fun writeTo(sb: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.Str -> writeString(sb, value.value)
            is JsonValue.Num -> sb.append(formatNumber(value.value))
            is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
            JsonValue.Null -> sb.append("null")
            is JsonValue.Arr -> {
                sb.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    writeTo(sb, item)
                }
                sb.append(']')
            }
            is JsonValue.Obj -> {
                sb.append('{')
                var first = true
                for ((key, item) in value.fields) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, key)
                    sb.append(':')
                    writeTo(sb, item)
                }
                sb.append('}')
            }
        }
    }

    private fun formatNumber(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "null"
        if (d == d.toLong().toDouble()) return d.toLong().toString()
        return d.toString()
    }

    private fun writeString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

internal class JsonParser(private val src: String) {
    private var pos = 0

    fun parse(): JsonValue {
        skipWs()
        val value = parseValue()
        skipWs()
        if (pos != src.length) fail("trailing content")
        return value
    }

    private fun fail(message: String): Nothing =
        throw JsonParseException("$message at offset $pos")

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun parseValue(): JsonValue {
        if (pos >= src.length) fail("unexpected end of input")
        return when (val c = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't' -> { expect("true"); JsonValue.Bool(true) }
            'f' -> { expect("false"); JsonValue.Bool(false) }
            'n' -> { expect("null"); JsonValue.Null }
            else ->
                if (c == '-' || c.isDigit()) parseNumber() else fail("unexpected character '$c'")
        }
    }

    private fun expect(word: String) {
        if (!src.startsWith(word, pos)) fail("expected '$word'")
        pos += word.length
    }

    private fun parseObject(): JsonValue.Obj {
        pos++ // '{'
        val fields = LinkedHashMap<String, JsonValue>()
        skipWs()
        if (pos < src.length && src[pos] == '}') { pos++; return JsonValue.Obj(fields) }
        while (true) {
            skipWs()
            if (pos >= src.length || src[pos] != '"') fail("expected object key")
            val key = parseString()
            skipWs()
            if (pos >= src.length || src[pos] != ':') fail("expected ':'")
            pos++
            skipWs()
            fields[key] = parseValue()
            skipWs()
            if (pos >= src.length) fail("unterminated object")
            when (src[pos]) {
                ',' -> pos++
                '}' -> { pos++; return JsonValue.Obj(fields) }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): JsonValue.Arr {
        pos++ // '['
        val items = ArrayList<JsonValue>()
        skipWs()
        if (pos < src.length && src[pos] == ']') { pos++; return JsonValue.Arr(items) }
        while (true) {
            skipWs()
            items.add(parseValue())
            skipWs()
            if (pos >= src.length) fail("unterminated array")
            when (src[pos]) {
                ',' -> pos++
                ']' -> { pos++; return JsonValue.Arr(items) }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) fail("unterminated string")
            val c = src[pos++]
            if (c == '"') return sb.toString()
            if (c != '\\') { sb.append(c); continue }
            if (pos >= src.length) fail("unterminated escape")
            when (val esc = src[pos++]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (pos + 4 > src.length) fail("truncated unicode escape")
                    val hex = src.substring(pos, pos + 4)
                    val code = hex.toIntOrNull(16) ?: fail("bad unicode escape '\\u$hex'")
                    pos += 4
                    sb.append(code.toChar())
                }
                else -> fail("bad escape '\\$esc'")
            }
        }
    }

    private fun parseNumber(): JsonValue.Num {
        val start = pos
        if (pos < src.length && src[pos] == '-') pos++
        while (pos < src.length && src[pos].isDigit()) pos++
        if (pos < src.length && src[pos] == '.') {
            pos++
            while (pos < src.length && src[pos].isDigit()) pos++
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            while (pos < src.length && src[pos].isDigit()) pos++
        }
        val raw = src.substring(start, pos)
        return JsonValue.Num(raw.toDoubleOrNull() ?: fail("invalid number '$raw'"))
    }
}
