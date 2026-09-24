package com.koodoreader.core.dbio

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

/** A row of a desktop table: column name -> SQLite value (String/Long/Double/ByteArray/null). */
typealias Row = Map<String, Any?>

fun jdbcUrl(file: File): String = "jdbc:sqlite:${file.absolutePath}"

/**
 * Engine-agnostic access to one desktop-format `.db` file (any database the
 * desktop engine keeps: `<table>.db` or its `temp-<table>.db` scratch copy —
 * both contain the same-named table).
 *
 * The JDBC-based [DesktopDbReader] is the DEFAULT engine (pure JVM, used by
 * the unit tests). On Android the app installs a framework-SQLite factory via
 * [DesktopDbEngine] at startup: org.xerial sqlite-jdbc ships no Android
 * natives (dlopen fails, xerial#794), so it must never run on a device.
 */
interface DesktopDbReaderHandle : AutoCloseable {
    fun hasTable(table: String): Boolean
    fun rows(table: String): List<Row>
}

/** Engine-agnostic writer (native → desktop direction). */
interface DesktopDbWriterHandle : AutoCloseable {
    fun createTable(table: String)
    fun insert(table: String, rows: List<Row>)
}

/** Pluggable SQLite engine for [DesktopDbReader]/[DesktopDbWriter] duties. */
object DesktopDbEngine {
    @Volatile
    var readerFactory: (File) -> DesktopDbReaderHandle = { file -> DesktopDbReader(file) }

    @Volatile
    var writerFactory: (File) -> DesktopDbWriterHandle = { file -> DesktopDbWriter(file) }
}

class DesktopDbReader internal constructor(private val file: File) : DesktopDbReaderHandle {

    init {
        Class.forName("org.sqlite.JDBC")
    }

    /** Single reused connection — opened lazily, closed by [close]. */
    private var cachedConnection: Connection? = null

    private fun present(): Boolean = file.isFile

    private fun connection(): Connection {
        cachedConnection?.let { return it }
        val c = DriverManager.getConnection(jdbcUrl(file))
        c.createStatement().use { it.execute("PRAGMA query_only = ON") }
        cachedConnection = c
        return c
    }

    /** A missing file behaves like an empty database (lenient by design). */
    override fun hasTable(table: String): Boolean {
        if (!present()) return false
        // The connection is cached and closed by close() — do NOT `use` it here.
        val con = connection()
        return con.prepareStatement(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
        ).use { st ->
            st.setString(1, table)
            st.executeQuery().use { rs -> rs.next() }
        }
    }

    /** All rows of [table], column-name keyed (missing file/table -> empty list). */
    override fun rows(table: String): List<Row> {
        if (!present()) return emptyList()
        val con = connection()
        if (!hasTable(table)) return emptyList()
        val out = mutableListOf<Row>()
        con.createStatement(
            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
        ).use { st ->
            try {
                st.executeQuery("SELECT * FROM \"$table\"").use { rs ->
                    val meta = rs.metaData
                    val count = meta.columnCount
                    while (rs.next()) {
                        val row = HashMap<String, Any?>(count)
                        for (i in 1..count) {
                            row[meta.getColumnLabel(i)] = ValueReader.read(rs, i)
                        }
                        out.add(row)
                    }
                }
            } catch (e: java.sql.SQLException) {
                throw DesktopDbException("read $table: ${e.message}", e)
            }
        }
        return out
    }

    override fun close() {
        runCatching { cachedConnection?.close() }
        cachedConnection = null
    }
}

private object ValueReader {
    fun read(rs: ResultSet, index: Int): Any? =
        when (val v = rs.getObject(index)) {
            is ByteArray -> v
            is Int -> v.toLong()
            is Long -> v
            is Double -> v
            is Float -> v.toDouble()
            is Number -> v.toDouble()
            null -> null
            else -> v.toString()
        }
}

/** Reported when a desktop `.db` file cannot be parsed. */
class DesktopDbException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * JDBC writer for desktop-format `.db` files (default JVM engine; see
 * [DesktopDbEngine] for the Android story). Creates the table with the EXACT
 * desktop DDL (schema.lock) so the desktop engine reads the file back
 * without surprises.
 */
class DesktopDbWriter internal constructor(private val file: File) : DesktopDbWriterHandle {

    private val connection: Connection

    init {
        file.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection(jdbcUrl(file))
    }

    override fun createTable(table: String) {
        connection.prepareStatement(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
        ).use { st ->
            st.setString(1, table)
            val exists = st.executeQuery().use { it.next() }
            if (!exists) {
                connection.createStatement().use { it.execute(DesktopDdl.ddl(table).trim()) }
            }
        }
    }

    override fun insert(table: String, rows: List<Row>) {
        if (rows.isEmpty()) return
        val columns = DesktopDdl.COLUMNS[table]
            ?: throw IllegalArgumentException("no column list for $table")
        val sql = "INSERT OR REPLACE INTO \"$table\" (${
            columns.joinToString(", ") { "\"$it\"" }
        }) VALUES (${columns.joinToString(", ") { "?" }})"
        connection.prepareStatement(sql).use { ps ->
            rows.forEach { row ->
                columns.forEachIndexed { idx, col ->
                    val v = row[col]
                    if (v == null) ps.setNull(idx + 1, java.sql.Types.NULL) else ps.setObject(idx + 1, v)
                }
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    override fun close() {
        connection.close()
    }
}
