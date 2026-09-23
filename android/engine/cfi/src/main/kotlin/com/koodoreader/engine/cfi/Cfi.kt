package com.koodoreader.engine.cfi

/**
 * EPUB CFI core — data model and string primitives.
 *
 * NATIVE PORT. This is a faithful, bug-for-bug Kotlin translation of the CFI
 * implementation used by the web engine, so that annotation / reading-position
 * data stays byte-compatible between the desktop web app and the native Android
 * app:
 *
 *  - `koodo-reader/kookit` → `src/libs/cfi.ts` (AGPL-3.0)
 *  - `johnfactotum/foliate-js` → `epubcfi.js` (MIT) — the direct blueprint of
 *    this module (tokenizer → parser → serializer → ops).
 *
 * Only the DOM-independent half is ported here. `nodeToParts` / `partsToNode` /
 * `indexChildNodes` / `fromRange` / `toRange` need a DOM and are therefore left
 * to the reader layer (`engine/epub`, P2), which will operate on the native
 * document model instead.
 *
 * Behavioural notes that MUST stay aligned with upstream (see the individual
 * KDoc comments for the exact JS expression being mirrored):
 *  - a `null` list field means "absent" (JS `undefined`), an empty list means
 *    "present but empty" (JS `[]`, which is truthy) — this distinction changes
 *    the serialized output, so the fields are nullable on purpose;
 *  - `temporal == 0.0` is dropped when serializing (JS truthiness),
 *    while `offset == 0` is kept;
 *  - comparing a present `offset` with an absent one yields 0 ("equal").
 */

/** Stable, machine-readable error codes (mirrors the repo's error-code style). */
enum class CfiErrorCode(val code: String) {
    /** A step's index is not a valid positive integer. */
    INVALID_INDEX("CFI_INVALID_INDEX"),

    /** An offset (`:n`) is not a valid non-negative integer. */
    INVALID_OFFSET("CFI_INVALID_OFFSET"),

    /** A temporal offset (`~n`) is not a valid number. */
    INVALID_TEMPORAL("CFI_INVALID_TEMPORAL"),

    /** A spatial offset (`@x:y`) is not a valid number. */
    INVALID_SPATIAL("CFI_INVALID_SPATIAL"),

    /** An assertion was opened with `[` but never closed with `]`. */
    UNBALANCED_ASSERTION("CFI_UNBALANCED_ASSERTION"),

    /** The input was empty or contained no steps at all. */
    EMPTY_PATH("CFI_EMPTY_PATH"),

    /** A range CFI with fewer than three comma-separated parts. */
    RANGE_INCOMPLETE("CFI_RANGE_INCOMPLETE"),
}

/**
 * Typed error for every CFI failure. Mirrors the JS `Error` behaviour but with a
 * stable `code` so callers can branch on the failure kind instead of parsing
 * messages (same convention as `FolderBridgeError` / `AndroidBuildError`).
 */
class CfiException(
    val code: CfiErrorCode,
    message: String,
    val detail: Any? = null,
) : IllegalArgumentException("[${code.code}] $message")

/**
 * One CFI step: `/index[id]` plus its optional offsets and assertions.
 *
 * Field-by-field mirror of the object produced by foliate-js' `parser()`.
 *
 * @property index the step index (`/n`), always required
 * @property id the ID assertion (`[id]`) taken from the FIRST assertion that
 *   directly follows a step
 * @property offset character offset (`:n`); only serialized for odd `index`
 * @property temporal temporal offset (`~n`); only serialized when non-zero
 * @property spatial spatial offsets (`@x:y`); `null` = absent (JS `undefined`)
 * @property text text assertions (`[pre,post]`); `null` = absent (JS `undefined`)
 * @property side the `;s=` side assertion (e.g. `b` / `a`)
 */
data class CfiStep(
    val index: Int,
    val id: String? = null,
    val offset: Int? = null,
    val temporal: Double? = null,
    val spatial: List<Double>? = null,
    val text: List<String>? = null,
    val side: String? = null,
)

/** A single document's steps. Documents are separated by `!` in a CFI string. */
typealias CfiPath = List<CfiStep>

/** The documents of a CFI, in order (split on `!`). */
typealias CfiDocuments = List<CfiPath>

/**
 * A parsed CFI: either a point inside one document chain, or a range with a
 * shared `parent` plus `start` / `end` point paths.
 *
 * Mirror of foliate-js' two shapes: an array of documents (point) or
 * `{ parent, start, end }` (range).
 */
sealed class Cfi {
    /** A point CFI: `/a/b!c/d`. */
    data class Point(val documents: CfiDocuments) : Cfi()

    /**
     * A range CFI: `parent,start,end`.
     *
     * `parent` and `start`/`end` are themselves document chains so the shape
     * matches upstream `collapse()` / `buildRange()` exactly.
     */
    data class Range(
        val parent: CfiDocuments,
        val start: CfiDocuments,
        val end: CfiDocuments,
    ) : Cfi()
}

/** Characters escaped by `escapeCFI` upstream (`/[^[\])(,;=]/g`). */
private val CFI_ESCAPE_REGEX = Regex("[\\^\\[\\])(,;=]")

/** Upstream `isCFI = /^epubcfi\((.*)\)$/`. */
private val CFI_WRAPPER_REGEX = Regex("^epubcfi\\((.*)\\)$")

/** Escape the characters that carry structural meaning inside a CFI. */
fun escapeCfi(value: String): String = CFI_ESCAPE_REGEX.replace(value) { "^" + it.value }

/** True when [value] is wrapped in `epubcfi(...)`. */
fun isCfi(value: String): Boolean = CFI_WRAPPER_REGEX.containsMatchIn(value)

/** Upstream `wrap`: add the `epubcfi(...)` wrapper unless it is already there. */
fun wrapCfi(value: String): String = if (isCfi(value)) value else "epubcfi($value)"

/** Upstream `unwrap`: strip the `epubcfi(...)` wrapper when present. */
fun unwrapCfi(value: String): String = CFI_WRAPPER_REGEX.find(value)?.groupValues?.get(1) ?: value

/** Upstream `joinIndir`: join two or more CFIs into one with `!` indirection. */
fun joinIndir(vararg values: String): String =
    "epubcfi(" + values.joinToString("!") { unwrapCfi(it) } + ")"
