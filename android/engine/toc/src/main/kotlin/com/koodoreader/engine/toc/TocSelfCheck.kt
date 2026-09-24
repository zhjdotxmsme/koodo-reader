package com.koodoreader.engine.toc

/**
 * Self-check runner for :engine:toc.
 *
 * Mirrors :engine:cfi:CfiSelfCheck — runs a minimal smoke test to verify
 * the module compiles and its core invariants hold, without requiring the
 * Android SDK or JUnit Platform.
 *
 * Exit codes:
 *   0 — all checks passed
 *   1 — one or more checks failed
 *
 * Run with:
 *   gradle :engine:toc:selfCheck
 */
fun main() {
    println("=== TocSelfCheck ===")
    var failed = false

    // Smoke test 1: TocModel round-trip
    run("TocModel build + flatten") {
        val root = listOf(
            TocNode(
                title = "Chapter 1",
                href = "ch1.xhtml",
                level = 0,
                cfiTarget = "epubcfi(/6/2!/4/2/2:0)",
                children = listOf(
                    TocNode(
                        title = "Section 1.1",
                        href = "ch1.xhtml#sec1",
                        level = 1,
                        cfiTarget = "epubcfi(/6/2!/4/2/2:100)",
                        children = emptyList(),
                    ),
                ),
            ),
            TocNode(
                title = "Chapter 2",
                href = "ch2.xhtml",
                level = 0,
                cfiTarget = "epubcfi(/6/4!/4/2/2:0)",
                children = emptyList(),
            ),
        )
        val model = TocModel.fromResolved(root)
        check(model.rootNodes.size == 2) { "Expected 2 root nodes, got ${model.rootNodes.size}" }
        check(model.flatNodes.size == 3) { "Expected 3 flat nodes, got ${model.flatNodes.size}" }

        // Pre-order: chapter1, section1.1, chapter2
        check(model.flatNodes[0].title == "Chapter 1")
        check(model.flatNodes[1].title == "Section 1.1")
        check(model.flatNodes[2].title == "Chapter 2")

        // lookupByHref
        check(model.lookupByHref("ch1.xhtml")?.title == "Chapter 1")
        check(model.lookupByHref("ch1.xhtml#sec1")?.title == "Section 1.1")
        check(model.lookupByHref("nonexistent") == null)

        // lookupByTitle
        val found = model.lookupByTitle("Chapter")
        check(found.size == 2) { "Expected 2 title matches, got ${found.size}" }
        check(model.lookupByTitle("nonexistent").isEmpty())
    }

    // Smoke test 2: PositionCodec round-trip
    run("PositionCodec encode/decode") {
        val pos = ReadingPosition(
            bookKey = "book-123",
            spineIndex = 2,
            cfi = "/4/2/2:50",
            chapterPercent = 0.25f,
            totalPercent = 0.10f,
        )
        val json = PositionCodec.encode(pos)
        val decoded = PositionCodec.decode(json)
        check(decoded.bookKey == pos.bookKey)
        check(decoded.spineIndex == pos.spineIndex)
        check(decoded.cfi == pos.cfi)
        check(decoded.chapterPercent == pos.chapterPercent)
        check(decoded.totalPercent == pos.totalPercent)
    }

    // Smoke test 3: ProgressComputer
    run("ProgressComputer boundary") {
        val spine = listOf(
            SpineChapter(1000, "epubcfi(/6/2!)"),
            SpineChapter(2000, "epubcfi(/6/4!)"),
            SpineChapter(500, "epubcfi(/6/6!)"),
        )
        val pc = ProgressComputer(spine)

        // First chapter, offset 0 → chapterPercent 0.0
        val (cp0, tp0) = pc.compute(0, "/0")
        check(cp0 == 0f) { "First chapter offset 0: chapterPercent expected 0.0, got $cp0" }
        check(tp0 == 0f) { "First chapter offset 0: totalPercent expected 0.0, got $tp0" }

        // Last chapter (index 2), offset = length → totalPercent ≈ 1.0
        val (_, tpLast) = pc.compute(2, "/500")
        check(kotlin.math.abs(tpLast - 1.0f) < 0.01f) {
            "Last chapter: totalPercent expected ~1.0, got $tpLast"
        }

        // Empty spine throws
        run("ProgressComputer empty spine throws") {
            try {
                ProgressComputer(emptyList())
                failed = true; println("  FAIL: empty spine did not throw")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    // Smoke test 4: SearchIndex
    run("SearchIndex basic") {
        val chapters = listOf(
            ChapterText(0, "Chapter 1", "This is the first chapter about Kotlin.", "epubcfi(/6/2!)"),
            ChapterText(1, "Chapter 2", "Kotlin is a modern language. Kotlin runs everywhere.", "epubcfi(/6/4!)"),
            ChapterText(2, "Chapter 3", "The third chapter mentions nothing about Kotlin.", "epubcfi(/6/6!)"),
        )
        val index = SearchIndex.build("book-x", chapters)

        // Case-insensitive multi-hit
        val hits = index.search(SearchQuery("book-x", "kotlin", caseSensitive = false))
        check(hits.size == 3) { "Expected 3 hits for 'kotlin', got ${hits.size}" }

        // Case-sensitive (only exact case)
        val hitsCs = index.search(SearchQuery("book-x", "Kotlin", caseSensitive = true))
        check(hitsCs.size == 2) { "Expected 2 case-sensitive hits for 'Kotlin', got ${hitsCs.size}" }

        // Multiple hits in same chapter
        val hitsK2 = index.search(SearchQuery("book-x", "Kotlin", caseSensitive = false))
        val ch2Hits = hitsK2.filter { it.spineIndex == 1 }
        check(ch2Hits.size == 2) { "Expected 2 hits in ch2, got ${ch2Hits.size}" }

        // Context preserved
        val firstHit = hits.first()
        check(firstHit.contextBefore.length <= CONTEXT_LEN_FOR_TEST)
        check(firstHit.contextAfter.length <= CONTEXT_LEN_FOR_TEST)
        check(firstHit.matchedText.isNotEmpty())

        // Ranks are sequential
        hits.forEachIndexed { i, h -> check(h.rank == i) { "Rank $i mismatch" } }

        // Empty query → empty list
        check(index.search(SearchQuery("book-x", "")).isEmpty())

        // No match → empty list
        check(index.search(SearchQuery("book-x", "zigzag").isEmpty()))
    }

    // CONTEXT_LEN is private; re-declare here for the test above
    val CONTEXT_LEN_FOR_TEST = 40

    if (failed) {
        println("\n=== FAIL ===")
        kotlin.system.exitProcess(1)
    } else {
        println("=== OK ===")
    }
}

private inline fun run(label: String, block: () -> Unit) {
    print("$label ... ")
    try {
        block()
        println("OK")
    } catch (e: Throwable) {
        println("FAIL: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}
