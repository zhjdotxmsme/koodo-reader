package com.koodoreader.reader

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.koodoreader.core.dbio.DesktopDbEngine
import com.koodoreader.core.dbio.DesktopDbReaderHandle
import com.koodoreader.core.dbio.DesktopDbWriterHandle
import com.koodoreader.core.dbio.DesktopDdl
import com.koodoreader.core.dbio.Row
import java.io.File

/**
 * Device-side SQLite engine for core:dbio, backed by the framework
 * `android.database.sqlite` (works with the desktop's quirky DDL type names:
 * `object`/`array`/`string` are unknown types → NUMERIC affinity, and the
 * framework stores values by runtime type, so TEXT/INTEGER round-trip
 * exactly like on the desktop's better-sqlite3).
 *
 * org.xerial sqlite-jdbc must NOT run on Android (no natives, xerial#794);
 * install this once at app startup (KoodoReaderApp.onCreate).
 */
object AndroidDesktopDb {

    fun install() {
        DesktopDbEngine.readerFactory = { file -> AndroidReader(file) }
        DesktopDbEngine.writerFactory = { file -> AndroidWriter(file) }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { it.moveToFirst() }
}

/** Read-only handle over an existing desktop `.db` file (lenient on absence). */
private class AndroidReader(file: File) : DesktopDbReaderHandle {
    private val db: SQLiteDatabase? =
        if (file.isFile) {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } else {
            null
        }

    override fun hasTable(table: String): Boolean =
        db?.let { AndroidDesktopDbPrivate.tableExists(it, table) } ?: false

    override fun rows(table: String): List<Row> {
        val d = db ?: return emptyList()
        if (!AndroidDesktopDbPrivate.tableExists(d, table)) return emptyList()
        val out = mutableListOf<Row>()
        d.rawQuery("SELECT * FROM \"$table\"", null).use { c ->
            val count = c.columnCount
            while (c.moveToNext()) {
                val row = HashMap<String, Any?>(count)
                for (i in 0 until count) {
                    row[c.getColumnName(i)] = valueAt(c, i)
                }
                out.add(row)
            }
        }
        return out
    }

    private fun valueAt(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
        else -> c.getString(i)
    }

    override fun close() {
        db?.close()
    }
}

/** Read/write/create handle for a desktop-format `.db` file. */
private class AndroidWriter(file: File) : DesktopDbWriterHandle {
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)

    override fun createTable(table: String) {
        if (AndroidDesktopDbPrivate.tableExists(db, table)) return
        db.execSQL(DesktopDdl.ddl(table).trim())
    }

    override fun insert(table: String, rows: List<Row>) {
        if (rows.isEmpty()) return
        val columns = DesktopDdl.COLUMNS[table]
            ?: throw IllegalArgumentException("no column list for $table")
        val sql = "INSERT OR REPLACE INTO \"$table\" (${
            columns.joinToString(", ") { "\"$it\"" }
        }) VALUES (${columns.joinToString(", ") { "?" }})"
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(sql)
            rows.forEach { row ->
                columns.forEachIndexed { idx, col ->
                    when (val v = row[col]) {
                        null -> stmt.bindNull(idx + 1)
                        is String -> stmt.bindString(idx + 1, v)
                        is Long -> stmt.bindLong(idx + 1, v)
                        is Double -> stmt.bindDouble(idx + 1, v)
                        is Boolean -> stmt.bindLong(idx + 1, if (v) 1L else 0L)
                        is ByteArray -> stmt.bindBlob(idx + 1, v)
                        else -> stmt.bindString(idx + 1, v.toString())
                    }
                }
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun close() {
        db.close()
    }
}

/** Indirection so the private impls can share the existence check. */
private object AndroidDesktopDbPrivate {
    fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { it.moveToFirst() }
}
