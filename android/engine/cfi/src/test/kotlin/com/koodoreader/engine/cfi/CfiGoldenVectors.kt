package com.koodoreader.engine.cfi

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Golden-vector harness for the native CFI core.
 *
 * The vectors in `src/test/resources/cfi-golden.tsv` are produced by the
 * UPSTREAM implementation (`scripts/gen-cfi-golden.js`), so this harness is a
 * cross-implementation parity check rather than a self-fulfilling test: if the
 * Kotlin port drifts from the web engine, these rows fail with a precise diff.
 *
 * The harness is deliberately framework-free (no JUnit types) so it can also be
 * executed from a plain `main()` on any JVM — see [CfiSelfCheck]. The JUnit
 * wrapper ([CfiVectorTest]) only reports the outcome.
 *
 * TSV format: `op \t arg1 \t arg2 \t arg3 \t expected`, `#` comments allowed.
 */

/** One golden vector row. */
internal data class CfiVector(
    val op: String,
    val args: List<String>,
    val expected: String,
    val lineNumber: Int,
)

/** One failing vector: either a value mismatch or a thrown exception. */
internal data class CfiVectorFailure(
    val vector: CfiVector,
    val actual: String?,
    val error: String?,
) {
    /** Human-readable, single-block report (used by both runners). */
    fun describe(): String = buildString {
        append("line ").append(vector.lineNumber).append(": ").append(vector.op)
        if (vector.args.any { it.isNotEmpty() }) {
            append("(").append(vector.args.filter { it.isNotEmpty() }.joinToString(", ")).append(")")
        }
        append('\n')
        append("    expected: ").append(vector.expected).append('\n')
        if (error != null) {
            append("    threw:    ").append(error).append('\n')
        } else {
            append("    actual:   ").append(actual).append('\n')
        }
    }
}

/** Outcome of running the whole vector file. */
internal data class CfiVectorResult(
    val total: Int,
    val failures: List<CfiVectorFailure>,
) {
    val passed: Int get() = total - failures.size

    val ok: Boolean get() = failures.isEmpty()

    /** Multi-line summary; empty when everything passed. */
    fun report(): String =
        if (ok) {
            ""
        } else {
            failures.joinToString(separator = "\n") { it.describe() } +
                "\n${failures.size} of $total golden vectors FAILED " +
                "(the Kotlin port diverged from the upstream engine)."
        }
}

/**
 * Loads and executes the golden vectors. Pure apart from file/classpath reads.
 */
internal object CfiGoldenVectors {

    /** Resource name inside `src/test/resources`. */
    const val RESOURCE_NAME = "cfi-golden.tsv"

    /**
     * Candidate locations for the vector file when running outside Gradle (no
     * test resources on the classpath), relative to the repo root.
     */
    private val FILE_CANDIDATES = listOf(
        "android/engine/cfi/src/test/resources/$RESOURCE_NAME",
        "engine/cfi/src/test/resources/$RESOURCE_NAME",
        "../engine/cfi/src/test/resources/$RESOURCE_NAME",
    )

    /**
     * Read the vector file text.
     *
     * @param explicitPath optional override (CLI argument / test setup)
     * @return the file content, or `null` when it cannot be found anywhere
     */
    fun loadText(explicitPath: String? = null): String? {
        if (!explicitPath.isNullOrBlank()) {
            val file = File(explicitPath)
            if (file.isFile) return file.readText()
        }
        for (candidate in FILE_CANDIDATES) {
            val path = Paths.get(candidate)
            if (Files.isRegularFile(path)) return String(Files.readAllBytes(path))
        }
        val stream = CfiGoldenVectors::class.java.getResourceAsStream("/$RESOURCE_NAME")
        return stream?.bufferedReader()?.use { it.readText() }
    }

    /**
     * Parse TSV text into vectors.
     *
     * NOTE the name: `parse` would shadow the top-level CFI `parse()` inside this
     * object (Kotlin resolves members before top-level functions), which silently
     * broke the CFI ops before. Kept explicit on purpose.
     *
     * @throws IllegalStateException when a data row does not have 5 columns
     *   (fail loudly: a silently mis-parsed oracle is worse than no oracle)
     */
    fun parseVectors(text: String): List<CfiVector> {
        val vectors = mutableListOf<CfiVector>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val columns = line.split('\t')
            check(columns.size == 5) {
                "golden vector line ${index + 1}: expected 5 tab-separated columns, got ${columns.size}"
            }
            vectors += CfiVector(
                op = columns[0],
                args = listOf(columns[1], columns[2], columns[3]),
                expected = columns[4],
                lineNumber = index + 1,
            )
        }
        return vectors
    }

    /**
     * The Kotlin-side implementation of every op in the vector table. Each op
     * takes the three argument columns and returns the string that must equal
     * the upstream-generated `expected` column.
     */
    private val OPS: Map<String, (String, String, String) -> String> = mapOf(
        // `canonicalPoint` and `tostring` are the same assertion in Kotlin: the
        // upstream oracle pins them with two different code paths (public
        // `collapse` vs private `toString`), which cross-checks the harness too.
        "canonicalPoint" to { a, _, _ -> canonicalize(a) },
        "tostring" to { a, _, _ -> canonicalize(a) },
        "collapse" to { a, _, _ -> collapse(a) },
        "collapseEnd" to { a, _, _ -> collapse(a, toEnd = true) },
        "buildRange" to { a, b, _ -> buildRange(a, b) },
        "compare" to { a, b, _ -> compare(a, b).toString() },
        "isCfi" to { a, _, _ -> isCfi(a).toString() },
        "joinIndir" to { a, b, _ -> joinIndir(a, b) },
        "escapeCfi" to { a, _, _ -> escapeCfi(a) },
        "unwrapCfi" to { a, _, _ -> unwrapCfi(a) },
        "wrapCfi" to { a, _, _ -> wrapCfi(a) },
        "fakeFromIndex" to { a, _, _ -> CfiFake.fromIndex(a.trim().toInt()) },
        "fakeToIndex" to { a, _, _ -> CfiFake.toIndex(pointPath(a)).toString() },
        "fromCalibrePos" to { a, _, _ -> fromCalibrePos(a) },
        "fromCalibreHighlight" to { a, b, c -> fromCalibreHighlight(a.trim().toInt(), b, c) },
        "concatArrays" to { a, b, _ -> concatProjection(a, b) },
    )

    /**
     * First document of a point CFI — what upstream `fake.toIndex(parts)`
     * receives (`parts = parse(value)[0]`).
     */
    private fun pointPath(cfi: String): CfiPath =
        pointDocuments(cfi).firstOrNull()
            ?: throw CfiException(CfiErrorCode.EMPTY_PATH, "expected at least one document: $cfi")

    /** `parse(x).documents` with a typed error for non-point input. */
    private fun pointDocuments(cfi: String): CfiDocuments =
        (parse(cfi) as? Cfi.Point)?.documents
            ?: throw CfiException(CfiErrorCode.EMPTY_PATH, "expected a point CFI, got a range: $cfi")

    /**
     * Mirrors the JS shim's projection of `concatArrays` results
     * (`/index[id]` per step, documents joined by `!`) so the vector compares
     * structure rather than a serialization.
     */
    private fun concatProjection(a: String, b: String): String =
        concatPath(pointDocuments(a), pointDocuments(b)).joinToString("!") { document ->
            document.joinToString("") { step ->
                "/${step.index}" + (step.id?.let { "[$it]" } ?: "")
            }
        }

    /**
     * Run [vectors] against the Kotlin port.
     *
     * Exceptions are captured as failures instead of aborting the run: one
     * malformed vector should not hide the state of the other 73.
     */
    fun run(vectors: List<CfiVector>): CfiVectorResult {
        val failures = mutableListOf<CfiVectorFailure>()
        for (vector in vectors) {
            val op = OPS[vector.op]
            if (op == null) {
                failures += CfiVectorFailure(vector, actual = null, error = "unknown op \"${vector.op}\"")
                continue
            }
            try {
                val actual = op(vector.args[0], vector.args[1], vector.args[2])
                if (actual != vector.expected) {
                    failures += CfiVectorFailure(vector, actual = actual, error = null)
                }
            } catch (e: Throwable) {
                failures += CfiVectorFailure(vector, actual = null, error = e.toString())
            }
        }
        return CfiVectorResult(total = vectors.size, failures = failures)
    }

    /** [loadText] + [parseVectors] + [run] in one call. */
    fun runFromDisk(explicitPath: String? = null): CfiVectorResult {
        val text = loadText(explicitPath) ?: error(
            "golden vectors not found (looked in $FILE_CANDIDATES and on the classpath); " +
                "run `node scripts/gen-cfi-golden.js` first"
        )
        return run(parseVectors(text))
    }
}
