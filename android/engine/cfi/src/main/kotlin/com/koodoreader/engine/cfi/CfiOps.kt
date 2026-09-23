package com.koodoreader.engine.cfi

/**
 * CFI operations — port of foliate-js `collapse` / `buildRange` / `compare` /
 * `fake` / `fromCalibrePos` / `fromCalibreHighlight` (`epubcfi.js`, MIT).
 *
 * These are the primitives the reader layer needs for reading-position and
 * annotation handling:
 *  - [collapse] turns a range into its start/end point (used by every "jump to"
 *    path and by progress calculation);
 *  - [buildRange] creates the range CFI written to the database when the user
 *    highlights text;
 *  - [compare] provides the total order used for highlight sorting and for
 *    "is this annotation before that one" checks.
 */

/**
 * `concatArrays(a, b)` — glue two document chains by merging the last step of
 * [a] with the first step of [b].
 *
 * Upstream: `a.slice(0, -1).concat([a[a.length - 1].concat(b[0])]).concat(b.slice(1))`
 */
internal fun concatPath(a: CfiDocuments, b: CfiDocuments): CfiDocuments {
    if (a.isEmpty() || b.isEmpty()) {
        throw CfiException(
            CfiErrorCode.EMPTY_PATH,
            "Cannot concatenate an empty CFI path",
            mapOf("a" to a.toString(), "b" to b.toString()),
        )
    }
    return a.dropLast(1) + listOf(a.last() + b.first()) + b.drop(1)
}

/**
 * Upstream `!offset` test used by [buildRange]: a **falsy** offset (`undefined`
 * or `0`) means "this step is still part of the shared parent prefix".
 */
private fun isFalsyOffset(offset: Int?): Boolean = offset == null || offset == 0

/**
 * Collapse a range CFI to a point. Port of upstream `collapse`.
 *
 * @param toEnd `true` → collapse to the range end, `false` → to the range start
 */
fun Cfi.collapse(toEnd: Boolean = false): Cfi.Point = when (this) {
    is Cfi.Point -> this
    is Cfi.Range -> Cfi.Point(concatPath(parent, if (toEnd) end else start))
}

/** String overload of [collapse] (always re-serialized, like upstream). */
fun collapse(cfi: String, toEnd: Boolean = false): String =
    parse(cfi).collapse(toEnd).toCfiString()

/**
 * Build a range CFI from two point CFIs. Port of upstream `buildRange`.
 *
 * Steps that are identical in both points (same index, no offset) are folded
 * into the `parent` part; the first difference splits `start` from `end`. This
 * is exactly the shape stored by the web app, so the output must stay
 * byte-identical for cross-platform annotations.
 *
 * Note: upstream only supports **local** paths here (a range must stay inside a
 * single document); the non-local prefix is copied from `from` verbatim.
 *
 * @param from range start
 * @param to range end
 */
fun buildRange(from: String, to: String): String {
    val fromPoint = parse(from).collapse()
    val toPoint = parse(to).collapse(toEnd = true)

    val localFrom = fromPoint.documents.last()
    val localTo = toPoint.documents.last()

    val localParent = mutableListOf<CfiStep>()
    val localStart = mutableListOf<CfiStep>()
    val localEnd = mutableListOf<CfiStep>()
    var pushToParent = true

    val len = maxOf(localFrom.size, localTo.size)
    for (i in 0 until len) {
        val a = localFrom.getOrNull(i)
        val b = localTo.getOrNull(i)

        // `pushToParent &&= a?.index === b?.index && !a?.offset && !b?.offset`
        // (once false it stays false, and a missing step only matches a missing
        // step — which is why the `a != null` guard below is unreachable-faithful
        // rather than a behaviour change).
        pushToParent = pushToParent &&
            a != null &&
            b != null &&
            a.index == b.index &&
            isFalsyOffset(a.offset) &&
            isFalsyOffset(b.offset)

        if (pushToParent) {
            if (a != null) localParent += a
        } else {
            if (a != null) localStart += a
            if (b != null) localEnd += b
        }
    }

    val parent = fromPoint.documents.dropLast(1) + listOf(localParent.toList())
    return Cfi.Range(
        parent = parent,
        start = listOf(localStart.toList()),
        end = listOf(localEnd.toList()),
    ).toCfiString()
}

/**
 * Compare two CFIs. Port of upstream `compare`.
 *
 * @return `-1` when [a] sorts before [b], `1` when after, `0` when equal
 *
 * Upstream semantics reproduced on purpose:
 *  - a range compares as (start, then end): `compare(a.start, b.start) ||
 *    compare(a.end, b.end)` — i.e. the start decides unless both starts are
 *    equal;
 *  - a shorter path sorts first;
 *  - **offsets**: JS evaluates `10 > undefined` as `false`, so a step **with**
 *    an offset and one **without** compare equal. Reproduced (bug-for-bug) so
 *    both platforms order annotations identically;
 *  - temporal/spatial offsets are not compared (upstream has a `TODO` there).
 */
fun compare(a: String, b: String): Int = compare(parse(a), parse(b))

/** [compare] on already-parsed values. */
fun compare(a: Cfi, b: Cfi): Int {
    if (a is Cfi.Range || b is Cfi.Range) {
        val byStart = compare(a.collapse(), b.collapse())
        if (byStart != 0) return byStart
        return compare(a.collapse(toEnd = true), b.collapse(toEnd = true))
    }

    val documentsA = (a as Cfi.Point).documents
    val documentsB = (b as Cfi.Point).documents

    for (i in 0 until maxOf(documentsA.size, documentsB.size)) {
        val p = documentsA.getOrNull(i) ?: emptyList()
        val q = documentsB.getOrNull(i) ?: emptyList()
        val maxIndex = maxOf(p.size, q.size) - 1

        for (j in 0..maxIndex) {
            val x = p.getOrNull(j)
            val y = q.getOrNull(j)
            if (x == null) return -1
            if (y == null) return 1
            if (x.index > y.index) return 1
            if (x.index < y.index) return -1

            if (j == maxIndex) {
                val offsetX = x.offset
                val offsetY = y.offset
                // Only compared when BOTH are present (JS truthiness, see KDoc).
                if (offsetX != null && offsetY != null) {
                    if (offsetX > offsetY) return 1
                    if (offsetX < offsetY) return -1
                }
            }
        }
    }

    return 0
}

/**
 * Port of upstream `fake` — indices ↔ CFIs for books without a real package
 * document (used by the Calibre importers below, and by the `TXT`/`MD`
 * renderers, which have no spine).
 */
object CfiFake {
    /** `fake.fromIndex(n)` → `epubcfi(/6/<(n + 1) * 2>)`. */
    fun fromIndex(index: Int): String = wrapCfi("/6/${(index + 1) * 2}")

    /** `fake.toIndex(parts)` → `parts.at(-1).index / 2 - 1` (integer division). */
    fun toIndex(path: CfiPath): Int {
        val last = path.lastOrNull()
            ?: throw CfiException(
                CfiErrorCode.EMPTY_PATH,
                "CfiFake.toIndex needs a non-empty path (upstream throws on undefined.index)",
            )
        return last.index / 2 - 1
    }
}

/**
 * Convert a Calibre reading position into a Koodo CFI. Port of upstream
 * `fromCalibrePos`.
 *
 * Upstream shifts off the first two steps (package document wrapper) and
 * rebuilds `/(6)/<step>` as the first document.
 */
fun fromCalibrePos(pos: String): String {
    val documents = when (val parsed = parse(pos)) {
        is Cfi.Point -> parsed.documents
        is Cfi.Range -> parsed.parent
    }
    // Upstream shifts the first step into `item`, shifts the (now) first step
    // away, and `.shift()` on a shorter array is a no-op — so a one-step
    // document is valid input and yields `epubcfi(/6/<index>!)`.
    val first = documents.firstOrNull()?.takeIf { it.isNotEmpty() }
        ?: throw CfiException(
            CfiErrorCode.EMPTY_PATH,
            "fromCalibrePos needs a non-empty first document",
            mapOf("pos" to pos),
        )

    val item = first.first()
    val rest = first.drop(2)
    return Cfi.Point(listOf(listOf(CfiStep(index = 6), item), rest)).toCfiString()
}

/**
 * Convert a Calibre highlight into a Koodo range CFI. Port of upstream
 * `fromCalibreHighlight`.
 *
 * NOTE (faithful port): upstream builds `fake.fromIndex(spineIndex) + '!'` while
 * the result is still `epubcfi(...)`-wrapped and then appends the raw CFI body,
 * producing e.g. `epubcfi(/6/2)!/4/...`. The tokenizer ignores the stray
 * `epubcfi(` / `)` characters, so the parsed result is the intended
 * `[[/6,/2], [/4, ...]]`. Kept as-is to stay byte-compatible with the web app.
 */
fun fromCalibreHighlight(spineIndex: Int, startCfi: String, endCfi: String): String {
    val prefix = CfiFake.fromIndex(spineIndex) + "!"
    return buildRange(prefix + startCfi.drop(2), prefix + endCfi.drop(2))
}

