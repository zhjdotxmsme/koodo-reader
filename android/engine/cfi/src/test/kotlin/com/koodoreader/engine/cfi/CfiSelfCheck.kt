package com.koodoreader.engine.cfi

import kotlin.system.exitProcess

/**
 * Framework-free verification runner for the native CFI core.
 *
 * Runs BOTH suites — the upstream-generated golden vectors ([CfiGoldenVectors])
 * and the model/deviation invariants ([CfiInvariantChecks]) — and exits non-zero
 * on any failure.
 *
 * WHY a plain `main()` next to JUnit: the port must be verifiable with nothing
 * but a Kotlin compiler (offline review, air-gapped machines, a fast CI smoke
 * step before Gradle). It also keeps the assertions in one place: the JUnit
 * classes are thin wrappers around exactly this code.
 *
 * Usage:
 *   java -cp <classes>[:<resources>] com.koodoreader.engine.cfi.CfiSelfCheckKt [path/to/cfi-golden.tsv]
 *
 * Exit codes: 0 all good, 1 assertions failed, 2 harness problem (missing file).
 */
fun main(args: Array<String>) {
    val explicitPath = args.firstOrNull()

    println("Koodo Reader - native CFI self-check")
    println("  vectors: ${explicitPath ?: "(auto-detect: repo-relative path, then classpath)"}")

    val vectors = try {
        val text = CfiGoldenVectors.loadText(explicitPath)
        if (text == null) {
            println("FAIL: cfi-golden.tsv not found; run `node scripts/gen-cfi-golden.js`")
            exitProcess(2)
        }
        CfiGoldenVectors.parseVectors(text)
    } catch (e: Throwable) {
        println("FAIL: could not load/parse golden vectors: ${e.message}")
        exitProcess(2)
    }

    val vectorResult = CfiGoldenVectors.run(vectors)
    println("  golden vectors:   ${vectorResult.passed}/${vectorResult.total} passed")
    if (!vectorResult.ok) {
        println(vectorResult.report())
    }

    val invariantFailures = CfiInvariantChecks.run()
    val invariantTotal = CfiInvariantChecks.size()
    println("  invariant checks: ${invariantTotal - invariantFailures.size}/$invariantTotal passed")
    invariantFailures.forEach { println("    FAIL $it") }

    if (vectorResult.ok && invariantFailures.isEmpty()) {
        println("OK")
        exitProcess(0)
    }

    println("FAILED")
    exitProcess(1)
}
