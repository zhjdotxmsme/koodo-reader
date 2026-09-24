package com.koodoreader.core.dbio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream

class BackupBundleTest {

    @TempDir
    lateinit var dir: File

    private fun sampleNotes(): List<Row> = listOf(
        mapOf(
            "key" to "n1", "bookKey" to "k1", "date" to "{\"ts\":9}",
            "chapter" to "c", "chapterIndex" to 1L, "text" to "t",
            "cfi" to "epubcfi(0,1)", "range" to null, "notes" to null,
            "percentage" to "5", "color" to 123L, "tag" to "[]",
        ),
    )

    private fun sampleBooks(): List<Row> = listOf(
        mapOf(
            "key" to "k1", "name" to "Book", "author" to null, "description" to null,
            "md5" to "deadbeef", "cover" to null, "format" to "epub", "publisher" to null,
            "size" to 10L, "page" to 3L, "path" to "/b/k1.epub", "charset" to null,
        ),
    )

    // ------------------------------------------------------------- zip io

    @Test
    fun `zip write then open round trips tables and covers`() {
        val zipPath = File(dir, "KoodoReader-Backup-test.zip")
        val cover = byteArrayOf(1, 2, 3, 4)
        BackupBundle.write(
            out = zipPath,
            tables = mapOf("books" to sampleBooks(), "notes" to sampleNotes()),
            configJson = "{}",
            covers = listOf(CoverRef.of("k1.jpeg", cover)),
        )
        assertTrue(BackupBundle.isZip(zipPath))
        BackupBundle.open(zipPath).use { b ->
            assertEquals("{}", b.configJson)
            val byTable = b.tables.associate { it.table to it }
            assertEquals("real", byTable["books"]?.source)
            assertEquals(1, byTable["books"]?.rows?.size)
            assertEquals("Book", byTable["books"]?.rows?.first()!!["name"])
            assertEquals("real", byTable["notes"]?.source)
            assertEquals(123L, byTable["notes"]?.rows?.first()!!["color"])
            // write() emits all five tables (empty ones included) -> "empty":
            assertTrue(byTable["plugins"]?.rows?.isEmpty() == true)
            assertEquals("empty", byTable["plugins"]?.source)
            assertEquals("empty", byTable["bookmarks"]?.source)
            assertEquals(listOf("k1.jpeg"), b.covers.map { it.name })
            assertEquals(cover.contentToString(), b.coverBytes("k1.jpeg")?.contentToString())
        }
    }

    @Test
    fun `zip open recovers temp scratch when real table is missing`() {
        val zipPath = File(dir, "temp-only.zip")
        // Build the zip by hand: a temp-notes db, no real notes, real books present.
        val work = java.nio.file.Files.createTempDirectory("handmade-zip").toFile()
        val cfg = File(work, "config.json")
        cfg.writeText("{}")
        val notesTemp = File(work, "temp-notes.db")
        DesktopDbWriter(notesTemp).use { w ->
            w.createTable("notes")
            w.insert("notes", sampleNotes())
        }
        val books = File(work, "books.db")
        DesktopDbWriter(books).use { w ->
            w.createTable("books")
            w.insert("books", sampleBooks())
        }
        FileOutputStream(zipPath).use { fos ->
            java.util.zip.ZipOutputStream(fos).use { zos ->
                fun put(name: String, f: File) {
                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                put("config/config.json", cfg)
                put("config/books.db", books)
                put("config/temp-notes.db", notesTemp)
            }
        }
        work.deleteRecursively()
        val notes = sampleNotes().first()
        BackupBundle.open(zipPath).use { b ->
            val byTable = b.tables.associate { it.table to it }
            assertEquals("real", byTable["books"]?.source)
            assertEquals("temp", byTable["notes"]?.source)
            assertEquals(notes["key"], byTable["notes"]?.rows?.first()!!["key"])
            assertEquals("missing", byTable["bookmarks"]?.source)
        }
    }

    @Test
    fun `all five tables round trip with rows`() {
        val zipPath = File(dir, "all-five.zip")
        BackupBundle.write(
            out = zipPath,
            tables = mapOf(
                "books" to sampleBooks(),
                "notes" to sampleNotes(),
                "bookmarks" to listOf(
                    mapOf("key" to "b1", "bookKey" to "k1", "cfi" to "epubcfi(0,2)", "label" to "L", "percentage" to "9", "chapter" to null),
                ),
                "plugins" to listOf(
                    mapOf(
                        "key" to "p1", "type" to "tt", "displayName" to "P", "icon" to null,
                        "version" to "1", "config" to "{}", "autoValue" to "x",
                        "langList" to null, "voiceList" to null, "scriptSHA256" to null, "script" to null,
                    ),
                ),
                "words" to listOf(
                    mapOf("key" to "w1", "bookKey" to "k1", "date" to null, "word" to "word", "sentence" to null, "chapter" to null),
                ),
            ),
            configJson = "{}",
        )
        BackupBundle.open(zipPath).use { b ->
            for (t in list0f5()) {
                val src = b.tables.first { it.table == t }
                assertEquals("real", src.source, "table $t")
                assertEquals(1, src.rows.size, "rows in $t")
            }
        }
    }

    private fun list0f5(): List<String> = listOf("books", "notes", "bookmarks", "plugins", "words")

    @Test
    fun `real table wins over temp scratch`() {
        val dirRoot = File(dir, "bundle")
        val cfg = File(dirRoot, "config")
        cfg.mkdirs()
        val real = File(cfg, "words.db")
        DesktopDbWriter(real).use { w ->
            w.createTable("words")
            w.insert("words", listOf(
                mapOf("key" to "w2", "bookKey" to "k1", "date" to null, "word" to "real", "sentence" to null, "chapter" to null),
            ))
        }
        val scratch = File(cfg, "temp-words.db")
        DesktopDbWriter(scratch).use { w ->
            w.createTable("words")
            w.insert("words", listOf(
                mapOf("key" to "w1", "bookKey" to "k1", "date" to null, "word" to "scratch", "sentence" to null, "chapter" to null),
            ))
        }
        BackupBundle.open(dirRoot).use { b ->
            val words = b.tables.first { it.table == "words" }
            assertEquals("real", words.source)
            assertEquals("real", words.rows.single()["word"])
            assertEquals("missing", b.tables.first { it.table == "bookmarks" }.source)
        }
    }

    @Test
    fun `directory bundle exposes covers and books`() {
        val root = File(dir, "dirbundle")
        val cover = File(root, "cover")
        val book = File(root, "book")
        val fonts = File(root, "fonts")
        cover.mkdirs()
        book.mkdirs()
        fonts.mkdirs()
        File(cover, "k1.png").writeBytes(byteArrayOf(9, 9))
        File(book, "k1.epub").writeText("fake-epub-bytes")
        File(fonts, "lxgw.ttf").writeBytes(byteArrayOf(0, 1, 0, 0))
        BackupBundle.open(root).use { b ->
            assertEquals(listOf("k1.png"), b.covers.map { it.name })
            assertEquals(byteArrayOf(9, 9).toList(), b.coverBytes("k1.png")!!.toList())
            assertEquals(listOf("k1.epub"), b.bookFiles.map { it.name })
            val out = java.io.ByteArrayOutputStream()
            b.bookStream("k1.epub", out)
            assertEquals("fake-epub-bytes", out.toString())
            assertEquals(listOf("lxgw.ttf"), b.fontFiles.map { it.name })
            val fontOut = java.io.ByteArrayOutputStream()
            b.fontStream("lxgw.ttf", fontOut)
            assertEquals(byteArrayOf(0, 1, 0, 0).toList(), fontOut.toByteArray().toList())
            assertTrue(b.tables.first { it.table == "books" }.rows.isEmpty())
            assertEquals("missing", b.tables.first { it.table == "books" }.source)
        }
    }

    @Test
    fun `zip detection distinguishes formats`() {
        val notZip = File(dir, "plain.txt")
        notZip.writeText("hello")
        assertFalse(BackupBundle.isZip(notZip))
        val zipPath = File(dir, "b.zip")
        BackupBundle.write(zipPath, emptyMap(), "{}", emptyList())
        assertTrue(BackupBundle.isZip(zipPath))
    }
}
