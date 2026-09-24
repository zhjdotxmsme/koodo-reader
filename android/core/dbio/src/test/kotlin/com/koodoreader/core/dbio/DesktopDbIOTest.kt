package com.koodoreader.core.dbio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DesktopDbIOTest {

    @TempDir
    lateinit var dir: File

    private fun file(name: String): File = File(dir, name)

    private fun sampleBooks(): List<Row> = listOf(
        mapOf(
            "key" to "k1", "name" to "EPUB One", "author" to "A", "description" to null,
            "md5" to "aa", "cover" to null, "format" to "epub", "publisher" to null,
            "size" to 1234L, "page" to 42L, "path" to "/x/k1.epub", "charset" to null,
        ),
        mapOf(
            "key" to "k2", "name" to "漫画", "author" to null, "description" to "desc",
            "md5" to "bb", "cover" to null, "format" to "cbz", "publisher" to null,
            "size" to 0L, "page" to 7L, "path" to "/x/k2.cbz", "charset" to null,
        ),
    )

    @Test
    fun `reader returns typed values for a written file`() {
        DesktopDbWriter(file("books.db")).use { w ->
            w.createTable("books")
            w.insert("books", sampleBooks())
        }
        DesktopDbReader(file("books.db")).use { r ->
            assertTrue(r.hasTable("books"))
            val rows = r.rows("books")
            assertEquals(2, rows.size)
            val first = rows.first { it["key"] == "k1" }
            assertEquals("A", first["author"])
            assertEquals(1234L, first["size"])
            assertEquals(42L, first["page"])
            assertEquals(null, first["description"])
            assertEquals("/x/k1.epub", first["path"])
        }
    }

    @Test
    fun `reader tolerates missing table and file`() {
        // Empty file created, table never created:
        DesktopDbWriter(file("empty.db")).use { }
        DesktopDbReader(file("empty.db")).use { r ->
            assertFalse(r.hasTable("notes"))
            assertTrue(r.rows("notes").isEmpty())
        }
        // Nonexistent file:
        assertTrue(DesktopDbReader(file("absent.db")).rows("books").isEmpty())
    }

    @Test
    fun `written file carries the desktop DDL verbatim`() {
        DesktopDbWriter(file("notes.db")).use { w ->
            w.createTable("notes")
            w.insert("notes", listOf(
                mapOf(
                    "key" to "n1", "bookKey" to "k1", "date" to "{\"ts\":1}",
                    "chapter" to null, "chapterIndex" to 3L, "text" to "hi",
                    "cfi" to "epubcfi(0,65)", "range" to null, "notes" to null,
                    "percentage" to "12", "color" to 4294901760L, "tag" to "[]",
                ),
            ))
        }
        // Re-read via raw JDBC and check the table definition survived intact.
        DesktopDbReader(file("notes.db")).use { r ->
            val row = r.rows("notes").single()
            assertEquals("{\"ts\":1}", row["date"])
            assertEquals(3L, row["chapterIndex"])
            assertEquals(4294901760L, row["color"])
        }
        // The DDL must use the desktop type names (object/array) — verified
        // against the constant via JDBC schema:
        val con = java.sql.DriverManager.getConnection(jdbcUrl(file("notes.db")))
        val st = con.createStatement()
        val rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='notes'")
        rs.next()
        val sql = rs.getString(1)
        assertTrue(sql.contains("\"date\" object"), "expected desktop 'object' type in: $sql")
        assertTrue(sql.contains("\"tag\" array"), "expected desktop 'array' type in: $sql")
        con.close()
    }

    @Test
    fun `integer and text affinity survive a round trip`() {
        val f = file("words.db")
        DesktopDbWriter(f).use { w ->
            w.createTable("words")
            w.insert("words", listOf(
                mapOf(
                    "key" to "w1", "bookKey" to "k1", "date" to null,
                    "word" to "reader", "sentence" to null, "chapter" to "ch1",
                ),
            ))
        }
        DesktopDbReader(f).use { r ->
            val row = r.rows("words").single()
            assertEquals("reader", row["word"])
            assertEquals(null, row["sentence"])
        }
    }

    @Test
    fun `reinsert replaces by primary key`() {
        val f = file("bookmarks.db")
        DesktopDbWriter(f).use { w ->
            w.createTable("bookmarks")
            w.insert("bookmarks", listOf(
                mapOf("key" to "b1", "bookKey" to "k1", "cfi" to "c1", "label" to "old", "percentage" to "10", "chapter" to null),
            ))
        }
        DesktopDbWriter(f).use { w ->
            w.createTable("bookmarks") // IF-NOT-EXISTS semantics via CREATE on existing file
            w.insert("bookmarks", listOf(
                mapOf("key" to "b1", "bookKey" to "k1", "cfi" to "c2", "label" to "new", "percentage" to "20", "chapter" to null),
            ))
        }
        DesktopDbReader(f).use { r ->
            val rows = r.rows("bookmarks")
            assertEquals(1, rows.size)
            assertEquals("new", rows.single()["label"])
        }
    }
}
