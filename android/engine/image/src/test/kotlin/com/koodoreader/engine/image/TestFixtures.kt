package com.koodoreader.engine.image

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 测试夹具：不依赖 Android、不依赖网络，纯字节构造。 */

internal fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/** 合成图片头（尺寸真、像素假），供 ImageHeader / 页序 / 排版测试用。 */
internal object Pics {

    fun png(width: Int, height: Int): ByteArray {
        val b = ByteArray(33)
        intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).forEachIndexed { i, v -> b[i] = v.toByte() }
        b[11] = 13 // IHDR 数据长度
        "IHDR".forEachIndexed { i, c -> b[12 + i] = c.code.toByte() }
        putBe32(b, 16, width)
        putBe32(b, 20, height)
        b[24] = 8 // bit depth
        b[25] = 6 // RGBA
        return b
    }

    fun gif(width: Int, height: Int): ByteArray {
        val b = ByteArray(14)
        "GIF89a".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        putLe16(b, 6, width)
        putLe16(b, 8, height)
        return b
    }

    fun jpeg(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8) // SOI
        out.write(0xFF); out.write(0xE0) // APP0
        out.write(0x00); out.write(0x10) // 段长 16
        out.write("JFIF\u0000".toByteArray(Charsets.ISO_8859_1))
        repeat(9) { out.write(0) }
        out.write(0xFF); out.write(0xC0) // SOF0
        out.write(0x00); out.write(0x11) // 段长 17
        out.write(0x08)
        out.write((height shr 8) and 0xFF); out.write(height and 0xFF)
        out.write((width shr 8) and 0xFF); out.write(width and 0xFF)
        out.write(0x03)
        repeat(9) { out.write(0) }
        out.write(0xFF); out.write(0xD9) // EOI
        return out.toByteArray()
    }

    private fun putBe32(b: ByteArray, at: Int, v: Int) {
        b[at] = ((v shr 24) and 0xFF).toByte()
        b[at + 1] = ((v shr 16) and 0xFF).toByte()
        b[at + 2] = ((v shr 8) and 0xFF).toByte()
        b[at + 3] = (v and 0xFF).toByte()
    }

    private fun putLe16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
    }
}

/** 内存归档：可控页数、可控读失败、可统计读取次数与 close。 */
internal class FakeArchive(
    pageCount: Int,
    private val pageBytes: (Int) -> ByteArray = { "PAGE-$it".toByteArray() },
    private val failOn: Set<Int> = emptySet(),
) : ArchiveExtractor {

    override val kind: ArchiveKind = ArchiveKind.ZIP
    override val name: String = "fake.cbz"
    override val fileSizeBytes: Long = pageCount * 16L

    override val entries: List<PageEntry> = (0 until pageCount).map { i ->
        val entryName = "%03d.jpg".format(i)
        PageEntry(i, entryName, ImageEntries.extOf(entryName), pageBytes(i).size.toLong())
    }

    var reads: Int = 0
        private set
    var closed: Boolean = false
        private set

    /** 记录被读过的页（验证「未驻留页不触发 IO」）。 */
    val readIndices: MutableList<Int> = mutableListOf()

    override fun readPage(index: Int): ByteArray {
        reads++
        readIndices += index
        if (index in failOn) throw IOException("synthetic read failure at page $index")
        return pageBytes(index)
    }

    override fun openPage(index: Int) = ByteArrayInputStream(readPage(index))

    override fun close() {
        closed = true
    }
}

// ---- zip / tar 写出 ---------------------------------------------------------

internal fun writeZip(file: File, entries: List<Pair<String, ByteArray>>) {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zos ->
        for ((entryName, data) in entries) {
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(data)
            zos.closeEntry()
        }
    }
    file.writeBytes(bos.toByteArray())
}

/** 最小 ustar 写出器（含 GNU long name 与 prefix 两条例外路径的测试支持）。 */
internal object TarWriter {

    private const val BLOCK = 512

    fun tar(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((entryName, data) in entries) {
            if (entryName.toByteArray(Charsets.ISO_8859_1).size > 100) {
                // GNU long name：'L' 头 + 名字数据，随后是真正的头（name 截断）
                val nameBytes = (entryName + "\u0000").toByteArray(Charsets.ISO_8859_1)
                out.write(header("././@LongLink", nameBytes.size, 'L'))
                out.write(pad(nameBytes))
                out.write(header(entryName.take(100), data.size, '0'))
            } else {
                out.write(header(entryName, data.size, '0'))
            }
            out.write(pad(data))
        }
        out.write(ByteArray(BLOCK * 2)) // 结束标记
        return out.toByteArray()
    }

    fun writeTar(file: File, entries: List<Pair<String, ByteArray>>) {
        file.writeBytes(tar(entries))
    }

    fun writeTarGz(file: File, entries: List<Pair<String, ByteArray>>) {
        GZIPOutputStream(file.outputStream()).use { it.write(tar(entries)) }
    }

    private fun pad(data: ByteArray): ByteArray {
        val remainder = data.size % BLOCK
        if (remainder == 0) return data
        return data + ByteArray(BLOCK - remainder)
    }

    private fun header(name: String, size: Int, typeFlag: Char): ByteArray {
        val h = ByteArray(BLOCK)
        val nameBytes = name.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(nameBytes, 0, h, 0, minOf(nameBytes.size, 100))
        putOctal(h, 100, 8, 0b111101101L) // mode 0755
        putOctal(h, 108, 8, 0)
        putOctal(h, 116, 8, 0)
        putOctal(h, 124, 12, size.toLong())
        putOctal(h, 136, 12, 0)
        for (i in 148 until 156) h[i] = ' '.code.toByte() // 校验和先填空格
        h[156] = typeFlag.code.toByte()
        "ustar".forEachIndexed { i, c -> h[257 + i] = c.code.toByte() }
        h[263] = '0'.code.toByte()
        h[264] = '0'.code.toByte()
        putOctal(h, 329, 8, 0)
        putOctal(h, 337, 8, 0)
        val sum = h.sumOf { it.toInt() and 0xFF }
        putOctal(h, 148, 8, sum.toLong())
        return h
    }

    private fun putOctal(b: ByteArray, offset: Int, length: Int, value: Long) {
        val digits = java.lang.Long.toOctalString(value).padStart(length - 1, '0').takeLast(length - 1)
        for (i in 0 until length - 1) b[offset + i] = digits[i].code.toByte()
        b[offset + length - 1] = 0
    }
}
