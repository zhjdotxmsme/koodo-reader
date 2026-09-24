package com.koodoreader.core.dbio

/**
 * The five desktop table DDLs, copied VERBATIM from `schema.lock`
 * (`databases.<t>.tables.<t>.sql`). Do not reformat: CI enforces verbatim
 * parity via `scripts/check-dbio-ddl.js`.
 *
 * Desktop stores these in separate SQLite files (`config/<table>.db` inside
 * the storage location / backup zip); the desktop restore merges them by
 * primary key, so writing them back with the identical DDL keeps the two
 * tracks interchangeable. The quirky desktop type names (`object`, `array`,
 * `string`) are legal SQLite type names: they give TEXT affinity, which is
 * what the desktop expects for the JSON-payload columns.
 */
object DesktopDdl {

    /** Desktop database order (kookit `databaseList`). */
    val TABLES: List<String> = listOf("books", "notes", "bookmarks", "plugins", "words")

    /** Column order per table (schema.lock column order). */
    val COLUMNS: Map<String, List<String>> = mapOf(
        "books" to listOf(
            "key", "name", "author", "description", "md5", "cover",
            "format", "publisher", "size", "page", "path", "charset",
        ),
        "notes" to listOf(
            "key", "bookKey", "date", "chapter", "chapterIndex", "text",
            "cfi", "range", "notes", "percentage", "color", "tag",
        ),
        "bookmarks" to listOf(
            "key", "bookKey", "cfi", "label", "percentage", "chapter",
        ),
        "plugins" to listOf(
            "key", "type", "displayName", "icon", "version", "config",
            "autoValue", "langList", "voiceList", "scriptSHA256", "script",
        ),
        "words" to listOf(
            "key", "bookKey", "date", "word", "sentence", "chapter",
        ),
    )

    /** Desktop "temp-" scratch database file name for a table (e.g. `temp-books.db`). */
    fun tempDatabaseFile(table: String): String = "temp-$table.db"

    fun ddl(table: String): String = when (table) {
        "books" -> BOOKS
        "notes" -> NOTES
        "bookmarks" -> BOOKMARKS
        "plugins" -> PLUGINS
        "words" -> WORDS
        else -> throw IllegalArgumentException("unknown desktop table: $table")
    }

    const val BOOKS = """
CREATE TABLE "books" (
        "key" text PRIMARY KEY,
        "name" text,
        "author" text,
        "description" text,
        "md5" text,
        "cover" text,
        "format" text,
        "publisher" text,
        "size" integer,
        "page" integer,
        "path" text,
        "charset" text
      )
"""

    const val NOTES = """
CREATE TABLE "notes" (
        "key" text PRIMARY KEY,
        "bookKey" text,
        "date" object,
        "chapter" text,
        "chapterIndex" integer,
        "text" text,
        "cfi" text,
        "range" text,
        "notes" text,
        "percentage" text,
        "color" integer,
        "tag" array
      )
"""

    const val BOOKMARKS = """
CREATE TABLE "bookmarks" (
        "key" text PRIMARY KEY,
        "bookKey" text,
        "cfi" text,
        "label" text,
        "percentage" text,
        "chapter" text
      )
"""

    const val PLUGINS = """
CREATE TABLE "plugins" (
        "key" text PRIMARY KEY,
        "type" text,
        "displayName" text,
        "icon" text,
        "version" text,
        "config" object,
        "autoValue" string,
        "langList" text,
        "voiceList" text,
        "scriptSHA256" text,
        "script" text
      )
"""

    const val WORDS = """
CREATE TABLE "words" (
        "key" text PRIMARY KEY,
        "bookKey" text,
        "date" object,
        "word" text,
        "sentence" text,
        "chapter" text
      )
"""
}
