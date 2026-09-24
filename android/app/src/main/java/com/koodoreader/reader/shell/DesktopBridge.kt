package com.koodoreader.reader.shell

import androidx.room.withTransaction
import com.koodoreader.core.data.KoodoDatabase
import com.koodoreader.core.data.entity.BookEntity
import com.koodoreader.core.data.entity.BookmarkEntity
import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.core.data.entity.PluginEntity
import com.koodoreader.core.data.entity.WordEntity
import com.koodoreader.core.dbio.BackupBundle
import com.koodoreader.core.dbio.BookRef
import com.koodoreader.core.dbio.CoverRef
import com.koodoreader.core.dbio.DataExport
import com.koodoreader.core.dbio.DataImport
import com.koodoreader.core.dbio.Row
import com.koodoreader.core.dbio.TableSource
import java.io.File
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Per-table outcome of an import (source = real/temp/empty/missing). */
data class TableReport(val source: String, val rows: Int, val imported: Int)

data class ImportReport(
    val tables: Map<String, TableReport>,
    val coversImported: Int,
    val booksImported: Int,
    val failures: List<String>,
)

data class ExportReport(
    val file: File,
    val tables: Map<String, Int>,
    val covers: Int,
    val books: Int,
)

/**
 * Bidirectional bridge between the native Room database (one file, five
 * tables) and the desktop backup layout (per-table `.db` files under
 * `config/` plus `cover/` and `book/` entries, as produced/consumed by
 * `src/utils/file/backup.ts` / `restore.ts`).
 *
 *  - desktop → native: all five tables upserted by primary key (same merge
 *    semantics as the desktop restore), covers and bundled book files
 *    copied into the app dirs (streamed, memory-bounded).
 *  - native → desktop: a ZIP the desktop "恢复" dialog accepts as-is
 *    (`config/config.json` required entry + exact desktop DDL per table).
 *    Book files are bundled so the desktop restore can satisfy `path`
 *    values pointing at the device's `<filesDir>/books` location.
 *
 * P7 additions (data export/import):
 *  - [noteRowForExport] / [wordRowForExport] map Room entities to the
 *    desktop CSV/JSON shape (exportType, split color, joined tags).
 *  - [applyImport] / [buildBookIndex] provide the inverse mapping. Both
 *    are pure functions (no IO); the caller wraps them in coroutines.
 */
object DesktopBridge {

    private const val BATCH = 50

    suspend fun importBackup(
        source: File,
        db: KoodoDatabase,
        booksDir: File,
        coverDir: File,
        fontsDir: File? = null,
    ): ImportReport = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        val reports = LinkedHashMap<String, TableReport>()
        var coverCount = 0
        var bookCount = 0
        BackupBundle.open(source).use { bundle ->
            // Bundled book files land at <booksDir>/<key>.<ext>; this map lets
            // the books rows' `path` be REWRITTEN to the local copy so the
            // imported books open natively (desktop rows carry desktop paths).
            val localBookFiles = bundle.bookFiles.associate { bf ->
                val dot = bf.name.lastIndexOf('.')
                val key = if (dot > 0) bf.name.substring(0, dot) else bf.name
                key to File(booksDir, sanitize(bf.name))
            }
            for (ts in bundle.tables) {
                val imported = importTable(ts, db, localBookFiles, failures)
                reports[ts.table] = TableReport(ts.source, ts.rows.size, imported)
            }
            for (c in bundle.covers) {
                val bytes = runCatching { bundle.coverBytes(c.name) }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    failures.add("cover ${c.name}: unreadable")
                    continue
                }
                val ok = runCatching { writeCover(coverDir, c.name, bytes) }
                if (ok.isFailure) failures.add("cover ${c.name}: ${ok.exceptionOrNull()}") else coverCount++
            }
            for ((key, dest) in localBookFiles) {
                val bf = bundle.bookFiles.firstOrNull { sanitize(it.name) == dest.name } ?: continue
                val ok = runCatching {
                    dest.parentFile?.mkdirs()
                    java.io.FileOutputStream(dest).use { bundle.bookStream(bf.name, it) }
                }
                if (ok.isFailure) {
                    failures.add("book ${bf.name}: ${ok.exceptionOrNull()}")
                } else {
                    bookCount++
                }
            }
            // Bundled fonts (desktop zips don't include fonts/ — backup.ts dir
            // list — so this only fires for custom/extended bundles).
            if (fontsDir != null) {
                for (ff in bundle.fontFiles) {
                    val dest = File(fontsDir, sanitize(ff.name))
                    val ok = runCatching {
                        dest.parentFile?.mkdirs()
                        java.io.FileOutputStream(dest).use { bundle.fontStream(ff.name, it) }
                    }
                    if (ok.isFailure) failures.add("font ${ff.name}: ${ok.exceptionOrNull()}")
                }
            }
        }
        ImportReport(reports, coverCount, bookCount, failures)
    }

    suspend fun exportBackup(
        outDir: File,
        db: KoodoDatabase,
        coverDir: File,
        timestamp: Long,
        booksDir: File? = null,
    ): ExportReport = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        val tables: Map<String, List<Row>> = mapOf(
            "books" to db.bookDao().observeAll().first().map { rowOf(it) },
            "notes" to db.noteDao().observeAll().first().map { rowOf(it) },
            "bookmarks" to db.bookmarkDao().observeAll().first().map { rowOf(it) },
            "plugins" to db.pluginDao().getAll().map { rowOf(it) },
            "words" to db.wordDao().observeAll().first().map { rowOf(it) },
        )
        val covers = coverDir.listFiles()
            .orEmpty()
            .filter { it.isFile }
            .map { CoverRef.of(it) } // streamed, not held in memory
        // P7: bundle book files when the device has them — the desktop restore
        // expects `book/<key>.<ext>` entries so imported rows' `path` resolves
        // locally instead of dangling on a desktop absolute path.
        val bookFiles: List<BookRef> = booksDir?.listFiles()
            .orEmpty()
            .filter { it.isFile }
            .map { BookRef.of(it) }
            ?: emptyList()
        // Desktop naming (backup.ts): KoodoReader-Backup-y-m-d-<epoch>.zip
        val out = File(outDir, "KoodoReader-Backup-local-$timestamp.zip")
        BackupBundle.write(out, tables, "{}", covers, bookFiles)
        ExportReport(out, tables.mapValues { it.value.size }, covers.size, bookFiles.size)
    }

    // ------------------------------------------------------------ table IO

    private suspend fun importTable(
        ts: TableSource,
        db: KoodoDatabase,
        localBookFiles: Map<String, File>,
        failures: MutableList<String>,
    ): Int {
        if (ts.rows.isEmpty()) return 0
        return try {
            when (ts.table) {
                "books" -> {
                    // Bundled book files win over desktop-absolute paths.
                    val rows = ts.rows.map { row ->
                        rowOfBook(row).withLocalPath(localBookFiles[str(row, "key")]?.absolutePath)
                    }
                    var n = 0
                    db.withTransaction {
                        for (chunk in rows.chunked(BATCH)) { db.bookDao().upsertAll(chunk); n += chunk.size }
                    }
                    n
                }
                "notes" -> {
                    val rows = ts.rows.map { rowOfNote(it) }
                    var n = 0
                    db.withTransaction {
                        for (chunk in rows.chunked(BATCH)) { db.noteDao().upsertAll(chunk); n += chunk.size }
                    }
                    n
                }
                "bookmarks" -> {
                    val rows = ts.rows.map { rowOfBookmark(it) }
                    var n = 0
                    db.withTransaction {
                        for (chunk in rows.chunked(BATCH)) { db.bookmarkDao().upsertAll(chunk); n += chunk.size }
                    }
                    n
                }
                "plugins" -> {
                    val rows = ts.rows.map { rowOfPlugin(it) }
                    var n = 0
                    db.withTransaction {
                        for (chunk in rows.chunked(BATCH)) { db.pluginDao().upsertAll(chunk); n += chunk.size }
                    }
                    n
                }
                "words" -> {
                    val rows = ts.rows.map { rowOfWord(it) }
                    var n = 0
                    db.withTransaction {
                        for (chunk in rows.chunked(BATCH)) { db.wordDao().upsertAll(chunk); n += chunk.size }
                    }
                    n
                }
                else -> throw IllegalArgumentException("unknown table ${ts.table}")
            }
        } catch (e: Exception) {
            failures.add("${ts.table}: ${e.message}")
            0
        }
    }

    // ------------------------------------------------------------- mappers

    private fun str(r: Row, col: String): String? = r[col] as? String
    private fun lng(r: Row, col: String): Long? = r[col] as? Long

    /** Point the row at the locally copied book file when the bundle carried one. */
    private fun BookEntity.withLocalPath(localPath: String?): BookEntity =
        if (localPath != null) copy(path = localPath) else this

    private fun rowOfBook(r: Row) = BookEntity(
        key = requireNotNull(str(r, "key")) { "books row without key" },
        name = str(r, "name"), author = str(r, "author"), description = str(r, "description"),
        md5 = str(r, "md5"), cover = str(r, "cover"), format = str(r, "format"),
        publisher = str(r, "publisher"), size = lng(r, "size"), page = lng(r, "page"),
        path = str(r, "path"), charset = str(r, "charset"),
    )

    private fun rowOfNote(r: Row) = NoteEntity(
        key = requireNotNull(str(r, "key")) { "notes row without key" },
        bookKey = str(r, "bookKey"), date = str(r, "date"), chapter = str(r, "chapter"),
        chapterIndex = lng(r, "chapterIndex"), text = str(r, "text"), cfi = str(r, "cfi"),
        range = str(r, "range"), notes = str(r, "notes"), percentage = str(r, "percentage"),
        color = lng(r, "color"), tag = str(r, "tag"),
    )

    private fun rowOfBookmark(r: Row) = BookmarkEntity(
        key = requireNotNull(str(r, "key")) { "bookmarks row without key" },
        bookKey = str(r, "bookKey"), cfi = str(r, "cfi"), label = str(r, "label"),
        percentage = str(r, "percentage"), chapter = str(r, "chapter"),
    )

    private fun rowOfPlugin(r: Row) = PluginEntity(
        key = requireNotNull(str(r, "key")) { "plugins row without key" },
        type = str(r, "type"), displayName = str(r, "displayName"), icon = str(r, "icon"),
        version = str(r, "version"), config = str(r, "config"), autoValue = str(r, "autoValue"),
        langList = str(r, "langList"), voiceList = str(r, "voiceList"),
        scriptSHA256 = str(r, "scriptSHA256"), script = str(r, "script"),
    )

    private fun rowOfWord(r: Row) = WordEntity(
        key = requireNotNull(str(r, "key")) { "words row without key" },
        bookKey = str(r, "bookKey"), date = str(r, "date"), word = str(r, "word"),
        sentence = str(r, "sentence"), chapter = str(r, "chapter"),
    )

    private fun rowOf(b: BookEntity): Row = mapOf(
        "key" to b.key, "name" to b.name, "author" to b.author, "description" to b.description,
        "md5" to b.md5, "cover" to b.cover, "format" to b.format, "publisher" to b.publisher,
        "size" to b.size, "page" to b.page, "path" to b.path, "charset" to b.charset,
    )

    private fun rowOf(n: NoteEntity): Row = mapOf(
        "key" to n.key, "bookKey" to n.bookKey, "date" to n.date, "chapter" to n.chapter,
        "chapterIndex" to n.chapterIndex, "text" to n.text, "cfi" to n.cfi, "range" to n.range,
        "notes" to n.notes, "percentage" to n.percentage, "color" to n.color, "tag" to n.tag,
    )

    private fun rowOf(m: BookmarkEntity): Row = mapOf(
        "key" to m.key, "bookKey" to m.bookKey, "cfi" to m.cfi, "label" to m.label,
        "percentage" to m.percentage, "chapter" to m.chapter,
    )

    private fun rowOf(p: PluginEntity): Row = mapOf(
        "key" to p.key, "type" to p.type, "displayName" to p.displayName, "icon" to p.icon,
        "version" to p.version, "config" to p.config, "autoValue" to p.autoValue,
        "langList" to p.langList, "voiceList" to p.voiceList,
        "scriptSHA256" to p.scriptSHA256, "script" to p.script,
    )

    private fun rowOf(w: WordEntity): Row = mapOf(
        "key" to w.key, "bookKey" to w.bookKey, "date" to w.date, "word" to w.word,
        "sentence" to w.sentence, "chapter" to w.chapter,
    )

    // --------------------------------------------------------- data export

    /**
     * Shape one [NoteEntity] for the desktop CSV/JSON exporter. The
     * `exportType` field is what the desktop `importData.ts` uses to
     * distinguish notes vs highlights (highlights have `notes` empty).
     */
    fun noteRowForExport(
        n: NoteEntity,
        books: Map<String, BookEntity>,
    ): DataExport.NoteRow {
        val book = books[n.bookKey]
        val isHighlight = n.notes.isNullOrEmpty()
        val parsedDate = n.date?.let { DataExport.parseDate(it) } ?: Date(0)
        return DataExport.NoteRow(
            key = n.key,
            bookKey = n.bookKey,
            bookName = book?.name,
            bookAuthor = book?.author,
            bookMd5 = book?.md5,
            chapter = n.chapter,
            chapterIndex = n.chapterIndex,
            text = n.text,
            notes = n.notes, // null for highlights (desktop contract)
            percentage = n.percentage,
            color = n.color,
            tag = parseTag(n.tag),
            date = parsedDate,
            exportType = if (isHighlight) DataExport.ExportType.HIGHLIGHT else DataExport.ExportType.NOTE,
        )
    }

    /** Shape one [WordEntity] for dictionary history CSV/JSON. */
    fun wordRowForExport(
        w: WordEntity,
        books: Map<String, BookEntity>,
    ): DataExport.WordRow {
        val book = books[w.bookKey]
        return DataExport.WordRow(
            key = w.key,
            bookKey = w.bookKey,
            bookName = book?.name,
            bookAuthor = book?.author,
            bookMd5 = book?.md5,
            chapter = w.chapter,
            word = w.word,
            sentence = w.sentence,
            date = w.date?.let { DataExport.parseDate(it) } ?: Date(0),
        )
    }

    /**
     * Reverse a `"a,b,c"` string back to a `List<String>`. Tags land in
     * Room as either a desktop JSON array string `["a","b"]` or a plain
     * comma list — both forms are accepted to keep parity with older
     * imports.
     */
    private fun parseTag(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val trimmed = raw.trim()
        // JSON array form (Kotlin writes `["a","b"]` for tag column)
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val inner = trimmed.substring(1, trimmed.length - 1)
            if (inner.isBlank()) return emptyList()
            return inner.split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
        }
        return trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Build a [DataImport.BookIndex] once per import call so `bookMd5`
     * resolution is O(1) per row.
     */
    fun buildBookIndex(books: List<BookEntity>): DataImport.BookIndex {
        val byKey = HashMap<String, String>()
        val byMd5 = HashMap<String, String>()
        books.forEach { b ->
            byKey[b.key] = b.name ?: ""
            // `md5` comes from :core:data, i.e. another module, so its smart cast is
            // not available — the null check has to go through a local val.
            val md5 = b.md5
            if (!md5.isNullOrEmpty()) byMd5[md5] = b.key
        }
        return DataImport.BookIndex(byKey, byMd5)
    }

    /**
     * Persist freshly decoded CSV/JSON rows back into Room. Pure mapping,
     * no IO — caller decides the dispatcher.
     */
    suspend fun applyImport(fresh: List<DataImport.DecodedRow>, db: KoodoDatabase): Int {
        var inserted = 0
        db.withTransaction {
            // Split per-table — same merge-by-primary-key semantics as
            // desktop `importNotesData` (`saveRecord` skips duplicates).
            val noteRows = mutableListOf<NoteEntity>()
            val wordRows = mutableListOf<WordEntity>()
            fresh.forEach { d ->
                when (d.exportType) {
                    DataImport.ExportType.NOTE,
                    DataImport.ExportType.HIGHLIGHT -> noteRows.add(noteFromImport(d))
                    DataImport.ExportType.DICTIONARY_HISTORY -> wordRows.add(wordFromImport(d))
                    DataImport.ExportType.UNKNOWN -> Unit
                }
            }
            if (noteRows.isNotEmpty()) {
                db.noteDao().upsertAll(noteRows); inserted += noteRows.size
            }
            if (wordRows.isNotEmpty()) {
                db.wordDao().upsertAll(wordRows); inserted += wordRows.size
            }
        }
        return inserted
    }

    private fun noteFromImport(d: DataImport.DecodedRow): NoteEntity = NoteEntity(
        key = d.raw["key"].orEmpty(),
        bookKey = d.raw["bookKey"].orEmpty().ifBlank { null },
        date = d.raw["date"]?.takeIf { it.isNotEmpty() },
        chapter = d.raw["chapter"]?.takeIf { it.isNotEmpty() },
        chapterIndex = d.raw["chapterIndex"]?.toLongOrNull(),
        text = d.raw["text"]?.takeIf { it.isNotEmpty() },
        cfi = d.raw["cfi"]?.takeIf { it.isNotEmpty() },
        range = d.raw["range"]?.takeIf { it.isNotEmpty() },
        notes = d.raw["notes"]?.takeIf { it.isNotEmpty() },
        percentage = d.raw["percentage"]?.takeIf { it.isNotEmpty() },
        color = d.raw["color"]?.let { rejoinColor(d.raw["styleType"], it) },
        tag = serializeTag(d.raw["tag"]),
    )

    private fun wordFromImport(d: DataImport.DecodedRow): WordEntity = WordEntity(
        key = d.raw["key"].orEmpty(),
        bookKey = d.raw["bookKey"].orEmpty().ifBlank { null },
        date = d.raw["date"]?.takeIf { it.isNotEmpty() },
        word = d.raw["word"]?.takeIf { it.isNotEmpty() },
        sentence = d.raw["sentence"]?.takeIf { it.isNotEmpty() },
        chapter = d.raw["chapter"]?.takeIf { it.isNotEmpty() },
    )

    /** Pack `styleType-#RRGGBB` back to a Long for Room (parity with `splitColor`). */
    private fun rejoinColor(styleType: String?, color: String?): Long? {
        if (color.isNullOrBlank()) return null
        val style = when (styleType?.lowercase()) {
            "background" -> 0x10000000L.toLong()
            "underline" -> 0x20000000L.toLong()
            "bold" -> 0x30000000L.toLong()
            "italic" -> 0x40000000L.toLong()
            else -> 0x10000000L.toLong()
        }
        val rgb = when {
            color.startsWith("#") && color.length == 7 -> color.substring(1).toLong(16)
            else -> color.toLongOrNull() ?: 0L
        }
        return style or (rgb and 0xFFFFFFL)
    }

    /** JSON-array-tag the desktop stores; we mirror that exactly so the
     *  next export preserves the literal shape. */
    private fun serializeTag(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val parts = parseTag(raw)
        if (parts.isEmpty()) return null
        return parts.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
    }

    // ---------------------------------------------------------------- utils

    /** Cover file name safety: `<key>.<ext>` convention, no path traversal. */
    private fun writeCover(coverDir: File, name: String, bytes: ByteArray): File {
        require(!name.contains('/') && !name.contains('\\') && name.length in 1..200) {
            "unsafe cover name: $name"
        }
        coverDir.mkdirs()
        val dest = File(coverDir, name)
        java.io.FileOutputStream(dest).use { it.write(bytes) }
        return dest
    }

    private fun sanitize(name: String): String {
        require(name.length in 1..255 && name.none { it == '/' || it == '\\' }) {
            "unsafe book name: $name"
        }
        return name
    }
}
