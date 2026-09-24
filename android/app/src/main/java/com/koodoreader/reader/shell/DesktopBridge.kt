package com.koodoreader.reader.shell

import androidx.room.withTransaction
import com.koodoreader.core.data.KoodoDatabase
import com.koodoreader.core.data.entity.BookEntity
import com.koodoreader.core.data.entity.BookmarkEntity
import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.core.data.entity.PluginEntity
import com.koodoreader.core.data.entity.WordEntity
import com.koodoreader.core.dbio.BackupBundle
import com.koodoreader.core.dbio.Row
import com.koodoreader.core.dbio.TableSource
import java.io.File
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

data class ExportReport(val file: File, val tables: Map<String, Int>, val covers: Int)

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
 *    Book files are NOT exported (user data stays on its original medium).
 */
object DesktopBridge {

    private const val BATCH = 50

    suspend fun importBackup(
        source: File,
        db: KoodoDatabase,
        booksDir: File,
        coverDir: File,
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
        }
        ImportReport(reports, coverCount, bookCount, failures)
    }

    suspend fun exportBackup(
        outDir: File,
        db: KoodoDatabase,
        coverDir: File,
        timestamp: Long,
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
            .map { com.koodoreader.core.dbio.CoverRef.of(it) } // streamed, not held in memory
        // Desktop naming (backup.ts): KoodoReader-Backup-y-m-d-<epoch>.zip
        val out = File(outDir, "KoodoReader-Backup-local-$timestamp.zip")
        BackupBundle.write(out, tables, "{}", covers)
        ExportReport(out, tables.mapValues { it.value.size }, covers.size)
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
