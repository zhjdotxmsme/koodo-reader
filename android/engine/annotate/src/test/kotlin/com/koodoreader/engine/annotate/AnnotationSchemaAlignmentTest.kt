package com.koodoreader.engine.annotate

// `mismatches` is a member extension of the AnnotationSchema object, so it has to
// be imported explicitly to be callable on a TableSpec from outside.
import com.koodoreader.engine.annotate.AnnotationSchema.mismatches
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verify [AnnotationSchema] column layouts align with the desktop schema.lock
 * `notes` table, column by column.
 *
 * schema.lock column order for `notes`:
 * key, bookKey, date, chapter, chapterIndex, text, cfi, range,
 * notes, percentage, color, tag
 *
 * Primary key: `key` (TEXT)
 */
class AnnotationSchemaAlignmentTest {

    @Test
    fun `notes table has exactly 12 columns`() {
        assertEquals(12, AnnotationSchema.NOTES.columns.size)
    }

    @Test
    fun `notes columns are in correct order`() {
        val names = AnnotationSchema.NOTES.columnNames
        val expected = listOf(
            "key", "bookKey", "date", "chapter", "chapterIndex",
            "text", "cfi", "range", "notes", "percentage", "color", "tag",
        )
        assertEquals(expected, names)
    }

    @Test
    fun `notes primary key is key`() {
        assertEquals(listOf("key"), AnnotationSchema.NOTES.primaryKeyNames)
    }

    @Test
    fun `notes key column is TEXT pk`() {
        val col = AnnotationSchema.NOTES.columns[0]
        assertEquals("key", col.name)
        assertEquals("TEXT", col.type)
        assertTrue(col.pk)
    }

    @Test
    fun `notes bookKey column is TEXT`() {
        val col = AnnotationSchema.NOTES.columns[1]
        assertEquals("bookKey", col.name)
        assertEquals("TEXT", col.type)
        assertTrue(!col.pk)
    }

    @Test
    fun `notes date column is object type token`() {
        val col = AnnotationSchema.NOTES.columns[2]
        assertEquals("date", col.name)
        assertEquals("object", col.type) // desktop quirky token
        assertTrue(!col.pk)
    }

    @Test
    fun `notes chapter column is TEXT`() {
        val col = AnnotationSchema.NOTES.columns[3]
        assertEquals("chapter", col.name)
        assertEquals("TEXT", col.type)
    }

    @Test
    fun `notes chapterIndex column is INTEGER`() {
        val col = AnnotationSchema.NOTES.columns[4]
        assertEquals("chapterIndex", col.name)
        assertEquals("INTEGER", col.type)
    }

    @Test
    fun `notes text column is TEXT`() {
        val col = AnnotationSchema.NOTES.columns[5]
        assertEquals("text", col.name)
        assertEquals("TEXT", col.type)
    }

    @Test
    fun `notes cfi column is TEXT`() {
        val col = AnnotationSchema.NOTES.columns[6]
        assertEquals("cfi", col.name)
        assertEquals("TEXT", col.type)
    }

    @Test
    fun `notes range column is TEXT`() {
        val col = AnnotationSchema.NOTES.columns[7]
        assertEquals("range", col.name)
        assertEquals("TEXT", col.type)
    }

    @Test
    fun `notes notes column is TEXT`() {
        val col = AnnotationSchema.NOTES.columns[8]
        assertEquals("notes", col.name)
        assertEquals("TEXT", col.type)
    }

    @Test
    fun `notes percentage column is TEXT`() {
        val col = AnnotationSchema.NOTES.columns[9]
        assertEquals("percentage", col.name)
        assertEquals("TEXT", col.type)
    }

    @Test
    fun `notes color column is INTEGER`() {
        val col = AnnotationSchema.NOTES.columns[10]
        assertEquals("color", col.name)
        assertEquals("INTEGER", col.type)
    }

    @Test
    fun `notes tag column is array type token`() {
        val col = AnnotationSchema.NOTES.columns[11]
        assertEquals("tag", col.name)
        assertEquals("array", col.type) // desktop quirky token
    }

    // ── Bookmarks table alignment ────────────────────────────────────────────

    @Test
    fun `bookmarks table has 6 columns`() {
        assertEquals(6, AnnotationSchema.BOOKMARKS.columns.size)
    }

    @Test
    fun `bookmarks columns are in correct order`() {
        val names = AnnotationSchema.BOOKMARKS.columnNames
        val expected = listOf("key", "bookKey", "cfi", "label", "percentage", "chapter")
        assertEquals(expected, names)
    }

    @Test
    fun `bookmarks primary key is key`() {
        assertEquals(listOf("key"), AnnotationSchema.BOOKMARKS.primaryKeyNames)
    }

    @Test
    fun `bookmarks key column is TEXT pk`() {
        val col = AnnotationSchema.BOOKMARKS.columns[0]
        assertEquals("key", col.name)
        assertEquals("TEXT", col.type)
        assertTrue(col.pk)
    }

    // ── Schema mismatches helper ─────────────────────────────────────────────

    @Test
    fun `mismatches returns empty for identical layout`() {
        val actual = AnnotationSchema.NOTES.columns
        val problems = AnnotationSchema.NOTES.mismatches(actual)
        assertTrue(problems.isEmpty(), "identical layout should produce no problems: $problems")
    }

    @Test
    fun `mismatches catches wrong column name`() {
        val bad = listOf(
            ColumnSpec("wrong_name", "TEXT", pk = true),
            ColumnSpec("bookKey", "TEXT"),
        ) + AnnotationSchema.NOTES.columns.drop(2).map { it.copy() }

        val problems = AnnotationSchema.NOTES.mismatches(bad)
        assertTrue(problems.isNotEmpty(), "wrong column name should be detected")
    }

    @Test
    fun `mismatches catches wrong primary key`() {
        val bad = AnnotationSchema.NOTES.columns.map { col ->
            if (col.name == "key") col.copy(pk = false) else col
        }
        val problems = AnnotationSchema.NOTES.mismatches(bad)
        assertTrue(problems.isNotEmpty(), "wrong PK should be detected")
    }

    @Test
    fun `mismatches catches wrong type token`() {
        val bad = AnnotationSchema.NOTES.columns.map { col ->
            if (col.name == "color") ColumnSpec(col.name, "TEXT", pk = col.pk)
            else col
        }
        val problems = AnnotationSchema.NOTES.mismatches(bad)
        assertTrue(problems.isNotEmpty(), "wrong type token should be detected")
    }

    @Test
    fun `mismatches catches wrong column count`() {
        val tooFew = AnnotationSchema.NOTES.columns.dropLast(1)
        val problems = AnnotationSchema.NOTES.mismatches(tooFew)
        assertTrue(problems.isNotEmpty(), "wrong column count should be detected")
    }

    // ── Column constants match schema names ──────────────────────────────────

    @Test
    fun `COL_KEY constant equals schema column name`() {
        assertEquals("key", AnnotationSchema.COL_KEY)
    }

    @Test
    fun `COL_BOOK_KEY constant equals schema column name`() {
        assertEquals("bookKey", AnnotationSchema.COL_BOOK_KEY)
    }

    @Test
    fun `COL_DATE constant equals schema column name`() {
        assertEquals("date", AnnotationSchema.COL_DATE)
    }

    @Test
    fun `forTable returns NOTES for notes`() {
        assertEquals(AnnotationSchema.NOTES, AnnotationSchema.forTable("notes"))
    }

    @Test
    fun `forTable returns BOOKMARKS for bookmarks`() {
        assertEquals(AnnotationSchema.BOOKMARKS, AnnotationSchema.forTable("bookmarks"))
    }

    @Test
    fun `forTable returns null for unknown table`() {
        assertEquals(null, AnnotationSchema.forTable("unknown"))
    }
}
