package com.koodoreader.engine.link

import kotlin.system.exitProcess

/**
 * Framework-free self-check for `:engine:link` (mirror of
 * `:engine:gesture`'s GestureSelfCheck). Runnable on any JVM with no test
 * framework:
 *
 *   gradle :engine:link:selfCheck
 *
 * Exercises the four acceptance areas end-to-end on the REAL types:
 *  - protocol literals (event/field names, 15 MiB limit),
 *  - image policy (source guard + inclusive size bound),
 *  - footnote extraction + two-way body↔note addressing,
 *  - link classification + routing (browser / internal jump / footnote / none).
 *
 * Prints OK/FAIL per item and exits non-zero on the first aggregate failure.
 */
fun main() {
    var failures = 0
    fun check(name: String, condition: Boolean) {
        if (condition) {
            println("  OK    $name")
        } else {
            println("  FAIL  $name")
            failures++
        }
    }

    println("== Protocol ==")
    check("view-image literal", LinkProtocol.EVENT_VIEW_IMAGE == "view-image")
    check("link-clicked literal", LinkProtocol.EVENT_LINK_CLICKED == "link-clicked")
    check("imgSrc field", LinkProtocol.FIELD_IMG_SRC == "imgSrc")
    check("href field", LinkProtocol.FIELD_HREF == "href")
    check("footnote field", LinkProtocol.FIELD_FOOTNOTE == "footnote")
    check("15 MiB limit", LinkProtocol.IMAGE_SIZE_LIMIT == 15L * 1024 * 1024)

    println("")
    println("== ImagePolicy ==")
    val dataUri = "data:image/png;base64,iVBORw0KGgo="
    check(
        "data: URI opens natively",
        ImageSpec(ImageSource.of(dataUri)).decision() == ImageDecision.OPEN_NATIVE,
    )
    check(
        "non-data: source is rejected",
        ImageSpec(ImageSource.of("images/a.png")).decision() == ImageDecision.REJECTED_SOURCE,
    )
    // Boundary semantics with a tiny custom policy (avoids allocating 15 MiB):
    val smallPolicy = ImagePolicy(dataUriTextLengthLimit = 10L)
    check(
        "length == limit still opens (inclusive)",
        ImageSpec(ImageSource.DataUri("data:12345")) // length 10
            .decision(smallPolicy) == ImageDecision.OPEN_NATIVE,
    )
    check(
        "length > limit is too large",
        ImageSpec(ImageSource.DataUri("data:123456")) // length 11
            .decision(smallPolicy) == ImageDecision.TOO_LARGE,
    )

    println("")
    println("== Footnotes (two-way) ==")
    val extraction = FootnoteExtractor.extract(
        chapterKey = "ch1",
        refs = listOf(
            RefEntry(refCfi = "epubcfi(/4/2/2)", anchor = "r1", number = 1),
            RefEntry(refCfi = "epubcfi(/4/4/2)", anchor = "r2", number = 2),
        ),
        definitions = listOf(
            DefinitionEntry(cfiTarget = "epubcfi(/2/2)", anchor = "n1", number = 1, text = "first"),
            DefinitionEntry(cfiTarget = "epubcfi(/2/4)", anchor = "n2", number = 2, text = "second"),
        ),
    )
    val chapter = extraction.chapter
    check("no unlinked refs", extraction.unlinkedRefs.isEmpty())
    check("no orphan definitions", extraction.orphanDefinitions.isEmpty())
    check("forward byRefCfi hit", chapter.byRefCfi("epubcfi(/4/4/2)")?.text == "second")
    check("forward byRefCfi miss", chapter.byRefCfi("epubcfi(/9/9/9)") == null)
    check("reverse by number hit", chapter.refsFor(1).map { it.refCfi } == listOf("epubcfi(/4/2/2)"))
    check(
        "reverse by definition CFI hit",
        chapter.refsForCfiTarget("epubcfi(/2/4)").map { it.anchor } == listOf("r2"),
    )
    check("reverse miss is empty", chapter.refsForCfiTarget("epubcfi(/2/99)").isEmpty())

    println("")
    println("== Link classification ==")
    check("anchor", LinkClassifier.classify("#x").kind == LinkKind.ANCHOR)
    check(
        "wrapped epubcfi valid",
        LinkClassifier.classify("epubcfi(/6/4[chap01ref]!/4/2)").cfiValid == true,
    )
    check(
        "versioned epubcfi: URI valid",
        LinkClassifier.classify("epubcfi:0?/6/4[chap01ref]!/4/2").cfiValid == true,
    )
    check(
        "malformed epubcfi invalid but classified",
        LinkClassifier.classify("epubcfi:2").let { it.kind == LinkKind.EPUBCFI && it.cfiValid == false },
    )
    check("http", LinkClassifier.classify("http://e.com").kind == LinkKind.EXTERNAL_HTTP)
    check("https", LinkClassifier.classify("https://e.com").kind == LinkKind.EXTERNAL_HTTP)
    check("mixed-case HTTP", LinkClassifier.classify("HTTP://E.COM").kind == LinkKind.EXTERNAL_HTTP)
    check(
        "mailto address parsed",
        LinkClassifier.classify("mailto:a@b.com?subject=x").emailAddress == "a@b.com",
    )
    check("content → SAF", LinkClassifier.classify("content://m/1").kind == LinkKind.SAF)
    check("file → SAF", LinkClassifier.classify("file:///s/a").kind == LinkKind.SAF)
    check("javascript → OTHER", LinkClassifier.classify("javascript:0").kind == LinkKind.OTHER)
    check("relative path → OTHER", LinkClassifier.classify("chap.xhtml").kind == LinkKind.OTHER)

    println("")
    println("== Link routing ==")
    val router = LinkRouter()
    check("https → system browser", router.route("https://e.com") is LinkAction.OpenBrowser)
    check("mailto → system browser", router.route("mailto:a@b.com") is LinkAction.OpenBrowser)
    check(
        "valid epubcfi → internal jump",
        router.route("epubcfi(/6/4[chap01ref]!/4/2)") is LinkAction.InternalJump,
    )
    check("anchor → none", router.route("#x") == LinkAction.None)
    check("SAF closed by default", router.route("content://m/1") == LinkAction.None)
    check(
        "whitelisted SAF opens",
        LinkRouter(LinkPolicy(safWhitelist = setOf("content://m/1")))
            .route("content://m/1") is LinkAction.OpenBrowser,
    )
    check(
        "event: footnote wins over href",
        router.routeEvent(LinkClickEvent("https://e.com", "the note")) is LinkAction.Footnote,
    )
    check(
        "event: blank footnote falls through to href",
        router.routeEvent(LinkClickEvent("https://e.com", " ")) is LinkAction.OpenBrowser,
    )
    check(
        "blocking http closes that scheme only",
        LinkRouter(LinkPolicy(allowHttp = false)).route("http://e.com") == LinkAction.None,
    )

    if (failures > 0) {
        println("")
        println("FAIL: $failures check(s) failed")
        exitProcess(1)
    }
    println("")
    println("PASS: all link self-checks passed")
}
