package com.koodoreader.engine.cfi

/**
 * CFI parser — port of foliate-js `parser` / `parserIndir` / `parse` plus the
 * `splitAt` helper (`epubcfi.js`, MIT).
 *
 * Upstream:
 *
 * ```
 * const splitAt = (arr, is) => [-1, ...is, arr.length].reduce(({ xs, a }, b) =>
 *     ({ xs: xs?.concat([arr.slice(a + 1, b)]) ?? [], a: b }), {}).xs
 * const parserIndir = tokens => splitAt(tokens, findTokens(tokens, '!')).map(parser)
 * export const parse = cfi => {
 *     const tokens = tokenizer(unwrap(cfi))
 *     const commas = findTokens(tokens, ',')
 *     if (!commas.length) return parserIndir(tokens)
 *     const [parent, start, end] = splitAt(tokens, commas).map(parserIndir)
 *     return { parent, start, end }
 * }
 * ```
 *
 * Two upstream details are reproduced on purpose:
 *
 * 1. `splitAt` **drops the element at every split index** (the `!` / `,` itself),
 *    which is why the slices are `(index + 1, nextIndex)` rather than
 *    `(index, nextIndex)`.
 * 2. In `parser`, the `text` branch does `continue` **before** `state = type`, so
 *    a text assertion does not refresh the "previous token type". Only the `id`
 *    branch refreshes it. This is observable for input like `/2[x,y]`
 *    (`y` becomes the id because `state` was still `'/'`), and is pinned by a
 *    golden vector.
 *
 * DELIBERATE DEVIATIONS (typed errors instead of upstream `TypeError` / silent
 * corruption), each covered by `CfiParseTest`:
 *  - a leading offset/assertion with no preceding step (upstream: reading a
 *    property of `undefined`) → [CfiErrorCode.EMPTY_PATH];
 *  - a range CFI with fewer than three parts (upstream: `map` over `undefined`)
 *    → [CfiErrorCode.RANGE_INCOMPLETE].
 */

/**
 * Split [items] at every index in [indices], dropping the elements at those
 * indexes. Port of upstream `splitAt`.
 *
 * `splitAt([a, X, b], [1])` → `[[a], [b]]`
 */
internal fun <T> splitAt(items: List<T>, indices: List<Int>): List<List<T>> {
    val boundaries = mutableListOf(-1)
    boundaries += indices
    boundaries += items.size

    val out = mutableListOf<List<T>>()
    var previous = -1
    for (boundary in boundaries.drop(1)) {
        val from = (previous + 1).coerceIn(0, items.size)
        val to = boundary.coerceIn(0, items.size)
        out += if (from <= to) items.subList(from, to) else emptyList()
        previous = boundary
    }
    return out
}

/**
 * Parse one document's token run into steps. Port of upstream `parser`.
 *
 * @throws CfiException [CfiErrorCode.EMPTY_PATH] when a token appears before any
 *   step (upstream would throw `TypeError: Cannot set property of undefined`)
 */
internal fun parseSteps(tokens: List<CfiToken>): CfiPath {
    var parts: List<CfiStep> = emptyList()
    var state: CfiToken? = null

    fun lastIndexOrThrow(): Int {
        if (parts.isEmpty()) {
            throw CfiException(
                CfiErrorCode.EMPTY_PATH,
                "CFI starts with an offset/assertion instead of a step",
                mapOf("tokens" to tokens.map { it.toString() }),
            )
        }
        return parts.size - 1
    }

    fun update(index: Int, transform: (CfiStep) -> CfiStep) {
        val mutable = parts.toMutableList()
        mutable[index] = transform(mutable[index])
        parts = mutable
    }

    for (token in tokens) {
        if (token is CfiToken.Step) {
            parts = parts + CfiStep(index = token.index)
        } else {
            val last = lastIndexOrThrow()
            when (token) {
                is CfiToken.Offset -> update(last) { it.copy(offset = token.offset) }
                is CfiToken.Temporal -> update(last) { it.copy(temporal = token.value) }
                is CfiToken.Spatial ->
                    update(last) { it.copy(spatial = (it.spatial ?: emptyList()) + token.value) }
                is CfiToken.NamedAssertion ->
                    if (token.name == "s") update(last) { it.copy(side = token.value) }
                is CfiToken.Assertion -> {
                    if (state is CfiToken.Step && token.value.isNotEmpty()) {
                        update(last) { it.copy(id = token.value) }
                    } else {
                        // NOTE: upstream `continue`s here, so `state` is NOT
                        // refreshed — reproduced by skipping the assignment below.
                        update(last) { it.copy(text = (it.text ?: emptyList()) + token.value) }
                        continue
                    }
                }
                else -> {
                    // Indirection / range separators never reach a single
                    // document's token run (they are split away first).
                }
            }
        }
        state = token
    }

    return parts
}

/** Split on `!` and parse each document. Port of upstream `parserIndir`. */
internal fun parseDocuments(tokens: List<CfiToken>): CfiDocuments =
    splitAt(tokens, tokens.indices.filter { tokens[it] is CfiToken.Indirection })
        .map { parseSteps(it) }

/**
 * Parse a CFI string into a [Cfi]. Port of upstream `parse` (including the
 * `unwrap` of a surrounding `epubcfi(...)`).
 *
 * @param cfi e.g. `epubcfi(/6/4[chap01ref]!/4/2/2:10)` or a range
 *   `epubcfi(/6/4[chap01ref]!/4,/2/1:1,/3:4)`
 * @throws CfiException on malformed input (see the class KDoc for deviations)
 */
fun parse(cfi: String): Cfi {
    val tokens = tokenizeCfi(unwrapCfi(cfi))
    val commas = tokens.indices.filter { tokens[it] is CfiToken.RangeSeparator }
    if (commas.isEmpty()) {
        return Cfi.Point(parseDocuments(tokens))
    }

    val parts = splitAt(tokens, commas).map { parseDocuments(it) }
    if (parts.size < 3) {
        throw CfiException(
            CfiErrorCode.RANGE_INCOMPLETE,
            "A range CFI needs three comma-separated parts (parent,start,end), got ${parts.size}",
            mapOf("cfi" to cfi, "parts" to parts.size),
        )
    }
    // Upstream destructures `[parent, start, end]` and ignores any extra parts.
    return Cfi.Range(parent = parts[0], start = parts[1], end = parts[2])
}

/**
 * [parse] that returns `null` instead of throwing. The reader layer uses this to
 * degrade gracefully when a stored annotation carries a malformed CFI.
 */
fun parseOrNull(cfi: String): Cfi? =
    try {
        parse(cfi)
    } catch (e: CfiException) {
        null
    }

