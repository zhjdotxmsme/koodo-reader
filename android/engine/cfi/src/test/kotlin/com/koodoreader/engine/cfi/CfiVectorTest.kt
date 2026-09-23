package com.koodoreader.engine.cfi

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Golden-vector parity test: the Kotlin port must reproduce, byte for byte, what
 * the UPSTREAM web engine produces (`scripts/gen-cfi-golden.js` runs the real
 * `epubcfi.js` and writes `src/test/resources/cfi-golden.tsv`).
 *
 * The test body is a thin wrapper: all logic lives in [CfiGoldenVectors] so the
 * exact same suite can also run framework-free through [CfiSelfCheck]
 * (`gradle :engine:cfi:selfCheck`).
 */
class CfiVectorTest {

    @Test
    fun `golden vectors match the upstream implementation`() {
        val result = CfiGoldenVectors.runFromDisk()
        assertTrue(result.total > 0, "no golden vectors were loaded")
        assertTrue(result.ok, "CFI port diverged from upstream:\n${result.report()}")
    }

    @Test
    fun `golden vector file covers every operation`() {
        // Guards against a truncated/empty regeneration: if the generator or the
        // vector table regresses, this fails before the parity test can silently
        // pass with a tiny file.
        val text = CfiGoldenVectors.loadText()
            ?: error("cfi-golden.tsv not found; run `node scripts/gen-cfi-golden.js`")
        val vectors = CfiGoldenVectors.parseVectors(text)
        val ops = vectors.map { it.op }.toSet()
        val expectedOps = setOf(
            "buildRange",
            "canonicalPoint",
            "collapse",
            "collapseEnd",
            "compare",
            "concatArrays",
            "escapeCfi",
            "fakeFromIndex",
            "fakeToIndex",
            "fromCalibreHighlight",
            "fromCalibrePos",
            "isCfi",
            "joinIndir",
            "tostring",
            "unwrapCfi",
            "wrapCfi",
        )
        assertTrue(
            ops.containsAll(expectedOps),
            "golden vectors are missing ops: ${expectedOps - ops}",
        )
        assertTrue(vectors.size >= 70, "expected >= 70 golden vectors, got ${vectors.size}")
    }
}
