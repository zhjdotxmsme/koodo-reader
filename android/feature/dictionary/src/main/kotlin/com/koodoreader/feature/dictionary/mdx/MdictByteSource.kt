package com.koodoreader.feature.dictionary.mdx

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Random-access byte source for a `.mdx` / `.mdd` container.
 *
 * PORT SOURCE — `node_modules/js-mdict@6.0.8/dist/esm/scanner.js:1-34`
 * (`FileScanner`, backed by `read-chunk`). Same contract: positional reads only,
 * no streaming state, so the parser can jump around the header / key-info /
 * record-info tables freely.
 *
 * Android note: dictionary files live in app-private storage and are typically
 * 5–60 MB, so both implementations below are enough — [FileMdictSource] keeps
 * only the OS page cache hot, [ByteArrayMdictSource] is what unit tests and the
 * bundled-asset path use.
 */
interface MdictByteSource : Closeable {

    /** File name (used to derive the `.mdx` / `.mdd` extension). */
    val name: String

    /** Total file size in bytes. */
    val size: Long

    /** Read exactly `length` bytes at `offset`; throws when the range is short. */
    fun read(offset: Long, length: Int): ByteArray

    override fun close() {}
}

/** In-memory source — tests, bundled fixture dictionaries, already-downloaded bytes. */
class ByteArrayMdictSource(
    private val bytes: ByteArray,
    override val name: String = "memory.mdx",
) : MdictByteSource {

    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > bytes.size) {
            throw MdictFormatException(
                "read out of range: offset=$offset length=$length size=${bytes.size}"
            )
        }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

/** File-backed source using positional reads (`RandomAccessFile.seek`). */
class FileMdictSource(private val file: File) : MdictByteSource {

    private val raf = RandomAccessFile(file, "r")

    override val name: String get() = file.name

    override val size: Long get() = raf.length()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > raf.length()) {
            throw MdictFormatException(
                "read out of range: offset=$offset length=$length size=${raf.length()}"
            )
        }
        val out = ByteArray(length)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    override fun close() {
        raf.close()
    }
}
