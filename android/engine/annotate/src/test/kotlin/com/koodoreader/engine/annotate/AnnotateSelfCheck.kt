package com.koodoreader.engine.annotate

import kotlin.system.exitProcess

/**
 * Framework-free self-check for `:engine:annotate`.
 * Runnable on any JVM with no test framework:
 *
 *   kotlin -cp engine/annotate/build/classes/kotlin/main AnnotateSelfCheck
 *
 * Exercises the core acceptance areas on REAL types:
 *  - AnnotationKind three-state enum and factory invariants
 *  - HighlightColor eight-colour palette + fromCode / fromHex degradation
 *  - AnnotationSchema column alignment with schema.lock notes table
 *  - CfiAnchor gateway (validity, point vs range, compare, overlaps)
 *  - AnnotationCodec round-trip (encode → decode, highlight / note / bookmark)
 *  - AnnotationStore CRUD, filter, overlap detection
 *
 * Prints OK / FAIL per item and exits non-zero on any aggregate failure.
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

    println("== AnnotationKind ==")
    check("three enum values", AnnotationKind.entries.size == 3)
    check("HIGHLIGHT enum", AnnotationKind.valueOf("HIGHLIGHT") == AnnotationKind.HIGHLIGHT)
    check("NOTE enum", AnnotationKind.valueOf("NOTE") == AnnotationKind.NOTE)
    check("BOOKMARK enum", AnnotationKind.valueOf("BOOKMARK") == AnnotationKind.BOOKMARK)

    // Factory: highlight
    val hl = Annotation.highlight(
        key = "1000000000001",
        bookKey = "book-selfcheck",
        cfiStart = "epubcfi(/6/4!/4/2/1:0)",
        cfiEnd = "epubcfi(/6/4!/4/2/2:10)",
        selectedText = "highlight text",
    )
    check("highlight factory kind", hl.kind == AnnotationKind.HIGHLIGHT)
    check("highlight noteText blank", hl.noteText == "")
    check("highlight isRange true", hl.isRange == true)

    // Factory: note
    val nt = Annotation.note(
        key = "1000000000002",
        bookKey = "book-selfcheck",
        cfiStart = "epubcfi(/6/4!/4/2/1:0)",
        cfiEnd = "epubcfi(/6/4!/4/2/2:10)",
        selectedText = "noted text",
        noteText = "my note",
    )
    check("note factory kind", nt.kind == AnnotationKind.NOTE)
    check("note factory noteText preserved", nt.noteText == "my note")
    check("note factory rejects blank", try {
        Annotation.note(
            key = "x",
            bookKey = "b",
            cfiStart = "epubcfi(/6/4!/4/2/1:0)",
            cfiEnd = "epubcfi(/6/4!/4/2/2:5)",
            selectedText = "x",
            noteText = "   ",
        )
        false
    } catch (e: IllegalArgumentException) {
        e.message?.contains("non-blank noteText") == true
    })

    // Factory: bookmark
    val bm = Annotation.bookmark(
        key = "1000000000003",
        bookKey = "book-selfcheck",
        cfi = "epubcfi(/6/4!/4/2/3:0)",
        label = "bookmark label",
    )
    check("bookmark factory kind", bm.kind == AnnotationKind.BOOKMARK)
    check("bookmark cfiEnd null", bm.cfiEnd == null)
    check("bookmark isRange false", bm.isRange == false)
    check("bookmark label preserved", bm.label == "bookmark label")

    // Factory: bookmark rejects range CFI (a range needs three comma-separated parts)
    check("bookmark rejects range CFI", try {
        Annotation.bookmark(
            key = "x",
            bookKey = "b",
            cfi = "epubcfi(/6/4!/4/2,/1:0,/3:5)",
        )
        false
    } catch (e: IllegalArgumentException) {
        e.message?.startsWith("expected a point CFI") == true
    })

    println("")
    println("== HighlightColor ==")
    check("DEFAULT is YELLOW", HighlightColor.DEFAULT == HighlightColor.YELLOW)
    check("eight colour entries", HighlightColor.entries.size == 8)
    check("codes 1 through 8", HighlightColor.entries.map { it.code }.sorted() == listOf(1, 2, 3, 4, 5, 6, 7, 8))
    check("all hex values start with #", HighlightColor.entries.all { it.hex.startsWith("#") })
    check("fromCode null → DEFAULT", HighlightColor.fromCode(null) == HighlightColor.DEFAULT)
    check("fromCode 0 → DEFAULT", HighlightColor.fromCode(0L) == HighlightColor.DEFAULT)
    check("fromCode 999 → DEFAULT", HighlightColor.fromCode(999L) == HighlightColor.DEFAULT)
    check("fromCode resolves all 8", HighlightColor.entries.all { HighlightColor.fromCode(it.code.toLong()) == it })
    check("fromHex null → DEFAULT", HighlightColor.fromHex(null) == HighlightColor.DEFAULT)
    check("fromHex blank → DEFAULT", HighlightColor.fromHex("   ") == HighlightColor.DEFAULT)
    check("fromHex unknown → DEFAULT", HighlightColor.fromHex("#DEADBEEF") == HighlightColor.DEFAULT)
    check("fromHex case-insensitive", HighlightColor.fromHex("#ffe54c") == HighlightColor.YELLOW)
    check("fromHex no-hash tolerated", HighlightColor.fromHex("FFE54C") == HighlightColor.YELLOW)
    check("fromHex resolves all 8", HighlightColor.entries.all { HighlightColor.fromHex(it.hex) == it })

    println("")
    println("== AnnotationSchema ==")
    // notes: 12 columns in schema.lock order
    val notesColNames = AnnotationSchema.NOTES.columnNames
    val expectedNotes = listOf(
        "key", "bookKey", "date", "chapter", "chapterIndex",
        "text", "cfi", "range", "notes", "percentage", "color", "tag",
    )
    check("notes 12 columns", notesColNames.size == 12)
    check("notes column order matches schema.lock", notesColNames == expectedNotes)
    check("notes primary key = key", AnnotationSchema.NOTES.primaryKeyNames == listOf("key"))
    check("notes date type = object", AnnotationSchema.NOTES.columns[2].type == "object")
    check("notes tag type = array", AnnotationSchema.NOTES.columns[11].type == "array")
    check("notes color type = INTEGER", AnnotationSchema.NOTES.columns[10].type == "INTEGER")

    // bookmarks: 6 columns in schema.lock order
    val bmColNames = AnnotationSchema.BOOKMARKS.columnNames
    val expectedBm = listOf("key", "bookKey", "cfi", "label", "percentage", "chapter")
    check("bookmarks 6 columns", bmColNames.size == 6)
    check("bookmarks column order matches schema.lock", bmColNames == expectedBm)
    check("bookmarks primary key = key", AnnotationSchema.BOOKMARKS.primaryKeyNames == listOf("key"))
    check("forTable notes → NOTES", AnnotationSchema.forTable("notes") == AnnotationSchema.NOTES)
    check("forTable bookmarks → BOOKMARKS", AnnotationSchema.forTable("bookmarks") == AnnotationSchema.BOOKMARKS)
    check("forTable unknown → null", AnnotationSchema.forTable("unknown") == null)

    println("")
    println("== CfiAnchor ==")
    val p1 = "epubcfi(/6/4!/4/2/1:0)"
    // Character offsets only serialize on ODD (text-node) steps — a `:5` on step `/2` is
    // dropped when the CFI is re-serialized, so every point here uses an odd index.
    val p2 = "epubcfi(/6/4!/4/2/3:5)"
    val p3 = "epubcfi(/6/4!/4/2/5:0)"
    val range1 = CfiAnchor.rangeCfi(p1, p2)
    check("isValid point CFI", CfiAnchor.isValid(p1))
    check("isValid range CFI", CfiAnchor.isValid(range1))
    check("isValid null → false", !CfiAnchor.isValid(null))
    check("isValid blank → false", !CfiAnchor.isValid("   "))
    check("isValid malformed → false", !CfiAnchor.isValid("not-a-cfi"))
    check("isPoint true for point", CfiAnchor.isPoint(p1))
    check("isPoint false for range", !CfiAnchor.isPoint(range1))
    check("isRange true for range", CfiAnchor.isRange(range1))
    check("isRange false for point", !CfiAnchor.isRange(p1))
    check("requireValid preserves valid CFI", CfiAnchor.requireValid(p1) == p1)
    check("requirePoint preserves point", CfiAnchor.requirePoint(p1) == p1)
    check("requirePoint rejects range", try {
        CfiAnchor.requirePoint(range1); false
    } catch (e: IllegalArgumentException) {
        e.message?.startsWith("expected a point CFI") == true
    })
    check("rangeCfi produces range", CfiAnchor.isRange(range1))
    check("startPoint collapses range to p1", CfiAnchor.startPoint(range1) == p1)
    check("endPoint collapses range to p2", CfiAnchor.endPoint(range1) == p2)
    check("startPoint leaves point unchanged", CfiAnchor.startPoint(p1) == p1)
    check("endPoint leaves point unchanged", CfiAnchor.endPoint(p1) == p1)
    check("compare identical = 0", CfiAnchor.compare(p1, p1) == 0)
    check("compare earlier < later", CfiAnchor.compare(p1, p3) < 0)
    check("overlaps overlapping ranges", CfiAnchor.overlaps(p1, p3, p2, p3))
    check("overlaps touching endpoints", CfiAnchor.overlaps(p1, p2, p2, p3))
    check("overlaps disjoint → false", !CfiAnchor.overlaps(p1, "epubcfi(/6/4!/4/2/1:100)", "epubcfi(/6/4!/4/2/3:0)", p3))

    println("")
    println("== AnnotationCodec round-trip ==")
    val encodedHl = AnnotationCodec.encode(hl)
    val decodedHl = AnnotationCodec.decode(encodedHl)
    check("highlight key round-trip", decodedHl.key == hl.key)
    check("highlight kind round-trip", decodedHl.kind == AnnotationKind.HIGHLIGHT)
    check("highlight cfiStart round-trip", decodedHl.cfiStart == hl.cfiStart)
    check("highlight cfiEnd round-trip", decodedHl.cfiEnd == hl.cfiEnd)
    check("highlight selectedText round-trip", decodedHl.selectedText == hl.selectedText)
    check("highlight color round-trip", decodedHl.color == hl.color)

    val encodedNt = AnnotationCodec.encode(nt)
    val decodedNt = AnnotationCodec.decode(encodedNt)
    check("note kind round-trip", decodedNt.kind == AnnotationKind.NOTE)
    check("note noteText round-trip", decodedNt.noteText == nt.noteText)
    check("note color round-trip", decodedNt.color == nt.color)

    val encodedBm = AnnotationCodec.encode(bm)
    val decodedBm = AnnotationCodec.decode(encodedBm)
    check("bookmark kind round-trip", decodedBm.kind == AnnotationKind.BOOKMARK)
    check("bookmark cfiStart round-trip", decodedBm.cfiStart == bm.cfiStart)
    check("bookmark label round-trip", decodedBm.label == bm.label)

    // Different keys → different JSON
    val a2 = hl.copy(key = "9999999999999")
    check("different keys → different JSON", AnnotationCodec.encode(a2) != encodedHl)

    // All colours round-trip
    var allColorsOk = true
    for (color in HighlightColor.entries) {
        val coloured = Annotation.highlight(
            key = "key-${color.code}",
            bookKey = "book",
            cfiStart = p1,
            cfiEnd = p2,
            selectedText = "x",
            color = color,
        )
        val decoded = AnnotationCodec.decode(AnnotationCodec.encode(coloured))
        if (decoded.color != color) {
            allColorsOk = false
            break
        }
    }
    check("all 8 colors round-trip", allColorsOk)

    // Empty tags round-trip
    val noTags = Annotation.highlight(
        key = "tags-empty",
        bookKey = "book",
        cfiStart = p1,
        cfiEnd = p2,
        selectedText = "x",
        tags = emptyList(),
    )
    check("empty tags round-trip", AnnotationCodec.decode(AnnotationCodec.encode(noTags)).tags == emptyList<String>())

    // Unicode tags round-trip
    val unicodeTags = Annotation.highlight(
        key = "tags-unicode",
        bookKey = "book",
        cfiStart = p1,
        cfiEnd = p2,
        selectedText = "x",
        tags = listOf("日本語", "emoji 🎵"),
    )
    check("unicode tags round-trip", AnnotationCodec.decode(AnnotationCodec.encode(unicodeTags)).tags == listOf("日本語", "emoji 🎵"))

    // Percentage boundary
    val pct0 = Annotation.highlight(
        key = "pct0",
        bookKey = "book",
        cfiStart = p1,
        cfiEnd = p2,
        selectedText = "x",
        percentage = "0",
    )
    check("percentage zero round-trip", AnnotationCodec.decode(AnnotationCodec.encode(pct0)).percentage == "0")

    val pctDec = Annotation.highlight(
        key = "pctdec",
        bookKey = "book",
        cfiStart = p1,
        cfiEnd = p2,
        selectedText = "x",
        percentage = "0.999",
    )
    check("percentage decimal round-trip", AnnotationCodec.decode(AnnotationCodec.encode(pctDec)).percentage == "0.999")

    // Date parts round-trip
    val withDate = Annotation.highlight(
        key = "1704067200000",
        bookKey = "book",
        cfiStart = p1,
        cfiEnd = p2,
        selectedText = "x",
        createdAt = 1704067200000L, // 2024-01-01 UTC
    )
    check("date parts round-trip", AnnotationCodec.decode(AnnotationCodec.encode(withDate)).dateParts() == withDate.dateParts())

    println("")
    println("== AnnotationStore ==")
    val store = AnnotationStore()

    // Add
    store.add(hl)
    check("add increases size to 1", store.all().size == 1)

    // Duplicate key rejected
    check("add duplicate key throws", try {
        store.add(hl); false
    } catch (e: IllegalArgumentException) {
        e.message == "duplicate key"
    })

    // Get
    check("get existing key returns annotation", store.get(hl.key) == hl)
    check("get unknown key returns null", store.get("no-such-key") == null)

    // Update
    val updated = hl.copy(selectedText = "updated text")
    store.update(updated)
    check("update changes selectedText", store.get(hl.key)?.selectedText == "updated text")

    // Update unknown key throws
    check("update unknown key throws", try {
        store.update(hl.copy(key = "no-such-key")); false
    } catch (e: IllegalArgumentException) {
        e.message == "key not found"
    })

    // Remove
    store.remove(hl.key)
    check("remove deletes annotation", store.all().isEmpty())

    // Remove unknown key is no-op
    store.remove("no-such-key") // must not throw

    // Filter by bookKey
    store.add(hl)                              // key 1000000000001, book-selfcheck
    store.add(nt)                              // key 1000000000002, book-selfcheck
    val diffBook = Annotation.highlight(
        key = "other-book",
        bookKey = "other-book-key",
        cfiStart = p1,
        cfiEnd = p2,
        selectedText = "other",
    )
    store.add(diffBook)

    check("filterByBookKey exact match count", store.filterByBookKey("book-selfcheck").size == 2)
    check("filterByBookKey no-match empty", store.filterByBookKey("no-such-book").isEmpty())
    check("filterByBookKey case-sensitive", store.filterByBookKey("Book-SelfCheck").isEmpty())

    // Overlap detection
    val rangeA = Annotation.highlight(
        key = "range-a",
        bookKey = "b",
        cfiStart = "epubcfi(/6/4!/4/2/1:0)",
        cfiEnd = "epubcfi(/6/4!/4/2/5:0)",
        selectedText = "a",
    )
    val rangeB = Annotation.highlight(
        key = "range-b",
        bookKey = "b",
        cfiStart = "epubcfi(/6/4!/4/2/3:0)",
        cfiEnd = "epubcfi(/6/4!/4/2/7:0)",
        selectedText = "b",
    )
    val rangeC = Annotation.highlight(
        key = "range-c",
        bookKey = "b",
        cfiStart = "epubcfi(/6/4!/4/2/10:0)",
        cfiEnd = "epubcfi(/6/4!/4/2/12:0)",
        selectedText = "c",
    )
    val overlapStore = AnnotationStore(listOf(rangeA, rangeB, rangeC))
    val hits = overlapStore.overlappingAnchors(
        "epubcfi(/6/4!/4/2/1:0)",
        "epubcfi(/6/4!/4/2/6:0)",
    )
    check("overlappingAnchors finds A and B", hits.map { it.key }.containsAll(listOf("range-a", "range-b")))
    check("overlappingAnchors excludes disjoint C", !hits.map { it.key }.contains("range-c"))
    check("overlappingAnchors touching counts", overlapStore.overlappingAnchors(
        "epubcfi(/6/4!/4/2/1:0)",
        "epubcfi(/6/4!/4/2/3:0)", // ends exactly where B starts
    ).map { it.key }.contains("range-b"))

    // Overlap ignores bookmarks (point annotations)
    val bmInRange = Annotation.bookmark(
        key = "bm-in-range",
        bookKey = "b",
        cfi = "epubcfi(/6/4!/4/2/3:0)",
    )
    val storeWithBm = AnnotationStore(listOf(bmInRange))
    check("overlappingAnchors ignores bookmarks", storeWithBm.overlappingAnchors(p1, p3).isEmpty())

    println("")
    if (failures > 0) {
        println("FAIL: $failures check(s) failed")
        exitProcess(1)
    }
    println("PASS: all annotate self-checks passed")
}
