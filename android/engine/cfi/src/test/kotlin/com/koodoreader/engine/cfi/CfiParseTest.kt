package com.koodoreader.engine.cfi

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Model / deviation / ordering invariants (see [CfiInvariantChecks] for the
 * individual cases and their rationale).
 *
 * Golden vectors pin *serialized output*; these checks pin the parsed DATA MODEL,
 * the deliberate deviations from upstream, and algebraic properties that no
 * string oracle can express (sort totality, idempotence).
 */
class CfiParseTest {

    @Test
    fun `model deviation and ordering invariants hold`() {
        val failures = CfiInvariantChecks.run()
        assertTrue(
            failures.isEmpty(),
            "CFI invariants failed:\n" + failures.joinToString("\n") { "  - $it" },
        )
    }

    @Test
    fun `every registered invariant is actually executed`() {
        // Guards against a cases-builder accidentally returning an empty list,
        // which would make the test above vacuously pass.
        assertTrue(CfiInvariantChecks.size() >= 20, "expected >= 20 invariant checks, got ${CfiInvariantChecks.size()}")
    }
}
