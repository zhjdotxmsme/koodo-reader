package com.koodoreader.engine.cfi

/**
 * Invariant checks that golden vectors cannot express: the parsed DATA MODEL
 * (field-level assertions), the documented deviations from upstream, and
 * algebraic properties (order totality, idempotent canonicalization).
 *
 * Framework-free on purpose: [CfiParseTest] reports these through JUnit, and
 * [CfiSelfCheck] runs the exact same code from a plain `main()`, so the suite can
 * be verified on any JVM without a test framework.
 */
internal object CfiInvariantChecks {

    /** One named check. */
    internal class Case(val name: String, val body: () -> Unit)

    /** Run every check; returns human-readable failures (empty = all good). */
    fun run(): List<String> {
        val failures = mutableListOf<String>()
        for (case in all()) {
            try {
                case.body()
            } catch (e: Throwable) {
                failures += "${case.name}: ${e.message ?: e.toString()}"
            }
        }
        return failures
    }

    /** Number of registered checks (used for reporting). */
    fun size(): Int = all().size

    private fun all(): List<Case> = modelCases() + deviationCases() + orderingCases()

    private fun modelCases(): List<Case> = listOf(
        Case("parse: multi-document point") {
            val documents = (parse("/6/4[chap01ref]!/4[body01]/10[para05]/3:10") as Cfi.Point).documents
            expectEquals(2, documents.size, "document count")
            expectEquals(
                listOf(CfiStep(index = 6), CfiStep(index = 4, id = "chap01ref")),
                documents[0],
                "document 0",
            )
            expectEquals(
                listOf(
                    CfiStep(index = 4, id = "body01"),
                    CfiStep(index = 10, id = "para05"),
                    CfiStep(index = 3, offset = 10),
                ),
                documents[1],
                "document 1",
            )
        },

        Case("parse: three documents") {
            val documents = (parse("/6/2!/4/2!/6/4") as Cfi.Point).documents
            expectEquals(3, documents.size, "document count")
            expectEquals(listOf(CfiStep(index = 6), CfiStep(index = 4)), documents[2], "document 2")
        },

        Case("parse: range shape") {
            val range = parse("epubcfi(/6/4[chap01ref]!/4[body01]/10[para05],/2/1:1,/3:4)") as Cfi.Range
            expectEquals(
                listOf(
                    listOf(CfiStep(index = 6), CfiStep(index = 4, id = "chap01ref")),
                    listOf(CfiStep(index = 4, id = "body01"), CfiStep(index = 10, id = "para05")),
                ),
                range.parent,
                "parent",
            )
            expectEquals(
                listOf(listOf(CfiStep(index = 2), CfiStep(index = 1, offset = 1))),
                range.start,
                "start",
            )
            expectEquals(listOf(listOf(CfiStep(index = 3, offset = 4))), range.end, "end")
        },

        Case("assertion quirk: /2[x,y] yields id=x and text=[y]") {
            // Upstream `parser` does not refresh `state` in the text branch, so the
            // chunk after the comma is still treated as an id. Also pinned by a
            // golden vector; asserted here at the model level for clarity.
            val step = (parse("/2[x,y]") as Cfi.Point).documents[0][0]
            expectEquals("x", step.id, "id")
            expectEquals(listOf("y"), step.text, "text")
        },

        Case("assertion: text chunks after an offset") {
            val step = (parse("/6/2!/4/2/2:5[pre,post]") as Cfi.Point).documents[1].last()
            expectEquals(5, step.offset, "offset")
            expectEquals(null, step.id, "id")
            expectEquals(listOf("pre", "post"), step.text, "text")
        },

        Case("assertion: side bias") {
            val step = (parse("/6/2!/4/2/2:5[;s=b]") as Cfi.Point).documents[1].last()
            expectEquals("b", step.side, "side")
            expectEquals(listOf(""), step.text, "text")
        },

        Case("assertion: escaped delimiter round-trips") {
            val step = (parse("/6/2!/4/6/2[ch^,ap]") as Cfi.Point).documents[1].last()
            expectEquals("ch,ap", step.id, "unescaped id")
            expectEquals(
                "epubcfi(/6/2!/4/6/2[ch^,ap])",
                canonicalize("/6/2!/4/6/2[ch^,ap]"),
                "re-escaped",
            )
        },

        Case("offsets: temporal and spatial") {
            val temporal = (parse("/6/2!/4/2~5.5") as Cfi.Point).documents[1].last()
            expectEquals(5.5, temporal.temporal, "temporal")
            val spatial = (parse("/6/2!/4/2@10:20") as Cfi.Point).documents[1].last()
            expectEquals(listOf(10.0, 20.0), spatial.spatial, "spatial")
        },
    )

    /**
     * The documented DEVIATIONS from upstream: malformed numerics raise typed
     * errors instead of silently propagating `NaN`.
     */
    private fun deviationCases(): List<Case> = listOf(
        Case("deviation: empty step index is a typed error, not NaN") {
            expectCfiError(CfiErrorCode.INVALID_INDEX, "double slash") { parse("/6//4") }
        },

        Case("deviation: overflowing index is a typed error") {
            expectCfiError(CfiErrorCode.INVALID_INDEX, "huge index") { parse("/99999999999") }
        },

        Case("deviation: offset before any step is a typed error") {
            expectCfiError(CfiErrorCode.EMPTY_PATH, "leading offset") { parse(":10") }
        },

        Case("deviation: incomplete range is a typed error") {
            expectCfiError(CfiErrorCode.RANGE_INCOMPLETE, "two parts") { parse("epubcfi(/6/2,/4)") }
        },

        Case("parseOrNull swallows malformed input") {
            expectEquals(null, parseOrNull("/6//4"), "parseOrNull")
            expectTrue(parseOrNull("/6/2") is Cfi.Point, "parseOrNull on valid input")
        },

        Case("canonicalize is idempotent") {
            val samples = listOf(
                "/6/4!/4/2/2:0",
                "/6/4!/4/2/3:0",
                "/6/2!/4/6/2[ch^,ap]",
                "/6/2!/4/2~0",
                "/2[x,y]",
                "epubcfi(/6/4[chap01ref]!/4[body01]/10[para05],/2/1:1,/3:4)",
                "epubcfi(/6/4!/4/10/2,/1:0,/1:10)",
            )
            for (sample in samples) {
                val once = canonicalize(sample)
                expectEquals(once, canonicalize(once), "idempotence for $sample")
            }
        },

        Case("upstream quirk: a re-canonicalized text assertion turns into an id") {
            // `/6/2!/4/2/2:5[pre,post]` canonicalizes to `.../2[pre,post]` — the
            // `:5` offset sits on an EVEN step and is therefore not serialized.
            // Feeding that back in makes the FIRST chunk an id (the id rule only
            // requires the previous token to be a step), hence `[pre][post]`.
            // Both forms are pinned against upstream by the `tostring` golden
            // vectors, so this is upstream behaviour, not a port artefact — and it
            // is why `canonicalize()` must never be used to rewrite stored
            // annotations blindly (see the migration doc, risk R2).
            expectEquals(
                "epubcfi(/6/2!/4/2/2[pre,post])",
                canonicalize("/6/2!/4/2/2:5[pre,post]"),
                "first pass",
            )
            expectEquals(
                "epubcfi(/6/2!/4/2/2[pre][post])",
                canonicalize("epubcfi(/6/2!/4/2/2[pre,post])"),
                "second pass",
            )
        },
    )

    /** Algebraic / ordering properties, plus the text helpers. */
    private fun orderingCases(): List<Case> = listOf(
        Case("compare is a total order on a shuffled set") {
            val ascending = listOf(
                "/6/2!/4/2/1:0",
                "/6/2!/4/2/1:5",
                "/6/2!/4/2/2:1",
                "/6/2!/4/2/2/1:1",
                "/6/4!/4/2/1:1",
            )
            val shuffled = listOf(ascending[3], ascending[0], ascending[4], ascending[2], ascending[1])
            expectEquals(ascending, shuffled.sortedWith { a, b -> compare(a, b) }, "sort order")
            for (value in ascending) {
                expectEquals(0, compare(value, value), "reflexivity for $value")
            }
        },

        Case("compare: offset presence compares equal (upstream truthiness)") {
            // Documented upstream behaviour: JS `10 > undefined` is false, so a
            // step with an offset and one without are "equal" at that level.
            expectEquals(0, compare("/6/4!/4/2/2", "/6/4!/4/2/2:5"), "offset vs no offset")
        },

        Case("compare: range uses start then end") {
            val range = "epubcfi(/6/4!/4/2,/1:1,/1:4)"
            expectEquals(-1, compare(range, "/6/4!/4/2/1:2"), "range start before point")
            expectEquals(1, compare(range, "epubcfi(/6/4!/4/2,/1:0,/1:9)"), "range start after other")
            expectEquals(0, compare(range, range), "range equals itself")
        },

        Case("compare: shorter path sorts first") {
            expectEquals(-1, compare("/6/2!/4/2", "/6/2!/4/2!/6/2"), "fewer documents")
            expectEquals(-1, compare("/6/4!/4/2", "/6/4!/4/2/2"), "fewer steps")
        },

        Case("isRange / isPoint helpers") {
            expectTrue(isRange("epubcfi(/6/4!/4/2,/1:1,/1:4)"), "isRange")
            expectTrue(!isRange("/6/4!/4/2/1:1"), "isRange on a point")
            expectTrue(isPoint("/6/4!/4/2/1:1"), "isPoint")
        },

        Case("escapeCfi / wrap / unwrap") {
            expectEquals("a^(b^)c^[d^]e^^f^=g", escapeCfi("a(b)c[d]e^f=g"), "escapeCfi")
            expectEquals("plain/*-+!", escapeCfi("plain/*-+!"), "escapeCfi leaves / * - + ! alone")
            expectEquals("epubcfi(/6/2)", wrapCfi("/6/2"), "wrapCfi")
            expectEquals("/6/2", unwrapCfi("epubcfi(/6/2)"), "unwrapCfi")
            expectEquals("epubcfi(/6/2!/4/2)", joinIndir("epubcfi(/6/2)", "/4/2"), "joinIndir")
        },

        Case("CfiFake round-trips indices") {
            for (index in 0..5) {
                val path = (parse(CfiFake.fromIndex(index)) as Cfi.Point).documents.first()
                expectEquals(index, CfiFake.toIndex(path), "index round trip for $index")
            }
        },
    )

    // ── tiny framework-free assertion helpers ──────────────────────────────────

    private fun expectEquals(expected: Any?, actual: Any?, what: String) {
        if (expected != actual) {
            throw AssertionError("$what: expected <$expected> but was <$actual>")
        }
    }

    private fun expectTrue(condition: Boolean, what: String) {
        if (!condition) throw AssertionError("$what: expected true")
    }

    private fun expectCfiError(code: CfiErrorCode, what: String, block: () -> Unit) {
        val thrown = try {
            block()
            null
        } catch (e: CfiException) {
            e
        }
        if (thrown == null) {
            throw AssertionError("$what: expected CfiException($code) but nothing was thrown")
        }
        if (thrown.code != code) {
            throw AssertionError("$what: expected $code but got ${thrown.code} (${thrown.message})")
        }
    }
}
