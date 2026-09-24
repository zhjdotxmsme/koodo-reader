package com.koodoreader.core.dbio

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * One table's contribution to an import, with its resolved source:
 *  - `real`    — rows came from `<table>.db`
 *  - `temp`    — `<table>.db` was absent/empty and `temp-<table>.db` (the
 *                desktop editing scratch copy) supplied the rows
 *  - `empty`   — file present, zero rows (nothing to import)
 *  - `missing` — neither file in the bundle
 */
data class TableSource(
    val table: String,
    val source: String,
    val rows: List<Row>,
)

/** A cover file entry (relative name under `cover/`, e.g. `k93jd3.jpg`). */
data class CoverFile(val name: String, val size: Long)

/** A book file entry (relative name under `book/`, e.g. `k93jd3.epub`). */
data class BookFile(val name: String, val size: Long)

/** A font file entry (relative name under `fonts/`, e.g. `lxgw_wenkai.ttf`). */
data class FontFile(val name: String, val size: Long)

/**
 * A cover to bundle at export time. Content is opened lazily (streamed into
 * the zip) so thousands of covers never sit in memory at once.
 */
class CoverRef(
    val name: String,
    val size: Long,
    private val opener: () -> java.io.InputStream,
) {
    fun openStream(): java.io.InputStream = opener()

    companion object {
        fun of(name: String, bytes: ByteArray): CoverRef =
            CoverRef(name, bytes.size.toLong()) { bytes.inputStream() }

        fun of(file: java.io.File): CoverRef =
            CoverRef(file.name, file.length()) { file.inputStream() }
    }
}

/**
 * A book file to bundle at export time. Mirrors [CoverRef] semantics:
 * contents are streamed lazily so a 2 GB library never sits in memory.
 *
 * Native tracks expose local book files under `<filesDir>/books/<key>.<ext>`;
 * the desktop `restore.ts` accepts `book/` entries and uses them to satisfy
 * `BookEntity.path` rows pointing at the device's storage.
 */
class BookRef(
    val name: String,
    val size: Long,
    private val opener: () -> java.io.InputStream,
) {
    fun openStream(): java.io.InputStream = opener()

    companion object {
        fun of(name: String, bytes: ByteArray): BookRef =
            BookRef(name, bytes.size.toLong()) { bytes.inputStream() }

        fun of(file: java.io.File): BookRef =
            BookRef(file.name, file.length()) { file.inputStream() }
    }
}

/**
 * A font file to bundle at export time. Same streaming contract as
 * [CoverRef]/[BookRef]. The desktop `backup.ts` does NOT include a `fonts/`
 * directory (fonts live in `<dataPath>/fonts/` but are exported only when
 * the user invokes "导出/导出字体"); the bridge accepts a `fonts/` entry on
 * import and copies it into `<filesDir>/fonts/`. This ref lets the native
 * track emit a desktop-shaped zip with the `fonts/` directory when needed
 * — same on-disk layout, separate code path, no temp juggling.
 */
class FontRef(
    val name: String,
    val size: Long,
    private val opener: () -> java.io.InputStream,
) {
    fun openStream(): java.io.InputStream = opener()

    companion object {
        fun of(name: String, bytes: ByteArray): FontRef =
            FontRef(name, bytes.size.toLong()) { bytes.inputStream() }

        fun of(file: java.io.File): FontRef =
            FontRef(file.name, file.length()) { file.inputStream() }
    }
}

/**
 * An opened desktop backup — either the desktop ZIP (a
 * `KoodoReader-Backup-` named zip with per-table `.db` files under `config/`
 * plus `cover/` and `book/` entries) or an unpacked directory with the same
 * layout.
 *
 * temp-* policy: the desktop keeps `temp-<table>.db` as an unsaved working
 * copy; import it ONLY when the real table is missing or empty (recover
 * in-progress work, never mix both). Export writes real tables only — the
 * desktop engine manages its own scratch copies and ours must not clobber
 * them (see the migration doc appendix A row).
 */
class BundleOpen internal constructor(
    val tables: List<TableSource>,
    val configJson: String?,
    val covers: List<CoverFile>,
    val bookFiles: List<BookFile>,
    val fontFiles: List<FontFile>,
    private val workDir: File?,
    private val zipFile: File?,
    private val coverRoot: File?,
    private val bookRoot: File?,
    private val fontRoot: File?,
) : AutoCloseable {

    override fun close() {
        workDir?.deleteRecursively()
    }

    /** Raw bytes of a bundled cover (desktop covers are small by design). */
    fun coverBytes(name: String): ByteArray? {
        if (zipFile != null) {
            return ZipFile(zipFile).use { zf ->
                zf.getEntry("cover/$name")?.let {
                    zf.getInputStream(it).use { s -> s.readBytes() }
                }
            }
        }
        val f = coverRoot?.let { File(it, name) } ?: return null
        return if (f.isFile) f.readBytes() else null
    }

    /** Stream a bundled book file into [out] (never fully in memory). */
    fun bookStream(name: String, out: OutputStream) {
        if (zipFile != null) {
            ZipFile(zipFile).use { zf ->
                val e = zf.getEntry("book/$name")
                    ?: throw DesktopDbException("no book entry: $name")
                zf.getInputStream(e).use { it.copyTo(out) }
            }
        } else {
            val f = bookRoot?.let { File(it, name) }
                ?: throw DesktopDbException("no book dir; missing entry: $name")
            if (!f.isFile) throw DesktopDbException("no book file: $name")
            f.inputStream().use { it.copyTo(out) }
        }
    }

    /** Stream a bundled font file into [out]. */
    fun fontStream(name: String, out: OutputStream) {
        if (zipFile != null) {
            ZipFile(zipFile).use { zf ->
                val e = zf.getEntry("fonts/$name")
                    ?: throw DesktopDbException("no font entry: $name")
                zf.getInputStream(e).use { it.copyTo(out) }
            }
        } else {
            val f = fontRoot?.let { File(it, name) }
                ?: throw DesktopDbException("no fonts dir; missing entry: $name")
            if (!f.isFile) throw DesktopDbException("no font file: $name")
            f.inputStream().use { it.copyTo(out) }
        }
    }
}

object BackupBundle {

    /** ZIP local-file header `PK\x03\x04`. */
    fun isZip(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        file.inputStream().use {
            val b = ByteArray(4)
            if (it.read(b) < 4) return false
            return b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() &&
                b[2] == 0x03.toByte() && b[3] == 0x04.toByte()
        }
    }

    /** Open a desktop backup: a ZIP file, or a directory with `config/` (+`cover/`+`book/`). */
    fun open(source: File): BundleOpen {
        return if (isZip(source)) readZip(source) else {
            if (!source.isDirectory) {
                throw DesktopDbException("not a zip or directory: ${source.absolutePath}")
            }
            readDir(source)
        }
    }

    // --------------------------------------------------------------- read

    private fun readZip(zip: File): BundleOpen {
        val work = java.nio.file.Files.createTempDirectory("dbio-open").toFile()
        ZipFile(zip).use { zf ->
            val dbFiles = linkedMapOf<String, File>()
            var configJson: String? = null
            val covers = mutableListOf<CoverFile>()
            val books = mutableListOf<BookFile>()
            val fonts = mutableListOf<FontFile>()
            val it = zf.entries()
            while (it.hasMoreElements()) {
                val entry = it.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                when {
                    name.startsWith("config/") && name.endsWith(".db") -> {
                        val base = name.removePrefix("config/").removeSuffix(".db")
                        val target = File(work, base + ".db")
                        target.parentFile?.mkdirs()
                        zf.getInputStream(entry).use { input ->
                            FileOutputStream(target).use { input.copyTo(it) }
                        }
                        if (base.startsWith("temp-")) {
                            dbFiles[base.removePrefix("temp-") + "@temp"] = target
                        } else {
                            dbFiles[base + "@real"] = target
                        }
                    }
                    name == "config/config.json" -> {
                        configJson = zf.getInputStream(entry).use { it.readBytes().decodeToString() }
                    }
                    name.startsWith("cover/") -> {
                        val rel = name.removePrefix("cover/")
                        covers.add(CoverFile(rel, entry.size))
                    }
                    name.startsWith("book/") -> {
                        val rel = name.removePrefix("book/")
                        books.add(BookFile(rel, entry.size))
                    }
                    name.startsWith("fonts/") -> {
                        val rel = name.removePrefix("fonts/")
                        fonts.add(FontFile(rel, entry.size))
                    }
                    else -> Unit
                }
            }
            return BundleOpen(
                tables = resolveTables(dbFiles),
                configJson = configJson,
                covers = covers,
                bookFiles = books,
                fontFiles = fonts,
                workDir = work,
                zipFile = zip,
                coverRoot = null,
                bookRoot = null,
                fontRoot = null,
            )
        }
    }

    private fun readDir(dir: File): BundleOpen {
        val realRoot = if (File(dir, "config").isDirectory) File(dir, "config") else dir
        val dbFiles = linkedMapOf<String, File>()
        realRoot.listFiles()?.forEach { f ->
            if (!f.isFile || !f.name.endsWith(".db")) return@forEach
            val base = f.name.removeSuffix(".db")
            if (base.startsWith("temp-")) dbFiles[base.removePrefix("temp-") + "@temp"] = f
            else dbFiles[base + "@real"] = f
        }
        val coversDir = File(dir, "cover")
        val covers = coversDir.listFiles()
            .orEmpty().filter { it.isFile }.map { CoverFile(it.name, it.length()) }
        val booksDir = File(dir, "book")
        val books = booksDir.listFiles()
            .orEmpty().filter { it.isFile }.map { BookFile(it.name, it.length()) }
        val configJsf = File(realRoot, "config.json")
        val fontsDir = File(dir, "fonts")
        return BundleOpen(
            tables = resolveTables(dbFiles),
            configJson = configJsf.takeIf { it.isFile }?.readText(),
            covers = covers,
            bookFiles = books,
            fontFiles = fontsDir.listFiles()
                .orEmpty().filter { it.isFile }.map { FontFile(it.name, it.length()) },
            workDir = null,
            zipFile = null,
            coverRoot = if (coversDir.isDirectory) coversDir else null,
            bookRoot = if (booksDir.isDirectory) booksDir else null,
            fontRoot = if (fontsDir.isDirectory) fontsDir else null,
        )
    }

    private fun resolveTables(dbFiles: Map<String, File>): List<TableSource> {
        val out = mutableListOf<TableSource>()
        for (t in DesktopDdl.TABLES) {
            val realFile: File? = dbFiles[t + "@real"]
            val tempFile: File? = dbFiles[t + "@temp"]
            val realRows: List<Row> = realFile?.let {
                DesktopDbEngine.readerFactory(it).use { r -> r.rows(t) }
            } ?: emptyList()
            when {
                realRows.isNotEmpty() -> out.add(TableSource(t, "real", realRows))
                tempFile != null -> {
                    val tempRows = DesktopDbEngine.readerFactory(tempFile).use { r -> r.rows(t) }
                    out.add(TableSource(t, if (tempRows.isNotEmpty()) "temp" else "empty", tempRows))
                }
                realFile != null -> out.add(TableSource(t, "empty", realRows))
                else -> out.add(TableSource(t, "missing", emptyList()))
            }
        }
        return out
    }

    // -------------------------------------------------------------- write

    /**
     * Write a desktop-readable backup ZIP (layout mirrors `backupFromPath`):
     * `config/config.json` (desktop restore REQUIRES this entry),
     * `config/<table>.db` per table (exact desktop DDL), `cover/<name>`.
     * Book files are deliberately NOT included by default — they are user
     * media on the device and the desktop restore only re-imports them when
     * a `book/` directory is present; pass [bookFiles] to mirror a desktop
     * backup that did bundle them. `fonts/` is included only when [fontFiles]
     * is non-empty (desktop "导出字体" style export). All three asset streams
     * are passed as `() -> InputStream` so callers never hold the bytes.
     *
     * Books live under `book/` in the zip (NOT `books/`); the desktop
     * restore iterates `book/<file>` and copies each into the storage
     * location's `book/` directory. Same on-disk layout, both directions.
     *
     * [syncJson] lets the caller embed a `config/sync.json` (the desktop
     * restore re-binds it to `ConfigService.setItem("syncRecord", ...)`);
     * default is empty so existing callers stay byte-identical.
     */
    fun write(
        out: File,
        tables: Map<String, List<Row>>,
        configJson: String = "{}",
        covers: List<CoverRef> = emptyList(),
        bookFiles: List<BookRef> = emptyList(),
        fontFiles: List<FontRef> = emptyList(),
        syncJson: String = "",
    ) {
        val work = java.nio.file.Files.createTempDirectory("dbio-write").toFile()
        try {
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    zos.putNextEntry(ZipEntry("config/config.json"))
                    zos.write(configJson.toByteArray())
                    zos.closeEntry()
                    if (syncJson.isNotEmpty()) {
                        zos.putNextEntry(ZipEntry("config/sync.json"))
                        zos.write(syncJson.toByteArray())
                        zos.closeEntry()
                    }
                    for (t in DesktopDdl.TABLES) {
                        val rows = tables[t].orEmpty()
                        val dbFile = File(work, "$t.db")
                        DesktopDbEngine.writerFactory(dbFile).use { w ->
                            w.createTable(t)
                            w.insert(t, rows)
                        }
                        zos.putNextEntry(ZipEntry("config/$t.db"))
                        dbFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        dbFile.delete()
                    }
                    for (ref in covers) {
                        zos.putNextEntry(ZipEntry("cover/${ref.name}"))
                        ref.openStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    // Books are streamed last: they dwarf covers/fonts and
                    // committing config tables before them keeps progress
                    // reports meaningful (a partial failure still yields a
                    // valid desktop-readable zip up to the last finished book).
                    for (ref in bookFiles) {
                        zos.putNextEntry(ZipEntry("book/${ref.name}"))
                        ref.openStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    for (ref in fontFiles) {
                        zos.putNextEntry(ZipEntry("fonts/${ref.name}"))
                        ref.openStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }
}
