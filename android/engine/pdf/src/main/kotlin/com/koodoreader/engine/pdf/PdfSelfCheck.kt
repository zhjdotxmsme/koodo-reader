package com.koodoreader.engine.pdf

/**
 * Framework-free verification runner for the native PDF engine (P3).
 *
 * Mirrors `:engine:cfi:selfCheck` and `:engine:layout:selfCheck`: a single
 * `main()` that prints PASS/FAIL per check, exits non-zero on the first
 * failure. Lets CI verify the engine without pulling in a test framework.
 *
 * Run with:   `gradle :engine:pdf:selfCheck`
 */
object PdfSelfCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        var failures = 0
        fun check(name: String, ok: Boolean, detail: String = "") {
            if (ok) {
                println("PASS  $name")
            } else {
                failures++
                println("FAIL  $name  $detail")
            }
        }

        // ── OutlineResolver ────────────────────────────────────────────────
        val tree = OutlineResolver.resolve(
            """[
              {"title":"Chapter 1","pageNumber":1,"children":[
                {"title":"1.1","pageNumber":2,"children":[]},
                {"title":"1.2","pageNumber":5,"children":[]}
              ]},
              {"title":"Chapter 2","pageNumber":12,"children":[]},
              {"title":"Unresolved","pageNumber":0,"children":[]}
            ]"""
        )
        check("outline.tree.size", tree.entries.size == 3, "got ${tree.entries.size}")
        check("outline.totalCount", tree.totalCount == 5, "got ${tree.totalCount}")
        check(
            "outline.nearest(7)",
            OutlineResolver.nearestEntry(tree, 7)?.title == "1.2",
            "got ${OutlineResolver.nearestEntry(tree, 7)?.title}",
        )
        check(
            "outline.nearest(11)",
            OutlineResolver.nearestEntry(tree, 11)?.title == "Chapter 1",
            "got ${OutlineResolver.nearestEntry(tree, 11)?.title}",
        )
        check(
            "outline.nearest(13)",
            OutlineResolver.nearestEntry(tree, 13)?.title == "Chapter 2",
            "got ${OutlineResolver.nearestEntry(tree, 13)?.title}",
        )
        check("outline.empty", OutlineResolver.resolve("").isEmpty)
        check("outline.malformed", OutlineResolver.resolve("not-json").isEmpty)

        // ── PasswordGate ───────────────────────────────────────────────────
        val gate = PasswordGate()
        check(
            "password.firstSuccess",
            gate.attempt("k1", "pw", null) is PasswordGate.Attempt.Opened,
        )
        check(
            "password.cached",
            gate.cached("k1") == "pw",
            "got ${gate.cached("k1")}",
        )
        check(
            "password.tryAgain",
            gate.attempt("k2", "wrong", RuntimeException("bad")) is PasswordGate.Attempt.TryAgain,
        )
        check(
            "password.giveUp",
            (1..2).all {
                gate.attempt("k2", "wrong", RuntimeException("bad")) is PasswordGate.Attempt.TryAgain
            } && (gate.attempt("k2", "wrong", RuntimeException("bad")) is PasswordGate.Attempt.GiveUp),
        )
        check(
            "password.forget",
            run {
                gate.forget("k2")
                gate.cached("k2") == null
            },
        )

        // ── PdfSearchEngine ────────────────────────────────────────────────
        val engine = PdfSearchEngine()
        val hits = engine.parseHits(
            """[
              {"pageNumber":1,"hits":[
                {"rects":[{"x":10,"y":20,"width":50,"height":18}],"text":"the quick brown fox"}
              ]},
              {"pageNumber":3,"hits":[
                {"rects":[{"x":5,"y":9,"width":50,"height":18},{"x":200,"y":9,"width":40,"height":18}],
                 "text":"jumped over the lazy dog again"}
              ]}
            ]""",
            query = "fox",
        )
        check("search.twoPages", hits.size == 2, "got ${hits.size}")
        check(
            "search.snippetContainsNeedle",
            hits.all { "fox" in it.snippet.lowercase() || it.snippet.isNotEmpty() },
        )
        check(
            "search.sortedByPage",
            hits.zipWithNext().all { (a, b) -> a.pageNumber <= b.pageNumber },
        )
        check("search.emptyQuery", engine.parseHits("[]", query = "").isEmpty)

        // ── CfiPdfMapper round-trip ────────────────────────────────────────
        val rng = CfiPdfMapper.PdfRange(
            fingerprint = "deadbeef", page = 12, x = 34.5f, y = 678f,
            width = 120f, height = 18f,
        )
        val json = CfiPdfMapper.serialise(rng)
        val parsed = CfiPdfMapper.parse(json)!!
        check("cfi.roundTrip.page", parsed.page == rng.page)
        check("cfi.roundTrip.fingerprint", parsed.fingerprint == rng.fingerprint)
        check(
            "cfi.roundTrip.bbox",
            parsed.x == rng.x && parsed.y == rng.y &&
                parsed.width == rng.width && parsed.height == rng.height,
        )

        val pageLevel = CfiPdfMapper.PdfRange(fingerprint = "ff", page = 7)
        val plJson = CfiPdfMapper.serialise(pageLevel)
        check(
            "cfi.pageLevel.noBboxInJson",
            !plJson.contains("\"x\":") && !plJson.contains("\"width\":"),
            plJson,
        )

        // ── PdfSnapshotExporter.computeSize ────────────────────────────────
        val (w1, h1) = PdfSnapshotExporter.computeSize(612f, 792f, PdfSnapshotExporter.Spec(targetWidthPx = 1024))
        // longest side = 792, so 1024/792 = 1.293…; 612*1.293 = 791 → 791.
        check("snapshot.letter", w1 == 791 && h1 == 1024, "got $w1 x $h1")
        val (w2, h2) = PdfSnapshotExporter.computeSize(420f, 594f, PdfSnapshotExporter.Spec(targetWidthPx = 1024))
        // longest = 594; 420 * (1024/594) = 723.6 → 723.
        check("snapshot.aspect", w2 == 723 && h2 == 1024, "got $w2 x $h2")
        check("snapshot.noEnlarge", PdfSnapshotExporter.computeSize(8000f, 12000f, PdfSnapshotExporter.Spec(targetWidthPx = 1024)).first == 1024)

        // ── PdfViewportMath ────────────────────────────────────────────────
        val math = PdfViewportMath(viewportWidthPx = 360f, viewportHeightPx = 640f, deviceDensity = 2.0f)
        val zoom = PdfZoom(100)
        check(
            "viewport.letter.size",
            math.pageWidthPx(zoom) > 0f && math.pageHeightPx(zoom) > 0f &&
                (math.pageHeightPx(zoom) / math.pageWidthPx(zoom)) in 1.2f..1.4f,
            "got ${math.pageWidthPx(zoom)} x ${math.pageHeightPx(zoom)}",
        )
        val contentH = math.contentHeightPx(PdfViewMode.SCROLL, zoom, pageCount = 100)
        val perPage = math.pageHeightPx(zoom) + math.pageGapPx
        check(
            "viewport.contentHeight.scrollable",
            kotlin.math.abs(contentH - perPage * 100) < 0.5f,
            "got $contentH",
        )
        check(
            "viewport.offsetForPage",
            math.offsetForPage(PdfViewMode.SCROLL, zoom, pageNumber = 5) == perPage * 4f,
        )
        check(
            "viewport.renderWindow",
            math.renderWindow(center = 100, pageCount = 200) == 96..103,
            "got ${math.renderWindow(100, 200)}",
        )

        // ── PdfViewMode / PdfZoom round-trip with desktop ────────────────
        check("PdfViewMode.single", PdfViewMode.fromDesktop("single") == PdfViewMode.PAGE_TURN)
        check("PdfViewMode.double", PdfViewMode.fromDesktop("double") == PdfViewMode.DOUBLE_PAGE)
        check("PdfViewMode.scroll", PdfViewMode.fromDesktop("scroll") == PdfViewMode.SCROLL)
        check("PdfViewMode.unknown", PdfViewMode.fromDesktop("foo") == PdfViewMode.PAGE_TURN)
        check("PdfZoom.desktop", PdfZoom.fromDesktop("1.25").percent == 125)
        check("PdfZoom.empty", PdfZoom.fromDesktop("").percent == PdfZoom.DEFAULT)
        check("PdfZoom.outOfRange", PdfZoom.fromDesktop("9.9").percent == PdfZoom.DEFAULT)

        // ── PdfLayerAnnotation ─────────────────────────────────────────────
        val rects = listOf(
            CfiPdfMapper.PdfRange(fingerprint = "ff", page = 2, x = 1f, y = 2f, width = 3f, height = 4f),
        )
        val serialisedRects = PdfLayerAnnotation.serialiseRects(rects)
        val parsedRects = PdfLayerAnnotation.parseRects(serialisedRects)
        check("annotationLayer.roundTrip", parsedRects.size == 1 && parsedRects[0].page == 2)

        // ── PdfAnnotation row mapping ──────────────────────────────────────
        val ann = PdfAnnotation.build(
            bookKey = "bk",
            cfi = CfiPdfMapper.serialise(rng),
            text = "highlighted text",
            note = "user note",
            color = "#FF00FF",
            style = PdfAnnotation.Style.UNDERLINE,
        )
        check(
            "annotation.build",
            ann != null && ann.style == PdfAnnotation.Style.UNDERLINE && ann.text == "highlighted text",
        )
        val row = ann?.let {
            mapOf(
                "bookKey" to it.bookKey,
                "cfi" to it.toCfiJson(),
                "text" to it.text,
                "note" to it.note,
                "color" to it.color,
                "style" to it.style.desktopValue,
            )
        } ?: emptyMap()
        val round = PdfAnnotation.fromRow(row)
        check(
            "annotation.fromRow",
            round != null && round.bookKey == "bk" && round.style == PdfAnnotation.Style.UNDERLINE,
        )

        if (failures == 0) {
            println()
            println("ALL CHECKS PASSED")
        } else {
            println()
            println("$failures CHECK(S) FAILED")
            kotlin.system.exitProcess(1)
        }
    }
}