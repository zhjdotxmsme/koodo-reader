package com.koodoreader.core.sync

/**
 * Deterministic JSON codec for [SyncSnapshot] (hand-written, no third-party JSON).
 */
object SyncSnapshotCodec {
    fun encode(snapshot: SyncSnapshot): String = SyncJson.write(snapshot)

    fun decode(text: String): SyncSnapshot {
        val version = SyncJson.version(text)
        if (version != 1) throw SyncVersionException(version)
        return SyncJson.readSnapshot(text)
    }
}

internal object SyncJson {
    fun version(text: String): Int {
        val key = "\"version\""
        val i = text.indexOf(key)
        if (i < 0) throw IllegalArgumentException("missing version")
        val colon = text.indexOf(':', i + key.length)
        val end = text.indexOf(',', colon).let { if (it < 0) text.indexOf('}', colon) else it }
        return text.substring(colon + 1, end).trim().toInt()
    }

    fun write(snapshot: SyncSnapshot): String = buildString {
        append('{')
        field("version", snapshot.version)
        comma()
        append("\"books\":")
        appendBooks(snapshot.books)
        comma()
        append("\"progress\":")
        appendProgress(snapshot.progress)
        comma()
        append("\"annotations\":")
        appendAnnotations(snapshot.annotations)
        comma()
        append("\"bookmarks\":")
        appendBookmarks(snapshot.bookmarks)
        comma()
        append("\"settings\":")
        appendSettings(snapshot.settings)
        comma()
        append("\"statistics\":")
        appendStatistics(snapshot.statistics)
        append('}')
    }

    fun readSnapshot(text: String): SyncSnapshot {
        fun sliceArray(name: String): String {
            val key = "\"$name\""
            val start = text.indexOf(key)
            require(start >= 0) { "missing $name" }
            val open = text.indexOf('[', start)
            require(open >= 0) { "missing [ for $name" }
            var depth = 0
            for (i in open until text.length) {
                when (text[i]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return text.substring(open + 1, i)
                    }
                }
            }
            error("unclosed array $name")
        }

        fun stringField(obj: String, key: String): String? {
            val needle = "\"$key\":"
            val i = obj.indexOf(needle)
            if (i < 0) return null
            val q = obj.indexOf('"', i + needle.length)
            if (q < 0) return null
            val end = obj.indexOf('"', q + 1)
            if (end < 0) return null
            return unescape(obj.substring(q + 1, end))
        }

        fun numberField(obj: String, key: String): String? {
            val re = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?|null)""")
            val m = re.find(obj) ?: return null
            val raw = m.groupValues[1]
            return if (raw == "null") null else raw
        }

        fun splitObjects(inner: String): List<String> {
            if (inner.isBlank()) return emptyList()
            val out = ArrayList<String>()
            var depth = 0
            var start = -1
            for (i in inner.indices) {
                when (inner[i]) {
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && start >= 0) {
                            out.add(inner.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
            return out
        }

        val books = splitObjects(sliceArray("books")).map { o ->
            BookSnapshot(
                bookKey = stringField(o, "bookKey") ?: "",
                name = stringField(o, "name"),
                author = stringField(o, "author"),
                md5 = stringField(o, "md5"),
                format = stringField(o, "format"),
            )
        }
        val progress = splitObjects(sliceArray("progress")).map { o ->
            ProgressSnapshot(
                bookKey = stringField(o, "bookKey") ?: "",
                cfi = stringField(o, "cfi"),
                fraction = numberField(o, "fraction")?.toDoubleOrNull(),
            )
        }
        val annotations = splitObjects(sliceArray("annotations")).map { o ->
            AnnotationSnapshot(
                key = stringField(o, "key") ?: "",
                bookKey = stringField(o, "bookKey"),
                chapter = stringField(o, "chapter"),
                text = stringField(o, "text"),
                notes = stringField(o, "notes"),
                color = numberField(o, "color")?.toLongOrNull(),
            )
        }
        val bookmarks = splitObjects(sliceArray("bookmarks")).map { o ->
            BookmarkSnapshot(
                key = stringField(o, "key") ?: "",
                bookKey = stringField(o, "bookKey"),
                cfi = stringField(o, "cfi"),
                label = stringField(o, "label"),
            )
        }
        val statistics = splitObjects(sliceArray("statistics")).map { o ->
            ReadingSessionSnapshot(
                key = stringField(o, "key") ?: "",
                bookKey = stringField(o, "bookKey") ?: "",
                day = stringField(o, "day") ?: "",
                seconds = numberField(o, "seconds")?.toLongOrNull() ?: 0L,
            )
        }
        return SyncSnapshot(
            version = 1,
            books = books,
            progress = progress,
            annotations = annotations,
            bookmarks = bookmarks,
            settings = readSettingsObject(text),
            statistics = statistics,
        )
    }

    private fun readSettingsObject(text: String): Map<String, String> {
        val key = "\"settings\""
        val i = text.indexOf(key)
        if (i < 0) return emptyMap()
        val open = text.indexOf('{', i)
        val close = text.indexOf('}', open)
        if (open < 0 || close < 0) return emptyMap()
        val inner = text.substring(open + 1, close).trim()
        if (inner.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val re = Regex("""\s*"([^"\\]+)"\s*:\s*"((?:[^"\\]|\\.)*)"\s*""")
        re.findAll(inner).forEach { m ->
            out[m.groupValues[1]] = unescape(m.groupValues[2])
        }
        return out
    }

    private fun StringBuilder.appendBooks(rows: List<BookSnapshot>) {
        append('[')
        rows.forEachIndexed { i, b ->
            if (i > 0) append(',')
            obj {
                str("bookKey", b.bookKey)
                optStr("name", b.name)
                optStr("author", b.author)
                optStr("md5", b.md5)
                optStr("format", b.format)
            }
        }
        append(']')
    }

    private fun StringBuilder.appendProgress(rows: List<ProgressSnapshot>) {
        append('[')
        rows.forEachIndexed { i, p ->
            if (i > 0) append(',')
            obj {
                str("bookKey", p.bookKey)
                optStr("cfi", p.cfi)
                if (p.fraction != null) {
                    comma()
                    append("\"fraction\":")
                    append(p.fraction)
                }
            }
        }
        append(']')
    }

    private fun StringBuilder.appendAnnotations(rows: List<AnnotationSnapshot>) {
        append('[')
        rows.forEachIndexed { i, a ->
            if (i > 0) append(',')
            obj {
                str("key", a.key)
                optStr("bookKey", a.bookKey)
                optStr("chapter", a.chapter)
                optStr("text", a.text)
                optStr("notes", a.notes)
                if (a.color != null) {
                    comma()
                    append("\"color\":")
                    append(a.color)
                }
            }
        }
        append(']')
    }

    private fun StringBuilder.appendBookmarks(rows: List<BookmarkSnapshot>) {
        append('[')
        rows.forEachIndexed { i, b ->
            if (i > 0) append(',')
            obj {
                str("key", b.key)
                optStr("bookKey", b.bookKey)
                optStr("cfi", b.cfi)
                optStr("label", b.label)
            }
        }
        append(']')
    }

    private fun StringBuilder.appendStatistics(rows: List<ReadingSessionSnapshot>) {
        append('[')
        rows.forEachIndexed { i, s ->
            if (i > 0) append(',')
            obj {
                str("key", s.key)
                comma()
                str("bookKey", s.bookKey)
                comma()
                str("day", s.day)
                comma()
                append("\"seconds\":")
                append(s.seconds)
            }
        }
        append(']')
    }

    private fun StringBuilder.appendSettings(map: Map<String, String>) {
        append('{')
        map.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) append(',')
            append('"')
            append(escape(k))
            append("\":")
            append('"')
            append(escape(v))
            append('"')
        }
        append('}')
    }

    private fun StringBuilder.field(name: String, value: Int) {
        append('"')
        append(name)
        append("\":")
        append(value)
    }

    private fun StringBuilder.comma() = append(',')

    private fun StringBuilder.obj(block: StringBuilder.() -> Unit) {
        append('{')
        block()
        append('}')
    }

    private fun StringBuilder.str(name: String, value: String) {
        append('"')
        append(name)
        append("\":")
        append('"')
        append(escape(value))
        append('"')
    }

    private fun StringBuilder.optStr(name: String, value: String?) {
        if (value == null) return
        if (length > 1 && this[length - 1] != '{') comma()
        str(name, value)
    }

    private fun escape(raw: String): String = buildString(raw.length) {
        for (c in raw) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    private fun unescape(raw: String): String = buildString(raw.length) {
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    '\\' -> append('\\')
                    '"' -> append('"')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    else -> append(raw[i + 1])
                }
                i += 2
            } else {
                append(raw[i])
                i++
            }
        }
    }
}
