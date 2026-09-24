package com.koodoreader.feature.dictionary.mdx

/**
 * `utils.js:58-85` (`levenshteinDistance`) — edit distance used by the MDX
 * `suggest` / `fuzzy_search` helpers.
 *
 * Kept quirk: the JS version returns the sentinel `9999` for a falsy operand, which
 * includes the *empty string*, not just `null`/`undefined`. Callers rely on that to
 * push empty keys out of the suggestion list (`mdx.js:87`).
 */
object Levenshtein {

    const val SENTINEL = 9999

    fun distance(a: String?, b: String?): Int {
        if (a.isNullOrEmpty()) return SENTINEL
        if (b.isNullOrEmpty()) return SENTINEL
        val m = a.length
        val n = b.length
        // Two rolling rows instead of the JS port's (m+1)x(n+1) matrix: same result,
        // no allocation per keyword during a fuzzy search over a whole block.
        var previous = IntArray(n + 1) { it }
        var current = IntArray(n + 1)
        for (i in 1..m) {
            current[0] = i
            for (j in 1..n) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[n]
    }
}
