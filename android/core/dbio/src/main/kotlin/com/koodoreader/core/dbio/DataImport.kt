package com.koodoreader.core.dbio

import java.io.File

/**
 * Desktop CSV/JSON data importer — mirror of the desktop
 * `src/utils/file/importData.ts` (`importNotesData` flow).
 *
 * Native tracks accept:
 *  - one or more `.csv` / `.json` files
 *  - every row carries `exportType ∈ {note, highlight, dictionaryHistory}`
 *  - `bookKey`/`bookMd5` resolve against a caller-supplied book index
 *  - missing books surface a `missing` set; callers decide whether to abort
 *
 * Pure JVM (no Android dep). Encoding tolerant: BOM, CRLF, quoted CSV
 * fields with `""` escape, JSON deep arrays/objects preserved as raw
 * strings (the desktop treats `tag` as a stringified JSON array).
 */
object DataImport {

    enum class ExportType { NOTE, HIGHLIGHT, DICTIONARY_HISTORY, UNKNOWN;
        companion object {
            fun of(raw: String?): ExportType = when (raw?.lowercase()) {
                "note" -> NOTE
                "highlight" -> HIGHLIGHT
                "dictionaryhistory" -> DICTIONARY_HISTORY
                else -> UNKNOWN
            }
        }
    }

    data class DecodedRow(
        val exportType: ExportType,
        val raw: Map<String, String>,
    )

    /**
     * Book match info the importer uses to rewrite `bookKey` from `bookMd5`
     * when the source row points at an unknown key (the desktop does the
     * same in `importNotesData`).
     */
    data class BookIndex(
        val byKey: Map<String, String>, // key -> name
        val byMd5: Map<String, String>, // md5 -> key
    )

    data class Outcome(
        val records: List<DecodedRow>,
        val resolvedKeys: List<String>, // bookKey after rewriting
        val missingBookNames: List<String>,
        val importedCount: Int,
        val skippedCount: Int,
    )

    /** Read text from disk (BOM tolerated). Returns null on read failure. */
    fun readText(file: File): String? = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()

    /** Detect by extension; CSV takes precedence when the content is empty. */
    fun decodeFile(name: String, content: String): List<DecodedRow> {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "json" -> decodeJson(content)
            "csv" -> decodeCsv(content)
            else -> decodeCsv(content) // mirror: desktop falls back to CSV
                .ifEmpty { decodeJson(content) }
        }
    }

    /**
     * Parse a CSV string. Strips the BOM, splits on `\n`, accepts `\r` as a
     * line terminator, parses `"…"` fields with `""` escape and embedded
     * commas. The header row must contain `key`; missing → empty list.
     *
     * Why no `kotlin-csv` dep: the desktop parser is in `export.ts` and we
     * need identical edge-case behaviour (BOM, CRLF, empty cells).
     */
    internal fun decodeCsv(content: String): List<DecodedRow> {
        val text = if (content.isNotEmpty() && content[0] == '\ufeff') content.substring(1) else content
        val rows = parseCsv(text)
        if (rows.size < 2) return emptyList()
        val header = rows[0].map { it.trim() }
        if (!header.contains("key")) return emptyList()
        return rows.drop(1).map { cells ->
            val obj = HashMap<String, String>()
            header.forEachIndexed { idx, name ->
                if (name.isNotEmpty()) obj[name] = cells.getOrElse(idx) { "" }
            }
            DecodedRow(ExportType.of(obj["exportType"]), obj)
        }
    }

    /** Parse a JSON array of objects; return empty on non-array/non-object. */
    internal fun decodeJson(content: String): List<DecodedRow> {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed[0] != '[') return emptyList()
        // Counted by hand: we only need the top-level array shape and string
        // values; a tiny recursive descent parser avoids pulling kotlinx-serialization
        // into a pure JVM test module and matches desktop token-by-token semantics.
        val parser = JsonParser(trimmed)
        val items = parser.parseArray() ?: return emptyList()
        return items.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val str = HashMap<String, String>()
            map.forEach { (k, v) ->
                str[k?.toString() ?: ""] = jsonStringify(v)
            }
            DecodedRow(ExportType.of(str["exportType"]), str)
        }
    }

    /**
     * For every decoded row, attach a bookKey (rewriting from md5 when
     * possible) and collect books the importer cannot find. Caller decides
     * whether `missingBookNames` is a hard or soft failure (the desktop
     * shows a `vex` dialog and aborts).
     */
    fun resolveBooks(rows: List<DecodedRow>, books: BookIndex): Pair<List<DecodedRow>, List<String>> {
        val missing = LinkedHashSet<String>()
        val out = rows.map { row ->
            val md5 = row.raw["bookMd5"].orEmpty()
            val key = row.raw["bookKey"].orEmpty()
            val resolvedKey: String = when {
                key.isNotEmpty() && books.byKey[key] != null -> key
                md5.isNotEmpty() && books.byMd5[md5] != null -> books.byMd5.getValue(md5)
                else -> key
            }
            val known = resolvedKey.isNotEmpty() && books.byKey[resolvedKey] != null
            if (!known) {
                val name = row.raw["bookName"].orEmpty().ifBlank { "Unknown book" }
                missing.add(name)
            }
            row.copy(
                raw = row.raw.toMutableMap().apply {
                    put("bookKey", resolvedKey)
                },
            )
        }
        return out to missing.toList()
    }

    /**
     * Deduplicate by primary key (`key` column) against an existing-key set.
     * Desktop does the same one-pass: existing → skip, fresh → import.
     */
    fun dedupe(rows: List<DecodedRow>, existingKeys: Set<String>): Pair<List<DecodedRow>, Int> {
        val seen = HashSet<String>()
        var skipped = 0
        val out = mutableListOf<DecodedRow>()
        rows.forEach { row ->
            val key = row.raw["key"].orEmpty()
            if (key.isEmpty() || !seen.add(key) || existingKeys.contains(key)) {
                if (key.isNotEmpty() && existingKeys.contains(key)) skipped++
                return@forEach
            }
            out.add(row)
        }
        return out to skipped
    }

    // ----------------------------------------------------- parser helpers

    /**
     * Convert a CSV blob into rows. Tolerant of trailing blank lines and
     * mixed line endings. Returns the rows with their trailing blank
     * filtered out (same as the desktop `parseCsvText`).
     */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        var field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"'); i += 2
                    }
                    c == '"' -> { inQuotes = false; i++ }
                    else -> { field.append(c); i++ }
                }
            } else when (c) {
                '"' -> { inQuotes = true; i++ }
                ',' -> { row.add(field.toString()); field = StringBuilder(); i++ }
                '\r' -> { i++ }
                '\n' -> {
                    row.add(field.toString()); field = StringBuilder()
                    if (row.isNotEmpty() || rows.isNotEmpty()) rows.add(row)
                    row = mutableListOf()
                    i++
                }
                else -> { field.append(c); i++ }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString()); rows.add(row)
        }
        return rows.filter { it.size > 1 || it.firstOrNull() != "" }
    }

    /** Tiny recursive-descent JSON parser sufficient for desktop exports. */
    private class JsonParser(private val src: String) {
        private var i = 0
        fun parseArray(): List<Any?>? {
            skipWs()
            if (peek() != '[') return null
            consume('[')
            val out = mutableListOf<Any?>()
            skipWs()
            if (peek() == ']') { consume(']'); return out }
            while (true) {
                skipWs()
                out.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> { consume(','); continue }
                    ']' -> { consume(']'); return out }
                    else -> return null
                }
            }
        }

        private fun parseValue(): Any? {
            skipWs()
            return when (val c = peek()) {
                '"' -> parseString()
                '[' -> parseArrayValue()
                '{' -> parseObject()
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else -> if (c == '-' || c in '0'..'9') parseNumber() else null
            }
        }

        private fun parseArrayValue(): List<Any?> {
            consume('[')
            val out = mutableListOf<Any?>()
            skipWs()
            if (peek() == ']') { consume(']'); return out }
            while (true) {
                skipWs(); out.add(parseValue()); skipWs()
                when (peek()) {
                    ',' -> { consume(','); continue }
                    ']' -> { consume(']'); return out }
                    else -> return out
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            consume('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { consume('}'); return out }
            while (true) {
                skipWs()
                val k = parseString()
                skipWs()
                consume(':')
                out[k] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> { consume(','); continue }
                    '}' -> { consume('}'); return out }
                    else -> return out
                }
            }
        }

        private fun parseString(): String {
            consume('"')
            val sb = StringBuilder()
            while (i < src.length) {
                val c = src[i]
                if (c == '\\') {
                    val next = src.getOrNull(i + 1) ?: break
                    sb.append(when (next) {
                        '"' -> '"'; '\\' -> '\\'; '/' -> '/'
                        'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000C'
                        'u' -> {
                            val hex = src.substring(i + 2, i + 6)
                            i += 4
                            hex.toInt(16).toChar()
                        }
                        else -> next
                    })
                    i += 2
                } else if (c == '"') {
                    i++; return sb.toString()
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }

        private fun parseBool(): Boolean {
            return if (src.startsWith("true", i)) { i += 4; true }
            else if (src.startsWith("false", i)) { i += 5; false }
            else false
        }

        private fun parseNull(): Any? {
            return if (src.startsWith("null", i)) { i += 4; null } else null
        }

        private fun parseNumber(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < src.length && (src[i].isDigit() || src[i] == '.' || src[i] == 'e' || src[i] == 'E' || src[i] == '+' || src[i] == '-')) {
                i++
            }
            val text = src.substring(start, i)
            return if (text.contains('.') || text.contains('e') || text.contains('E')) text.toDouble()
            else text.toLong()
        }

        private fun peek(): Char? = src.getOrNull(i)
        private fun consume(c: Char) {
            if (peek() == c) i++ else error("expected '$c' at $i")
        }

        private fun skipWs() {
            while (i < src.length && src[i].isWhitespace()) i++
        }
    }

    private fun jsonStringify(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Boolean -> if (v) "true" else "false"
        is Long, is Int -> v.toString()
        is Double, is Float -> v.toString()
        else -> v.toString()
    }
}
