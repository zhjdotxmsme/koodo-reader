package com.koodoreader.engine.cfi

/**
 * CFI serializer — port of foliate-js `partToString` / `toInnerString` /
 * `toString` (`epubcfi.js`, MIT).
 *
 * Upstream:
 *
 * ```
 * const partToString = ({ index, id, offset, temporal, spatial, text, side }) => {
 *     const param = side ? `;s=${side}` : ''
 *     return `/${index}`
 *         + (id ? `[${escapeCFI(id)}${param}]` : '')
 *         + (offset != null && index % 2 ? `:${offset}` : '')
 *         + (temporal ? `~${temporal}` : '')
 *         + (spatial ? `@${spatial.join(':')}` : '')
 *         + (text || (!id && side) ? '[' + (text?.map(escapeCFI)?.join(',') ?? '') + param + ']' : '')
 * }
 * ```
 *
 * Truthiness compatibility — every `?:`-style test below mirrors JS
 * truthiness, NOT nullability:
 *
 * | field | JS test | consequence |
 * |---|---|---|
 * | `offset` | `!= null && index % 2` | `offset = 0` **is** serialized (on odd steps) |
 * | `temporal` | truthy | `temporal = 0.0` **is dropped** |
 * | `spatial` | truthy | `[]` is truthy → a bare `@` would be emitted |
 * | `text` | truthy | `[]` is truthy → empty `[]` brackets are emitted |
 * | `id` / `side` | truthy | empty strings are dropped |
 *
 * This is exactly why [CfiStep.spatial] / [CfiStep.text] are nullable: `null`
 * means "absent" (JS `undefined`), while an empty list means "present but
 * empty" (JS `[]`). Collapsing the two would change serialized output.
 */

/** `~1.0` must render as `~1` (JS number → string drops the `.0`). */
private fun jsNumberToString(value: Double): String =
    if (value.isFinite() && value % 1.0 == 0.0) value.toLong().toString() else value.toString()

private fun String?.jsTruthy(): Boolean = this != null && this.isNotEmpty()

/** Serialize one step. Port of upstream `partToString`. */
fun CfiStep.toCfiString(): String {
    val param = if (side.jsTruthy()) ";s=$side" else ""
    val builder = StringBuilder("/").append(index)

    if (id.jsTruthy()) {
        builder.append("[").append(escapeCfi(id!!)).append(param).append("]")
    }
    if (offset != null && index % 2 != 0) {
        builder.append(":").append(offset)
    }
    if (temporal != null && temporal != 0.0) {
        builder.append("~").append(jsNumberToString(temporal))
    }
    if (spatial != null) {
        builder.append("@").append(spatial.joinToString(":") { jsNumberToString(it) })
    }
    if (text != null || (!id.jsTruthy() && side.jsTruthy())) {
        builder.append("[").append(text?.joinToString(",") { escapeCfi(it) } ?: "").append(param)
            .append("]")
    }

    return builder.toString()
}

/** Serialize a document chain. Port of upstream `toInnerString` for points. */
internal fun CfiDocuments.toCfiInnerString(): String =
    joinToString("!") { path -> path.joinToString("") { it.toCfiString() } }

/** Serialize without the `epubcfi(...)` wrapper. Port of upstream `toInnerString`. */
internal fun Cfi.toCfiInnerString(): String = when (this) {
    is Cfi.Point -> documents.toCfiInnerString()
    is Cfi.Range -> listOf(parent, start, end).joinToString(",") { it.toCfiInnerString() }
}

/** Serialize with the wrapper. Port of upstream `toString` (which always wraps). */
fun Cfi.toCfiString(): String = wrapCfi(toCfiInnerString())

/**
 * Canonicalize a CFI string: parse then re-serialize.
 *
 * Equivalent to upstream `toString(parse(cfi))`. Useful for de-duplicating
 * annotations that were written by different clients, and for asserting
 * round-trip stability.
 */
fun canonicalize(cfi: String): String = parse(cfi).toCfiString()

/** True when [cfi] is a range (three comma-separated parts). */
fun isRange(cfi: String): Boolean = parse(cfi) is Cfi.Range

/** True when [cfi] parses and is a point. Never throws. */
fun isPoint(cfi: String): Boolean = parseOrNull(cfi) is Cfi.Point
