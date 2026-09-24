package com.koodoreader.engine.text

/**
 * Random-access view over a book's raw bytes.
 *
 * The native pagination engine must not materialise a 100 MB TXT file: it reads
 * a window around the current position, lays it out, and advances. [TextSource]
 * is the smallest interface that supports both that windowed reading and the
 * whole-file path used by chapter splitting.
 *
 * Implementations must be safe to call from a background thread (the reader
 * loads books off the UI thread) and must never throw on an out-of-range read —
 * clamping is part of the contract, so callers can page blindly past EOF.
 */
interface TextSource {

    /** Total size in bytes. */
    val length: Long

    /**
     * Reads up to [len] bytes starting at byte [offset] (0-based).
     *
     * Returns fewer bytes than requested when the range crosses EOF, and an
     * empty array when `offset >= length` or `len <= 0`. Never throws for
     * out-of-range arguments.
     */
    fun read(offset: Long, len: Int): ByteArray

    /** Reads the whole source. Use only when the caller really needs all bytes. */
    fun readAll(): ByteArray {
        if (length > Int.MAX_VALUE) {
            throw IllegalStateException("source is ${length} bytes; use windowed reads")
        }
        return read(0, length.toInt())
    }

    companion object {
        /** Small default window (64 KiB) — one I/O call, cache friendly. */
        const val DEFAULT_WINDOW = 64 * 1024
    }
}

/**
 * In-memory [TextSource], used for unit tests and for small files that the
 * importer already holds in memory.
 */
class ByteArrayTextSource(
    private val bytes: ByteArray,
    private val baseOffset: Int = 0,
    private val size: Int = bytes.size - baseOffset,
) : TextSource {

    init {
        require(baseOffset >= 0 && size >= 0 && baseOffset + size <= bytes.size) {
            "invalid window: base=$baseOffset size=$size capacity=${bytes.size}"
        }
    }

    override val length: Long get() = size.toLong()

    override fun read(offset: Long, len: Int): ByteArray {
        if (len <= 0 || offset < 0 || offset >= size) return ByteArray(0)
        val end = minOf(offset + len, size.toLong())
        val from = baseOffset + offset.toInt()
        return bytes.copyOfRange(from, baseOffset + end.toInt())
    }

    override fun readAll(): ByteArray = read(0, size)
}

/** [TextSource] over an on-disk file, using positional reads (`seek` + `read`). */
class FileTextSource(private val path: java.io.File) : TextSource, AutoCloseable {

    @Volatile
    private var raf: java.io.RandomAccessFile? = null

    private val handle: java.io.RandomAccessFile
        get() = raf ?: synchronized(this) {
            raf ?: java.io.RandomAccessFile(path, "r").also { raf = it }
        }

    override val length: Long
        get() = handle.length()

    override fun read(offset: Long, len: Int): ByteArray {
        val total = length
        if (len <= 0 || offset < 0 || offset >= total) return ByteArray(0)
        val want = minOf(len.toLong(), total - offset).toInt()
        val out = ByteArray(want)
        // RandomAccessFile is not thread-safe: serialise the seek+read pair.
        synchronized(this) {
            val h = handle
            h.seek(offset)
            h.readFully(out)
        }
        return out
    }

    override fun close() {
        synchronized(this) {
            raf?.close()
            raf = null
        }
    }
}
