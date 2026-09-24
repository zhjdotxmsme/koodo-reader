package com.koodoreader.engine.annotate

/**
 * Codec — converts [Annotation] to/from the two serialized shapes it must
 * support:
 *
 * 1. **Room rows** ([NoteRow] / [BookmarkRow]) for local persistence on the
 *    existing `NoteEntity` / `BookmarkEntity` tables. Here the `cfi` column
 *    holds a canonical EPUB CFI directly: a range CFI for highlights/notes,
 *    a point CFI for bookmarks. The Android renderer consumes it with no
 *    unwrapping, and `color` holds the stable integer code.
 *
 * 2. **Desktop records** (fielded [JsonValue.JsonObject]) for backup-zip
 *    import/export. This mirrors the bytes the desktop writes:
 *     - the `cfi` column is `JSON.stringify(recordLocation)` — a STRING
 *       containing a location object whose own `cfi` field is the real
 *       EPUB CFI (`src/utils/reader/noteUtil.ts`, operationPanel bookmark);
 *     - `date` is a nested `{year,month,day}` object and `tag` a nested
 *       string array (desktop "object"/"array" columns);
 *     - `color` is the desktop hex string.
 *
 * The desktop `cfi` shape loses the range END (recordLocation carries only
 * the current/start position — desktop restores ranges mainly from the
 * DOM-coordinate `range` column). To keep an Annotation lossless across a
 * backup round-trip, the recordLocation we write adds ONE forward-compatible
 * field, `cfiEnd`; the desktop client JSON.parses the column and simply
 * ignores the unknown member, while [fromRecord] reads it. A record without
 * `cfiEnd` degrades to a single-point anchor, which is exactly how an
 * authentic desktop row behaves.
 *
 * No [Any] crosses the boundary: every field is a concrete Kotlin/JSON type.
 */
object AnnotationCodec {

    // ── Annotation → Room rows ───────────────────────────────────────────

    /** Serialize to a `notes` row for the existing Room `NoteEntity` table. */
    fun toNoteRow(a: Annotation): NoteRow {
        require(a.kind != AnnotationKind.BOOKMARK) { "bookmarks do not map to a notes row" }
        return NoteRow(
            key = a.key,
            bookKey = a.bookKey,
            date = toJsonString(dateObject(a)),
            chapter = a.chapter,
            chapterIndex = a.chapterIndex.toLong(),
            text = a.selectedText,
            cfi = storageCfi(a),
            range = a.range,
            notes = a.noteText,
            percentage = a.percentage,
            color = a.color.code.toLong(),
            tag = toJsonString(JsonValue.stringArray(a.tags)),
        )
    }

    /** Serialize to a `bookmarks` row for the existing Room `BookmarkEntity`. */
    fun toBookmarkRow(a: Annotation): BookmarkRow {
        require(a.kind == AnnotationKind.BOOKMARK) { "only bookmarks map to a bookmarks row" }
        return BookmarkRow(
            key = a.key,
            bookKey = a.bookKey,
            cfi = a.cfiStart,
            label = a.label,
            percentage = a.percentage,
            chapter = a.chapter,
        )
    }

    // ── Room rows → Annotation ───────────────────────────────────────────

    /** Rebuild a highlight/note from a Room `notes` row. */
    fun noteRowToAnnotation(row: NoteRow): Annotation {
        val kind = if (row.notes.isNullOrBlank()) AnnotationKind.HIGHLIGHT else AnnotationKind.NOTE
        val cfi = row.cfi ?: ""
        val (start, end) = splitStorageCfi(cfi)
        val createdAt = row.key.toLongOrNull() ?: 0L
        return Annotation(
            key = row.key,
            bookKey = row.bookKey ?: "",
            kind = kind,
            cfiStart = start,
            cfiEnd = end,
            color = HighlightColor.fromCode(row.color),
            selectedText = row.text ?: "",
            noteText = row.notes ?: "",
            chapter = row.chapter ?: "",
            chapterIndex = row.chapterIndex?.toInt() ?: 0,
            percentage = row.percentage ?: "0",
            range = row.range,
            tags = decodeStringList(row.tag),
            label = "",
            createdAt = createdAt,
        )
    }

    /** Rebuild a bookmark from a Room `bookmarks` row. */
    fun bookmarkRowToAnnotation(row: BookmarkRow): Annotation {
        val point = row.cfi?.takeIf { CfiAnchor.isValid(it) }?.let { CfiAnchor.requireValid(it) } ?: (row.cfi ?: "")
        return Annotation(
            key = row.key,
            bookKey = row.bookKey ?: "",
            kind = AnnotationKind.BOOKMARK,
            cfiStart = point,
            cfiEnd = null,
            color = HighlightColor.DEFAULT,
            selectedText = "",
            noteText = "",
            chapter = row.chapter ?: "",
            chapterIndex = 0,
            percentage = row.percentage ?: "0",
            range = null,
            tags = emptyList(),
            label = row.label ?: "",
            createdAt = row.key.toLongOrNull() ?: 0L,
        )
    }

    // ── Annotation → desktop / backup records ────────────────────────────

    /**
     * Build the desktop `notes` record (column names as keys) written into a
     * backup zip.
     */
    fun toNoteRecord(a: Annotation): JsonValue.JsonObject {
        require(a.kind != AnnotationKind.BOOKMARK) { "bookmarks do not map to a notes record" }
        val parts = a.dateParts()
        return JsonValue.JsonObject(
            AnnotationSchema.COL_KEY to JsonValue.of(a.key),
            AnnotationSchema.COL_BOOK_KEY to JsonValue.of(a.bookKey),
            AnnotationSchema.COL_DATE to JsonValue.JsonObject(
                "year" to JsonValue.of(parts.year),
                "month" to JsonValue.of(parts.month),
                "day" to JsonValue.of(parts.day),
            ),
            AnnotationSchema.COL_CHAPTER to JsonValue.of(a.chapter),
            AnnotationSchema.COL_CHAPTER_INDEX to JsonValue.of(a.chapterIndex),
            AnnotationSchema.COL_TEXT to JsonValue.of(a.selectedText),
            AnnotationSchema.COL_CFI to JsonValue.of(recordLocationJson(a)),
            AnnotationSchema.COL_RANGE to JsonValue.of(a.range ?: ""),
            AnnotationSchema.COL_NOTES to JsonValue.of(a.noteText),
            AnnotationSchema.COL_PERCENTAGE to JsonValue.of(a.percentage),
            // Desktop stores the hex string despite the column being "integer".
            AnnotationSchema.COL_COLOR to JsonValue.of(a.color.hex),
            AnnotationSchema.COL_TAG to JsonValue.stringArray(a.tags),
        )
    }

    /** Build the desktop `bookmarks` record written into a backup zip. */
    fun toBookmarkRecord(a: Annotation): JsonValue.JsonObject {
        require(a.kind == AnnotationKind.BOOKMARK) { "only bookmarks map to a bookmarks record" }
        return JsonValue.JsonObject(
            AnnotationSchema.COL_KEY to JsonValue.of(a.key),
            AnnotationSchema.COL_BOOK_KEY to JsonValue.of(a.bookKey),
            AnnotationSchema.COL_CFI to JsonValue.of(recordLocationJson(a)),
            AnnotationSchema.COL_LABEL to JsonValue.of(a.label),
            AnnotationSchema.COL_PERCENTAGE to JsonValue.of(a.percentage),
            AnnotationSchema.COL_CHAPTER to JsonValue.of(a.chapter),
        )
    }

    // ── Desktop / backup records → Annotation ────────────────────────────

    /** Rebuild a highlight/note from a desktop `notes` record. */
    fun fromNoteRecord(record: JsonValue.JsonObject): Annotation {
        val key = record[AnnotationSchema.COL_KEY].asString() ?: ""
        val noteText = record[AnnotationSchema.COL_NOTES].asString() ?: ""
        val kind = if (noteText.isBlank()) AnnotationKind.HIGHLIGHT else AnnotationKind.NOTE
        val (start, end) = extractCfi(record[AnnotationSchema.COL_CFI])
        val chapterIndex = record[AnnotationSchema.COL_CHAPTER_INDEX].asLong()?.toInt()
            ?: locationOf(record)?.get("chapterDocIndex")?.asLong()?.toInt()
            ?: 0
        return Annotation(
            key = key,
            bookKey = record[AnnotationSchema.COL_BOOK_KEY].asString() ?: "",
            kind = kind,
            cfiStart = start,
            cfiEnd = end,
            color = resolveColor(record[AnnotationSchema.COL_COLOR]),
            selectedText = record[AnnotationSchema.COL_TEXT].asString() ?: "",
            noteText = noteText,
            chapter = record[AnnotationSchema.COL_CHAPTER].asString() ?: "",
            chapterIndex = chapterIndex,
            percentage = record[AnnotationSchema.COL_PERCENTAGE].asString() ?: "0",
            range = record[AnnotationSchema.COL_RANGE].asString()?.takeIf { it.isNotBlank() },
            tags = decodeTagValue(record[AnnotationSchema.COL_TAG]),
            label = "",
            createdAt = key.toLongOrNull() ?: 0L,
        )
    }

    /** Rebuild a bookmark from a desktop `bookmarks` record. */
    fun fromBookmarkRecord(record: JsonValue.JsonObject): Annotation {
        val key = record[AnnotationSchema.COL_KEY].asString() ?: ""
        val (start, _) = extractCfi(record[AnnotationSchema.COL_CFI])
        return Annotation(
            key = key,
            bookKey = record[AnnotationSchema.COL_BOOK_KEY].asString() ?: "",
            kind = AnnotationKind.BOOKMARK,
            cfiStart = start,
            cfiEnd = null,
            color = HighlightColor.DEFAULT,
            selectedText = "",
            noteText = "",
            chapter = record[AnnotationSchema.COL_CHAPTER].asString() ?: "",
            chapterIndex = locationOf(record)?.get("chapterDocIndex")?.asLong()?.toInt() ?: 0,
            percentage = record[AnnotationSchema.COL_PERCENTAGE].asString() ?: "0",
            range = null,
            tags = emptyList(),
            label = record[AnnotationSchema.COL_LABEL].asString() ?: "",
            createdAt = key.toLongOrNull() ?: 0L,
        )
    }

    // ── JSON-string convenience (backup round-trip) ──────────────────────

    /** Serialize any annotation to its desktop record as compact JSON. */
    fun encode(a: Annotation): String =
        toJsonString(
            when (a.kind) {
                AnnotationKind.BOOKMARK -> toBookmarkRecord(a)
                else -> toNoteRecord(a)
            },
        )

    /**
     * Decode a desktop record JSON, auto-detecting the table: a record with
     * a `chapterIndex` member is a `notes` row, otherwise a `bookmarks` row.
     */
    fun decode(json: String): Annotation {
        val record = parseJson(json) as? JsonValue.JsonObject
            ?: throw IllegalArgumentException("annotation record must be a JSON object")
        return if (record.entries.containsKey(AnnotationSchema.COL_CHAPTER_INDEX)) {
            fromNoteRecord(record)
        } else {
            fromBookmarkRecord(record)
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /** The CFI written to a Room `cfi` column: a range when an end exists. */
    private fun storageCfi(a: Annotation): String =
        if (a.cfiEnd != null) CfiAnchor.rangeCfi(a.cfiStart, a.cfiEnd) else a.cfiStart

    /**
     * Split a Room `cfi` column into (start, end): a range yields both
     * collapsed points, a point yields (point, null).
     */
    private fun splitStorageCfi(cfi: String): Pair<String, String?> =
        if (CfiAnchor.isRange(cfi)) {
            CfiAnchor.startPoint(cfi) to CfiAnchor.endPoint(cfi)
        } else if (CfiAnchor.isValid(cfi)) {
            CfiAnchor.requireValid(cfi) to null
        } else {
            cfi to null
        }

    private fun dateObject(a: Annotation): JsonValue.JsonObject {
        val parts = a.dateParts()
        return JsonValue.JsonObject(
            "year" to JsonValue.of(parts.year),
            "month" to JsonValue.of(parts.month),
            "day" to JsonValue.of(parts.day),
        )
    }

    /**
     * Build the desktop recordLocation JSON string stored in the `cfi`
     * column. Field names mirror GeneralRender's tempLocation
     * (cfi/percentage/chapterTitle/chapterDocIndex/text/...), with the
     * lossless `cfiEnd` extension for ranges.
     */
    private fun recordLocationJson(a: Annotation): String {
        val location = LinkedHashMap<String, JsonValue>()
        location["cfi"] = JsonValue.of(a.cfiStart)
        if (a.cfiEnd != null) location["cfiEnd"] = JsonValue.of(a.cfiEnd)
        location["percentage"] = JsonValue.of(a.percentage)
        location["chapterTitle"] = JsonValue.of(a.chapter)
        location["chapterDocIndex"] = JsonValue.of(a.chapterIndex)
        location["text"] = JsonValue.of(if (a.kind == AnnotationKind.BOOKMARK) a.label else a.selectedText)
        location["chapterHref"] = JsonValue.of("")
        location["count"] = JsonValue.of(0)
        location["page"] = JsonValue.of(0)
        return toJsonString(JsonValue.JsonObject(location))
    }

    /**
     * Resolve the position from a desktop `cfi` column. Accepts either a
     * recordLocation JSON string (real CFI in `.cfi`, optional `.cfiEnd`) or
     * a bare EPUB CFI string. Invalid CFIs are passed through unchanged so
     * the caller can flag them rather than silently dropping the annotation.
     */
    private fun extractCfi(value: JsonValue?): Pair<String, String?> {
        val raw = value.asString() ?: return ("" to null)
        val location = parseJsonOrNull(raw) as? JsonValue.JsonObject
        if (location != null) {
            val cfi = location["cfi"].asString()
            if (cfi != null) {
                val start = if (CfiAnchor.isValid(cfi)) CfiAnchor.requireValid(cfi) else cfi
                val rawEnd = location["cfiEnd"].asString()
                val end = rawEnd?.takeIf { CfiAnchor.isValid(it) }?.let { CfiAnchor.requireValid(it) }
                return start to end
            }
        }
        // Not a location object: treat the whole string as a bare CFI.
        val start = if (CfiAnchor.isValid(raw)) CfiAnchor.requireValid(raw) else raw
        return start to null
    }

    private fun locationOf(record: JsonValue.JsonObject): JsonValue.JsonObject? =
        parseJsonOrNull(record[AnnotationSchema.COL_CFI].asString() ?: "") as? JsonValue.JsonObject

    /** Desktop `color` may be a hex string or a numeric code; accept both. */
    private fun resolveColor(value: JsonValue?): HighlightColor =
        when (value) {
            is JsonValue.JsonStr -> HighlightColor.fromHex(value.value)
            is JsonValue.JsonNum -> HighlightColor.fromCode(value.value.toLong())
            else -> HighlightColor.DEFAULT
        }

    /** `tag` may be a JSON array value or a JSON-encoded string. */
    private fun decodeTagValue(value: JsonValue?): List<String> {
        when (value) {
            is JsonValue.JsonArray -> return value.items.mapNotNull { it.asString() }
            is JsonValue.JsonStr -> return decodeStringList(value.value)
            else -> return emptyList()
        }
    }

    private fun decodeStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val parsed = parseJsonOrNull(json) as? JsonValue.JsonArray ?: return emptyList()
        return parsed.items.mapNotNull { it.asString() }
    }

    private fun parseJsonOrNull(text: String): JsonValue? =
        try {
            parseJson(text)
        } catch (e: IllegalArgumentException) {
            null
        }
}
