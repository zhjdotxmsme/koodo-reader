package com.koodoreader.engine.cfi

/**
 * CFI tokenizer — port of foliate-js `tokenizer()` (`epubcfi.js`, MIT).
 *
 * Upstream structure, reproduced verbatim:
 *
 * ```
 * const tokenizer = str => {
 *     const tokens = []
 *     let state, escape, value = ''
 *     const push = x => (tokens.push(x), state = null, value = '')
 *     const cat = x => (value += x, escape = false)
 *     for (const char of Array.from(str.trim()).concat('')) {
 *         if (char === '^' && !escape) { escape = true; continue }
 *         if (state === '!') push(['!'])
 *         else if (state === ',') push([','])
 *         else if (state === '/' || state === ':') { ... }
 *         ...
 *     }
 * }
 * ```
 *
 * Faithfulness notes (deliberate reproductions, not rewrites):
 *
 * 1. The JS loop iterates the trimmed string **plus a final empty-string
 *    sentinel**, which flushes a pending numeric state (`/`, `:`, `~`, `@`) but
 *    does nothing for `[` and `;` states — there the sentinel lands in the
 *    `else cat(char)` branch where `''` is a no-op. Kotlin models the sentinel as
 *    `null` and reproduces exactly that.
 * 2. `^` escapes the next character only where upstream checks `!escape` (the
 *    `;` / `,` / `]` / `=` delimiters and `^` itself); `escape` is reset by every
 *    `cat`.
 * 3. An unterminated assertion (`[abc` with no `]`) yields **no token** upstream:
 *    the content is silently dropped. Same here.
 *
 * DELIBERATE DEVIATION (documented, covered by `CfiParseTest`): upstream calls
 * `parseInt('')` / `parseFloat('')` for malformed numeric steps, producing `NaN`
 * that then propagates invisibly through the whole pipeline. This port raises a
 * typed [CfiException] instead, so the reader layer can react (skip the
 * annotation, fall back to chapter + percentage) instead of silently
 * mis-locating the user.
 */

/** A single CFI token. Mirrors the `[type, value]` tuples emitted upstream. */
internal sealed class CfiToken {
    /** `!` — move into the next document. */
    data object Indirection : CfiToken()

    /** `,` — separates `parent`, `start`, `end` of a range CFI. */
    data object RangeSeparator : CfiToken()

    /** `/n` — a step index. */
    data class Step(val index: Int) : CfiToken()

    /** `:n` — a character offset. */
    data class Offset(val offset: Int) : CfiToken()

    /** `~n` — a temporal offset. */
    data class Temporal(val value: Double) : CfiToken()

    /** `@n` — one component of a spatial offset (two per `@x:y`). */
    data class Spatial(val value: Double) : CfiToken()

    /** One chunk of an assertion body (terminated by `,` or `]`). */
    data class Assertion(val value: String) : CfiToken()

    /**
     * `;name=value` — upstream's dynamically typed `;${value}` token. Only
     * `name == "s"` (side bias) carries meaning; anything else is ignored by the
     * parser, exactly like upstream.
     */
    data class NamedAssertion(val name: String, val value: String) : CfiToken()
}

/** Characters that (re)start an accumulator state when seen outside a value. */
private const val STRUCTURAL_CHARS = "/:~@[!,"

/**
 * Tokenize the *inner* part of a CFI (without the `epubcfi(...)` wrapper), i.e.
 * `unwrapCfi(...)` output.
 *
 * @param input raw CFI body, e.g. `/6/4[chap]!/4/2/2:10`
 * @throws CfiException when a numeric field has no digits (see deviation note)
 */
internal fun tokenizeCfi(input: String): List<CfiToken> {
    val tokens = mutableListOf<CfiToken>()

    // `state` is a char for '/', ':', '~', '@', '[', '!', ','. The named
    // assertion state (';' / ';s') is tracked in `namedState` instead, matching
    // upstream's two different `state` representations.
    var state: Char? = null
    var namedState: String? = null
    var escape = false
    val value = StringBuilder()

    fun push(token: CfiToken) {
        tokens += token
        state = null
        namedState = null
        value.setLength(0)
    }

    fun cat(char: Char?) {
        if (char != null) value.append(char)
        escape = false
    }

    // Trimmed input + the upstream '' sentinel, modelled as `null`.
    val chars: List<Char?> = input.trim().toCharArray().map { it as Char? } + listOf<Char?>(null)

    for (char in chars) {
        val isDigit = char != null && char.isDigit()
        val isNumberLike = isDigit || char == '.'

        if (char == '^' && !escape) {
            escape = true
            continue
        }

        if (state == '!') {
            push(CfiToken.Indirection)
        } else if (state == ',') {
            push(CfiToken.RangeSeparator)
        } else if (state == '/' || state == ':') {
            if (isDigit) {
                cat(char)
                continue
            }
            valueInto(state!!, value.toString())?.let { push(it) }
            // NOTE: no `continue` — upstream falls through to the state switch at
            // the bottom, so `char` may immediately open the next state.
        } else if (state == '~') {
            if (isNumberLike) {
                cat(char)
                continue
            }
            valueInto('~', value.toString())?.let { push(it) }
        } else if (state == '@') {
            // Upstream special case: a ':' inside a spatial offset pushes the
            // current component and then RESTORES the '@' state (via an explicit
            // `state = '@'; continue`), so `@10:20` yields two Spatial tokens.
            // Falling through to the generic state switch here would produce an
            // Offset token for `20` and silently drop the second component.
            if (char == ':') {
                valueInto('@', value.toString())?.let { push(it) }
                state = '@'
                continue
            }
            if (isNumberLike) {
                cat(char)
                continue
            }
            valueInto('@', value.toString())?.let { push(it) }
        } else if (state == '[') {
            if (char == ';' && !escape) {
                push(CfiToken.Assertion(value.toString()))
                state = ';'
                namedState = ";"
            } else if (char == ',' && !escape) {
                push(CfiToken.Assertion(value.toString()))
                state = '['
                namedState = null
            } else if (char == ']' && !escape) {
                push(CfiToken.Assertion(value.toString()))
            } else {
                cat(char)
            }
            continue
        } else if (namedState != null) {
            if (char == '=' && !escape) {
                namedState = ";" + value
                value.setLength(0)
            } else if (char == ';' && !escape) {
                push(CfiToken.NamedAssertion(assertionName(namedState!!), value.toString()))
                state = ';'
                namedState = ";"
            } else if (char == ']' && !escape) {
                push(CfiToken.NamedAssertion(assertionName(namedState!!), value.toString()))
            } else {
                cat(char)
            }
            continue
        }

        if (char != null && STRUCTURAL_CHARS.indexOf(char) >= 0) {
            state = char
            namedState = null
        }
    }

    return tokens
}

/** `state` in `[`, `;`, `;s` … → the assertion name (`""`, `"s"`, …). */
private fun assertionName(state: String): String =
    if (state.startsWith(";")) state.substring(1) else state

/**
 * Convert a pending numeric accumulator into a token, mirroring upstream
 * `push([state, parseInt(value)])` / `parseFloat(value)`.
 *
 * Returns `null` for the impossible case of an empty accumulator on a branch
 * upstream would not reach (kept total on purpose).
 *
 * @throws CfiException typed error instead of upstream's silently-propagating `NaN`
 */
private fun valueInto(state: Char, raw: String): CfiToken? {
    val text = raw.trim()
    when (state) {
        '/' -> {
            requireDigits(text, state, raw, CfiErrorCode.INVALID_INDEX, "step index")
            return CfiToken.Step(parseIntOrThrow(text, CfiErrorCode.INVALID_INDEX, "step index"))
        }
        ':' -> {
            requireDigits(text, state, raw, CfiErrorCode.INVALID_OFFSET, "character offset")
            return CfiToken.Offset(
                parseIntOrThrow(text, CfiErrorCode.INVALID_OFFSET, "character offset")
            )
        }
        '~' -> {
            requireDigits(text, state, raw, CfiErrorCode.INVALID_TEMPORAL, "temporal offset")
            return CfiToken.Temporal(
                parseDoubleOrThrow(text, CfiErrorCode.INVALID_TEMPORAL, "temporal offset")
            )
        }
        '@' -> {
            requireDigits(text, state, raw, CfiErrorCode.INVALID_SPATIAL, "spatial offset")
            return CfiToken.Spatial(
                parseDoubleOrThrow(text, CfiErrorCode.INVALID_SPATIAL, "spatial offset")
            )
        }
    }
    return null
}

/** Guard used by the deviation paths (upstream would produce `NaN` here). */
private fun requireDigits(
    text: String,
    state: Char,
    raw: String,
    code: CfiErrorCode,
    what: String,
) {
    if (text.isEmpty()) {
        throw CfiException(
            code,
            "CFI $what is missing digits (upstream would silently produce NaN)",
            mapOf("state" to state.toString(), "raw" to raw),
        )
    }
}

/** `parseInt(value)` with a typed failure instead of a silent `NaN`. */
private fun parseIntOrThrow(raw: String, code: CfiErrorCode, what: String): Int {
    val parsed = raw.toIntOrNull()
    if (parsed == null) {
        throw CfiException(code, "CFI $what is not a valid integer: \"$raw\"", mapOf("raw" to raw))
    }
    return parsed
}

/** `parseFloat(value)` with a typed failure instead of a silent `NaN`. */
private fun parseDoubleOrThrow(raw: String, code: CfiErrorCode, what: String): Double {
    val parsed = raw.toDoubleOrNull()
    if (parsed == null || parsed.isNaN()) {
        throw CfiException(code, "CFI $what is not a valid number: \"$raw\"", mapOf("raw" to raw))
    }
    return parsed
}


