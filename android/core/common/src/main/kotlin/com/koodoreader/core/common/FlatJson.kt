package com.koodoreader.core.common

/**
 * Minimal flat JSON object parser: `{"key": "value", ...}` — exactly the
 * shape of the desktop locale files (verified: every value is a string).
 * Written by hand so this module (and its JVM tests) need no JSON library
 * and behave identically on JVM and Android (no org.json dependency).
 *
 * Supports standard JSON string escapes: \" \\ \/ \b \f \n \r \t \uXXXX.
 */
object FlatJson {

    /** Parse a flat JSON object; non-string values are ignored. Throws [FlatJsonException] on malformed input. */
    fun parse(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        val s = text.trim()
        if (!s.startsWith("{")) throw FlatJsonException("not a JSON object")
        var i = 1
        while (true) {
            i = skipWs(s, i)
            if (i >= s.length) throw FlatJsonException("unterminated object")
            val c = s[i]
            if (c == '}') return out
            if (c == ',') { i++; continue }
            if (c != '"') throw FlatJsonException("expected key at $i")
            val key = readString(s, i).also { i = it.second }.first
            i = skipWs(s, i)
            if (i >= s.length || s[i] != ':') throw FlatJsonException("expected ':' at $i")
            i = skipWs(s, i + 1)
            if (i >= s.length || s[i] != '"') {
                // Non-string value (should not happen in locale files): skip it.
                i = skipValue(s, i)
                continue
            }
            val value = readString(s, i).also { i = it.second }.first
            out[key] = value
        }
    }

    private fun skipWs(s: String, i: Int): Int {
        var j = i
        while (j < s.length && s[j].isWhitespace()) j++
        return j
    }

    /** Read a JSON string starting at [start] (a quote); returns value + next index. */
    private fun readString(s: String, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> return sb.toString() to (i + 1)
                c == '\\' -> {
                    if (i + 1 >= s.length) throw FlatJsonException("bad escape")
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
                            if (i + 6 > s.length) throw FlatJsonException("bad unicode escape")
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16) ?: throw FlatJsonException("bad unicode escape: $hex")
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> throw FlatJsonException("unknown escape: \\$e")
                    }
                    i += 2
                }
                else -> { sb.append(c); i++ }
            }
        }
        throw FlatJsonException("unterminated string")
    }

    /** Skip a non-string JSON value (number/bool/null/object/array) conservatively. */
    private fun skipValue(s: String, i: Int): Int {
        var j = skipWs(s, i)
        if (j >= s.length) return j
        when (s[j]) {
            '{', '[' -> {
                val open = s[j]; val close = if (open == '{') '}' else ']'
                var depth = 0
                while (j < s.length) {
                    if (s[j] == open) depth++
                    if (s[j] == close) { depth--; if (depth == 0) return j + 1 }
                    j++
                }
                throw FlatJsonException("unterminated nested value")
            }
            else -> {
                while (j < s.length && s[j] != ',' && s[j] != '}') j++
                return j
            }
        }
    }
}

/** Reported when a catalog file is not valid flat-locale JSON. */
class FlatJsonException(message: String) : RuntimeException(message)
