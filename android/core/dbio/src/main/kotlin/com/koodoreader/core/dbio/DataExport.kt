package com.koodoreader.core.dbio

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Desktop CSV/JSON data exporter — mirror of the desktop
 * `src/utils/file/export.ts` (notes/highlights/dictionaryHistory sections).
 *
 * Desktop-parity export:
 *  - emits `exportType` (note | highlight | dictionaryHistory) on every row
 *  - `tag` joins with commas (desktop split/join contract)
 *  - `date` is `YYYY-MM-DD` (the desktop `i18n`-aware formatter lowers to
 *    this exact shape, regardless of locale)
 *  - `color` is split into `styleType` + raw hex; numeric IDs from the DB
 *    are converted via the [HighlightColor] enum table (the desktop stores
 *    `styleType-color` strings natively; the integer form is the packed
 *    middle ground used by the Kotlin `NoteEntity.color: Long?`).
 *  - when multiple books are involved the export is a zip with `all.<ext>`
 *    plus one file per book; single-book exports write one bare file
 *
 * Pure JVM: no Android dependency, no third-party CSV/JSON libs (the
 * encoded formats are tiny; pulling in a library would be overkill and
 * break platform parity if the desktop's parser drifted).
 */
object DataExport {

    enum class Format(val extension: String) {
        CSV("csv"), JSON("json"), MD("md"), TXT("txt"), HTML("html");

        companion object {
            fun parse(raw: String): Format = when (raw.lowercase()) {
                "csv" -> CSV
                "json" -> JSON
                "md", "markdown" -> MD
                "txt" -> TXT
                "html" -> HTML
                else -> CSV
            }
        }
    }

    enum class ExportType(val wire: String) {
        NOTE("note"),
        HIGHLIGHT("highlight"),
        DICTIONARY_HISTORY("dictionaryHistory");
    }

    /**
     * One row of the desktop `notes` table (desktop `Note` type). Stored as
     * a map so any source that already yields `Row`-shaped data works.
     *
     * Identifiers are nullable only because the desktop lets a user export
     * before clearing the database; payloads (text/word/sentence) are
     * always present in practice.
     */
    data class NoteRow(
        val key: String,
        val bookKey: String?,
        val bookName: String?,
        val bookAuthor: String?,
        val bookMd5: String?,
        val chapter: String?,
        val chapterIndex: Long?,
        val text: String?,
        val notes: String?, // null for highlights
        val percentage: String?,
        val color: Long?,
        val tag: List<String>,
        val date: Date,
        val exportType: ExportType,
    )

    /** One row of the desktop `words` (dictionary history) table. */
    data class WordRow(
        val key: String,
        val bookKey: String?,
        val bookName: String?,
        val bookAuthor: String?,
        val bookMd5: String?,
        val chapter: String?,
        val word: String?,
        val sentence: String?,
        val date: Date,
        val exportType: ExportType = ExportType.DICTIONARY_HISTORY,
    )

    // ---------------------------------------------------------- public IO

    /**
     * Encode a list of rows in [format]. The header columns are the union
     * of every field on every row so the desktop's `papaparse`/json import
     * restores the entire payload column-by-column. Empty rows shorten the
     * header to the active subset (matters for single-row exports).
     *
     * Returns the encoded text — caller writes it where it wants (single
     * file, one of many in a zip, an SD card, a picker Intent).
     */
    fun encode(records: List<NoteRow>, format: Format): String {
        require(records.isNotEmpty()) { "no records to encode" }
        val flat = records.map { rowOf(it) }
        return when (format) {
            Format.JSON -> flat.joinToString(
                prefix = "[", postfix = "]", separator = ",\n",
            ) { jsonEncode(it) }
            Format.CSV -> encodeCsv(flat)
            Format.MD -> convertNotesToMarkdown(flat)
            Format.TXT -> convertNotesToTxt(flat)
            Format.HTML -> convertNotesToHTML(flat)
        }
    }

    /** Encode words/dictionary-history rows in [format]. */
    fun encodeWords(records: List<WordRow>, format: Format): String {
        require(records.isNotEmpty()) { "no words to encode" }
        val flat = records.map { rowOf(it) }
        return when (format) {
            Format.JSON -> flat.joinToString(
                prefix = "[", postfix = "]", separator = ",\n",
            ) { jsonEncode(it) }
            Format.CSV -> encodeCsv(flat)
            else -> encodeCsv(flat)
        }
    }

    // ---------------------------------------------------------- helpers

    /**
     * Split a stored `styleType-#RRGGBB` string OR a numeric packed ID back
     * into its `(styleType, color)` pair (desktop parity: same arithmetic
     * the desktop `HighlightUtil.convertNumberToHighlightValue` does). When
     * the value is already split (desktop native export), pass it through
     * untouched.
     */
    fun splitColor(stored: Any?): Pair<String, String> {
        val raw = stored?.toString().orEmpty()
        if (raw.isEmpty()) return "background" to "#FEF3CD"
        // Already split ("background-#FEF3CD")
        if (raw.contains('-') && !raw.startsWith("#")) {
            val dash = raw.indexOf('-')
            val head = raw.substring(0, dash)
            val tail = raw.substring(dash + 1)
            if (tail.startsWith("#") || tail.length in 4..9) {
                return head to tail
            }
        }
        // Numeric packed: 0xSTTT.... — the desktop packs styleType (top
        // byte-family) + hue into one int. Tables vary by version, so we
        // fall back to a safe default for unknown values rather than guessing.
        // Force 32-bit mask: Long literals > 0x80000000 promote to negative
        // signed Longs, and a bitwise AND between a 32-bit colour and a
        // 64-bit mask would shift the comparison.
        val asInt = when (stored) {
            is Long -> stored.toInt()
            is Int -> stored
            else -> raw.toLongOrNull()?.toInt() ?: return "background" to "#FEF3CD"
        }
        val styleType = when ((asInt.toLong() and 0xFFFFFFFFL) ushr 28) {
            1L -> "background"
            2L -> "underline"
            3L -> "bold"
            4L -> "italic"
            else -> "background"
        }
        val rgb = asInt and 0x00FFFFFF
        val hex = String.format(Locale.ROOT, "#%06X", rgb)
        return styleType to hex
    }

    private fun rowOf(n: NoteRow): Map<String, Any?> {
        val (styleType, color) = splitColor(n.color)
        return linkedMapOf(
            "key" to n.key,
            "bookKey" to (n.bookKey ?: ""),
            "bookName" to (n.bookName ?: "Unknown book"),
            "bookAuthor" to (n.bookAuthor ?: "Unknown author"),
            "bookMd5" to (n.bookMd5 ?: ""),
            "date" to formatDate(n.date),
            "chapter" to (n.chapter ?: ""),
            "chapterIndex" to (n.chapterIndex ?: 0L),
            "text" to (n.text ?: ""),
            "notes" to (n.notes ?: ""),
            "percentage" to (n.percentage ?: "0"),
            "color" to color,
            "styleType" to styleType,
            "tag" to n.tag.joinToString(","),
            "exportType" to n.exportType.wire,
        )
    }

    private fun rowOf(w: WordRow): Map<String, Any?> = linkedMapOf(
        "key" to w.key,
        "bookKey" to (w.bookKey ?: ""),
        "bookName" to (w.bookName ?: "Unknown book"),
        "bookAuthor" to (w.bookAuthor ?: "Unknown author"),
        "bookMd5" to (w.bookMd5 ?: ""),
        "date" to formatDate(w.date),
        "chapter" to (w.chapter ?: ""),
        "word" to (w.word ?: ""),
        "sentence" to (w.sentence ?: ""),
        "exportType" to w.exportType.wire,
    )

    /**
     * Desktop CSV uses `\ufeff` (BOM) so Excel opens UTF-8 cleanly; field
     * quoting matches the import-side parser: any cell containing `,` or
     * `"` is wrapped in `"…"` with `"` doubled. The header row is always
     * emitted even for an empty list (the importer fails loudly if missing).
     */
    internal fun encodeCsv(rows: List<Map<String, Any?>>): String {
        if (rows.isEmpty()) return "\ufeff"
        val header = rows.first().keys.toList()
        val sb = StringBuilder("\ufeff")
        sb.append(header.joinToString(",")).append('\n')
        rows.forEach { row ->
            sb.append(header.joinToString(",") { col ->
                val v = row[col]?.toString() ?: ""
                if (v.any { it == ',' || it == '"' || it == '\n' }) {
                    "\"" + v.replace("\"", "\"\"") + "\""
                } else v
            }).append('\n')
        }
        return sb.toString()
    }

    /**
     * JSON encode a single row without reflection. Strings escape
     * backslash/quote/control; numerics emit raw digits; null maps to the
     * literal `null` token. Numbers never appear quoted.
     *
     * Output is COMPACT single-line JSON (`{"k":v,...}`): whitespace is not part of
     * the desktop contract — `JSON.parse` / the native [DataImport] parser accept both
     * — and an export can carry thousands of rows.
     */
    internal fun jsonEncode(row: Map<String, Any?>): String {
        fun enc(v: Any?): String = when (v) {
            null -> "null"
            is Boolean -> v.toString()
            is Long, is Int, is Double, is Float -> v.toString()
            else -> "\"" + v.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\""
        }
        return row.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${k.replace("\"", "\\\"")}\":${enc(v)}"
        }
    }

    /** Desktop `Date` formatter: `YYYY-MM-DD` (the desktop emits this). */
    private fun formatDate(date: Date): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        cal.time = date
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** Same formatter available as a public utility for round-trip tests. */
    fun parseDate(value: String): Date? {
        val parts = value.trim().split("-")
        if (parts.size < 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        cal.set(y, m - 1, d, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }

    /**
     * Group flat rows by `bookName` — same algorithm as desktop
     * `groupByBook`. Returns `bookName -> records in original order`.
     */
    fun <T> groupByBook(records: List<T>, name: (T) -> String?): LinkedHashMap<String, List<T>> {
        val out = LinkedHashMap<String, MutableList<T>>()
        records.forEach { r ->
            val key = name(r) ?: "Unknown book"
            out.getOrPut(key) { mutableListOf() }.add(r)
        }
        return out.mapValues { it.value.toList() }
            as LinkedHashMap<String, List<T>>
    }

    // ---------------------------------------------------------- md/txt/html
    // These match the desktop's `convertNotesToMarkdown/Txt/HTML` shape one
    // for one; we keep them inline (small) so test fixtures stay readable.

    internal fun convertNotesToMarkdown(rows: List<Map<String, Any?>>): String {
        val groups = groupByBook(rows) { it["bookName"] as String? }
        val sb = StringBuilder("# Koodo Reader - Notes\n\n")
        groups.forEach { (bookName, bookRows) ->
            val author = bookRows.first()["bookAuthor"] ?: "Unknown author"
            sb.append("## ").append(bookName).append("\n\n")
            sb.append("*").append(author).append("*\n\n")
            bookRows.forEach { r ->
                val chapter = r["chapter"]?.toString().orEmpty()
                val text = r["text"]?.toString().orEmpty()
                val note = r["notes"]?.toString().orEmpty()
                if (chapter.isNotEmpty()) sb.append("### ").append(chapter).append("\n\n")
                if (text.isNotEmpty()) sb.append("> ").append(text.replace("\n", "\n> ")).append("\n\n")
                if (note.isNotEmpty()) sb.append("**Note:** ").append(note).append("\n\n")
                val meta = mutableListOf<String>()
                r["date"]?.toString()?.takeIf { it.isNotEmpty() }?.let { meta.add("Date: $it") }
                r["color"]?.toString()?.takeIf { it.isNotEmpty() }?.let { meta.add("Color: $it") }
                r["tag"]?.toString()?.takeIf { it.isNotEmpty() }?.let { meta.add("Tags: $it") }
                if (meta.isNotEmpty()) sb.append("*").append(meta.joinToString(" | ")).append("*\n\n")
                sb.append("---\n\n")
            }
        }
        return sb.toString()
    }

    internal fun convertNotesToTxt(rows: List<Map<String, Any?>>): String {
        val groups = groupByBook(rows) { it["bookName"] as String? }
        val sb = StringBuilder("Koodo Reader - Notes\n").append("=".repeat(40)).append("\n\n")
        groups.forEach { (bookName, bookRows) ->
            val author = bookRows.first()["bookAuthor"] ?: "Unknown author"
            sb.append("Book: ").append(bookName).append('\n')
            sb.append("Author: ").append(author).append('\n')
            sb.append("-".repeat(40)).append("\n\n")
            bookRows.forEach { r ->
                fun add(label: String, key: String) {
                    val v = r[key]?.toString().orEmpty()
                    if (v.isNotEmpty()) sb.append(label).append(": ").append(v).append('\n')
                }
                add("Chapter", "chapter")
                add("Text", "text")
                add("Note", "notes")
                add("Date", "date")
                add("Color", "color")
                add("Tags", "tag")
                sb.append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    internal fun convertNotesToHTML(rows: List<Map<String, Any?>>): String {
        val groups = groupByBook(rows) { it["bookName"] as String? }
        val body = StringBuilder("<h1>Koodo Reader - Notes</h1>\n")
        groups.forEach { (bookName, bookRows) ->
            val author = bookRows.first()["bookAuthor"] ?: "Unknown author"
            body.append("<h2>").append(escape(bookName)).append("</h2>\n")
            body.append("<p><em>").append(escape(author.toString())).append("</em></p>\n")
            bookRows.forEach { r ->
                val chapter = r["chapter"]?.toString().orEmpty()
                val text = r["text"]?.toString().orEmpty()
                val note = r["notes"]?.toString().orEmpty()
                if (chapter.isNotEmpty()) body.append("<h3>").append(escape(chapter)).append("</h3>\n")
                if (text.isNotEmpty()) body.append("<blockquote>").append(escape(text)).append("</blockquote>\n")
                if (note.isNotEmpty()) body.append("<div class=\"note\"><strong>Note:</strong> ")
                    .append(escape(note)).append("</div>\n")
                val meta = mutableListOf<String>()
                r["date"]?.toString()?.takeIf { it.isNotEmpty() }?.let { meta.add("Date: $it") }
                r["color"]?.toString()?.takeIf { it.isNotEmpty() }?.let { meta.add("Color: $it") }
                r["tag"]?.toString()?.takeIf { it.isNotEmpty() }?.let { meta.add("Tags: $it") }
                if (meta.isNotEmpty()) {
                    body.append("<p class=\"meta\">").append(meta.joinToString(" &nbsp;|&nbsp; "))
                        .append("</p>\n")
                }
                body.append("<hr />\n")
            }
        }
        return """<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8"><title>Koodo Reader - Notes</title>
<style>body{font-family:Georgia,serif;max-width:860px;margin:40px auto;padding:0 20px;color:#222;line-height:1.7}
h1{font-size:1.8em;border-bottom:2px solid #444;padding-bottom:8px}
h2{font-size:1.4em;margin-top:2em;color:#333}
h3{font-size:1.1em;color:#555;margin-top:1.2em}
blockquote{border-left:4px solid #aaa;margin:.8em 0;padding:6px 16px;background:#f9f9f9;color:#444}
.note{background:#fffbe6;border-left:4px solid #f5c518;padding:6px 14px;margin:6px 0}
.meta{font-size:.85em;color:#888;margin:4px 0 10px}
hr{border:none;border-top:1px solid #ddd;margin:16px 0}</style></head>
<body>${body}</body></html>"""
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#039;")

    // ---------------------------------------------------------- util

    /** Mirror of the desktop timestamp suffix used in the filename. */
    fun fileTimestamp(now: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("yyyy-M-d", Locale.US)
        val date = fmt.format(Date(now))
        return "$date-$now"
    }

    /** Default zip name for a multi-book export (matches desktop). */
    fun zipFileName(type: String, format: Format, now: Long = System.currentTimeMillis()): String =
        "KoodoReader-$type-${fileTimestamp(now)}-${format.extension.uppercase()}.zip"

    /**
     * Sanitise a file name the same way the desktop does for per-book CSV
     * files (Windows-illegal chars mapped to `_`).
     */
    fun sanitiseFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    /**
     * Write a desktop-parity zip of `[bookname.<ext>, all.<ext>]` when
     * multiple books are involved, or a single bare file when only one.
     * Returns the file written.
     */
    fun writeExport(
        outDir: File,
        records: List<NoteRow>,
        format: Format,
        type: String, // "Note" | "Highlight"
        now: Long = System.currentTimeMillis(),
    ): File {
        outDir.mkdirs()
        val text = encode(records, format)
        val groups = groupByBook(records) { it.bookName }
        return if (groups.size > 1) {
            val zipName = zipFileName(type, format, now)
            val zipFile = File(outDir, zipName)
            java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
                if (format != Format.HTML) {
                    zos.putNextEntry(java.util.zip.ZipEntry("all.${format.extension}"))
                    zos.write(text.toByteArray())
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(java.util.zip.ZipEntry("all.html"))
                    zos.write(text.toByteArray())
                    zos.closeEntry()
                }
                groups.forEach { (bookName, bookRows) ->
                    val safeName = sanitiseFileName(bookName) + "." + format.extension
                    val body = encode(bookRows, format)
                    zos.putNextEntry(java.util.zip.ZipEntry(safeName))
                    zos.write(body.toByteArray())
                    zos.closeEntry()
                }
            }
            zipFile
        } else {
            val bare = "KoodoReader-$type-${fileTimestamp(now)}.${format.extension}"
            val out = File(outDir, bare)
            out.writeText(text, Charsets.UTF_8)
            out
        }
    }
}
