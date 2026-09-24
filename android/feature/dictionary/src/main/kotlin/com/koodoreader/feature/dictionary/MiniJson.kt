package com.koodoreader.feature.dictionary

/**
 * Minimal JSON codec for the dictionary index.
 *
 * WHY NOT org.json / kotlinx.serialization: this module is unit-tested on a plain
 * JVM (the Android `org.json` stub throws in local unit tests unless
 * `returnDefaultValues` is enabled) and the module's dependency list is
 * deliberately empty — the same reasoning as `:core:designsystem` and
 * `:engine:mobi`. The subset needed by [DictStore] is small: objects, arrays,
 * strings, booleans, numbers and null.
 *
 * The desktop stores the same data through `ConfigService.setObjectConfig(id, …,
 * "customDicts")` / `setListConfig(id, "dictList")` (src/utils/file/dictUtil.ts:111-136),
 * i.e. inside electron-store's `config.json`; this codec writes the equivalent
 * subtree into `<dict folder>/dict-index.json` so the P7 backup round-trip can
 * translate one into the other.
 */
object MiniJson {

    fun write(value: Any?): String {
        val out = StringBuilder()
        writeValue(out, value)
        return out.toString()
    }

    private fun writeValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(value.toString())
            is Int -> out.append(value.toString())
            is Long -> out.append(value.toString())
            is Double -> out.append(
                if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString()
                else value.toString()
            )
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k.toString())
                    out.append(':')
                    writeValue(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    writeValue(out, item)
                }
                out.append(']')
            }
            else -> writeString(out, value.toString())
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append('"')
    }

    /** Parse a JSON document; throws [IllegalArgumentException] on malformed input. */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw IllegalArgumentException("trailing content at ${parser.position}")
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        (parse(text) as? Map<String, Any?>) ?: emptyMap()

    private class Parser(private val text: String) {
        var position = 0

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw IllegalArgumentException("unexpected end of input")
            return when (val ch = text[position]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (ch == '-' || ch.isDigit()) parseNumber()
                else throw IllegalArgumentException("unexpected character '$ch' at $position")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect("{")
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { position++; return map }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(":")
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> { position++; return map }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $position")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect("[")
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { position++; return list }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> { position++; return list }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $position")
                }
            }
        }

        private fun parseString(): String {
            expect("\"")
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("unterminated string")
                when (val ch = text[position++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) throw IllegalArgumentException("unterminated escape")
                        when (val esc = text[position++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) {
                                    throw IllegalArgumentException("truncated \\u escape")
                                }
                                out.append(text.substring(position, position + 4).toInt(16).toChar())
                                position += 4
                            }
                            else -> throw IllegalArgumentException("bad escape '\\$esc'")
                        }
                    }
                    else -> out.append(ch)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = position
            if (peek() == '-') position++
            while (!atEnd() && (text[position].isDigit() || text[position] in ".eE+-")) position++
            val raw = text.substring(start, position)
            return raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun peek(): Char =
            if (atEnd()) throw IllegalArgumentException("unexpected end of input") else text[position]

        private fun expect(literal: String) {
            if (!text.startsWith(literal, position)) {
                throw IllegalArgumentException("expected '$literal' at $position")
            }
            position += literal.length
        }
    }
}
