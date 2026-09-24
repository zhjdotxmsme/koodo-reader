package com.koodoreader.engine.annotate

import com.koodoreader.engine.cfi.Cfi
import com.koodoreader.engine.cfi.buildRange
import com.koodoreader.engine.cfi.canonicalize
import com.koodoreader.engine.cfi.collapse
import com.koodoreader.engine.cfi.compare as compareCfi
import com.koodoreader.engine.cfi.parseOrNull

/**
 * The single gateway through which every annotation position passes.
 *
 * HARD RULE (ADR-002): this module NEVER constructs an address itself.
 * Every position string is produced, parsed, normalized and compared by the
 * golden-vector-tested `:engine:cfi` core. [CfiAnchor] only wraps that API
 * in the small set of operations the annotation layer needs, so no other
 * file in this package imports `com.koodoreader.engine.cfi` directly.
 *
 * Keeping one gateway means a change in how positions are validated touches
 * exactly one place, and it makes the "CFI 复用现有 engine/cfi（黄金向量
 * 防漂移）" requirement auditable.
 */
object CfiAnchor {

    /** True when [value] parses as any CFI (point or range). Never throws. */
    fun isValid(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return parseOrNull(value) != null
    }

    /** True when [value] parses as a point CFI. Never throws. */
    fun isPoint(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return parseOrNull(value) is Cfi.Point
    }

    /** True when [value] parses as a range CFI. Never throws. */
    fun isRange(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return parseOrNull(value) is Cfi.Range
    }

    /**
     * Parse and re-serialize [value] into canonical form.
     *
     * @throws IllegalArgumentException if [value] is not a valid CFI (the
     *   underlying error is already an [IllegalArgumentException] subtype)
     */
    fun requireValid(value: String): String = canonicalize(value)

    /**
     * Like [requireValid] but additionally requires a POINT (a bookmark
     * anchor cannot be a range).
     */
    fun requirePoint(value: String): String {
        val canonical = canonicalize(value)
        require(parseOrNull(canonical) is Cfi.Point) { "expected a point CFI for a bookmark, got a range: $value" }
        return canonical
    }

    /**
     * Build the canonical range CFI for a highlight/note from two point
     * CFIs. Delegates to the CFI core's `buildRange`, which folds the shared
     * parent prefix exactly like the web engine — the output is what gets
     * written to the desktop `notes.cfi` column.
     */
    fun rangeCfi(start: String, end: String): String = buildRange(start, end)

    /** Collapse a (possibly range) CFI to its start point. */
    fun startPoint(cfi: String): String = collapse(cfi, toEnd = false)

    /** Collapse a (possibly range) CFI to its end point. */
    fun endPoint(cfi: String): String = collapse(cfi, toEnd = true)

    /**
     * Total order over point CFIs: `-1` if [a] sorts before [b], `1` after,
     * `0` equal. Delegates to the CFI core's `compare` (including its
     * bug-for-bug offset semantics), so annotation ordering is identical on
     * desktop and Android.
     */
    fun compare(a: String, b: String): Int = compareCfi(a, b)

    /**
     * Interval-overlap test over point CFIs. Two closed intervals overlap
     * unless one lies entirely before the other; touching endpoints count as
     * overlap (a zero-length point at a boundary is shared).
     */
    fun overlaps(
        startA: String,
        endA: String,
        startB: String,
        endB: String,
    ): Boolean {
        val aEndVsBStart = compare(endA, startB)
        val bEndVsAStart = compare(endB, startA)
        return !(aEndVsBStart < 0 || bEndVsAStart < 0)
    }
}
