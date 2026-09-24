package com.koodoreader.engine.image

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * CBT / `.tar`（含 `.tar.gz`）实现骨架。
 *
 * 实现范围（够用即可，不做 pax 全量）：
 *  - ustar（magic `ustar` @257）、GNU long name（typeflag `L`）、`prefix` 字段；
 *  - `0/\0/7` 视为普通文件，`5`/`1`/`2` 等跳过；
 *  - **未压缩 tar**：随机访问（[RandomAccessFile] 按偏移读），打开即列出页表，
 *    不把整卷读进内存；
 *  - **`.tar.gz`**：gzip 无法随机访问，打开时整体解压到内存，并有体积上限
 *    [MAX_GZIP_MATERIALISE_BYTES]；超限抛 [UnsupportedArchiveException] 并提示
 *    转 CBZ（避免 OOM —— 这是刻意的取舍，不是遗漏）。
 *
 * 未做（诚实标注）：pax 扩展头（`x`）的 `path` 覆盖、校验和验证、
 * sparse 文件。三者对漫画卷宗罕见，列入 ADR §8 待办。
 */
class TarExtractor private constructor(
    private val file: File,
    private val source: BlockSource,
    override val entries: List<PageEntry>,
    private val refs: List<TarRef>,
) : ArchiveExtractor {

    private val gzipMaterialised: Boolean = source is MemoryBlockSource

    override val kind: ArchiveKind = if (gzipMaterialised) ArchiveKind.TAR_GZIP else ArchiveKind.TAR

    override val name: String = file.name

    override val fileSizeBytes: Long = if (gzipMaterialised) source.size else file.length()

    /** `.tar.gz` 走内存解压（宿主可据此提示内存占用）。 */
    val materialisedInMemory: Boolean get() = gzipMaterialised

    override fun readPage(index: Int): ByteArray {
        val ref = refs[index]
        return source.readAt(ref.dataOffset, ref.size.toInt())
    }

    override fun openPage(index: Int): InputStream = ByteArrayInputStream(readPage(index))

    override fun close() {
        source.close()
    }

    /** tar 里的一页：名字 + 数据在（未压缩）tar 流中的位置。 */
    internal data class TarRef(val name: String, val dataOffset: Long, val size: Long, val typeFlag: Char)

    companion object {

        const val BLOCK = 512

        /** `.tar.gz` 内存解压上限（96 MB）：超过就建议转 CBZ。 */
        const val MAX_GZIP_MATERIALISE_BYTES: Long = 96L * 1024 * 1024

        fun open(file: File, maxGzipMaterialiseBytes: Long = MAX_GZIP_MATERIALISE_BYTES): TarExtractor {
            val header = file.inputStream().use { stream ->
                val buffer = ByteArray(4)
                val read = stream.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            }
            val isGzip = header.size >= 2 && (header[0].toInt() and 0xFF) == 0x1F &&
                (header[1].toInt() and 0xFF) == 0x8B
            val source: BlockSource =
                if (isGzip) materialiseGzip(file, maxGzipMaterialiseBytes) else FileBlockSource(file)
            return try {
                val refs = parseTar(source)
                val pageRefs = refs.filter { ImageEntries.isImage(it.name) }
                    .sortedWith(compareBy(NaturalOrder) { it.name })
                val entries = pageRefs.mapIndexed { index, ref ->
                    PageEntry(index, ref.name, ImageEntries.extOf(ref.name), ref.size)
                }
                TarExtractor(file, source, entries, pageRefs)
            } catch (t: Throwable) {
                runCatching { source.close() }
                throw t
            }
        }

        private fun materialiseGzip(file: File, maxBytes: Long): BlockSource {
            val out = ByteArrayOutputStream()
            GZIPInputStream(file.inputStream().buffered()).use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        throw UnsupportedArchiveException(
                            "tar.gz 解压后超过 ${maxBytes / 1024 / 1024} MB，" +
                                "本骨架为避免 OOM 不加载；请先转换为 CBZ（zip 支持随机访问）。",
                        )
                    }
                    out.write(buffer, 0, read)
                }
            }
            return MemoryBlockSource(out.toByteArray())
        }

        /**
         * 解析 tar 头块序列。返回所有普通文件条目（含非图片），过滤交给调用方。
         */
        internal fun parseTar(source: BlockSource): List<TarRef> {
            val refs = ArrayList<TarRef>()
            var offset = 0L
            var longName: String? = null
            while (offset + BLOCK <= source.size) {
                val header = source.readAt(offset, BLOCK)
                if (header.all { it.toInt() == 0 }) break // 两个全零块表示结束（这里只看一个）
                val typeFlag = header[156].toInt().toChar()
                val size = parseNumeric(header, 124, 12)
                val dataOffset = offset + BLOCK
                when (typeFlag) {
                    // GNU long name：下一块的 name 由本块数据给出
                    'L' -> longName = String(source.readAt(dataOffset, size.toInt()), Charsets.ISO_8859_1)
                        .trimEnd('\u0000', '\n')
                    // pax 扩展头（x）与全局头（g）：本骨架忽略，见类注释
                    'x', 'g' -> Unit
                    '0', '\u0000', '7' -> {
                        val entryName = longName ?: readName(header)
                        longName = null
                        refs += TarRef(entryName, dataOffset, size, typeFlag)
                    }
                    else -> longName = null // '5' 目录 / '1' 硬链 / '2' 符号链 ...
                }
                offset = dataOffset + roundUpToBlock(size)
            }
            return refs
        }

        private fun readName(header: ByteArray): String {
            val name = cString(header, 0, 100)
            val prefix = cString(header, 345, 155)
            return if (prefix.isEmpty()) name else "$prefix/$name"
        }

        private fun cString(bytes: ByteArray, offset: Int, length: Int): String {
            var end = offset
            val limit = minOf(offset + length, bytes.size)
            while (end < limit && bytes[end].toInt() != 0) end++
            return String(bytes, offset, end - offset, Charsets.ISO_8859_1)
        }

        private fun roundUpToBlock(size: Long): Long = ((size + BLOCK - 1) / BLOCK) * BLOCK

        /** tar 数字字段：八进制（默认）或 base-256（首字节置高位）。 */
        internal fun parseNumeric(bytes: ByteArray, offset: Int, length: Int): Long {
            if (bytes[offset].toInt() and 0x80 != 0) {
                var value = (bytes[offset].toInt() and 0x7F).toLong()
                for (i in 1 until length) {
                    value = (value shl 8) or (bytes[offset + i].toInt() and 0xFF).toLong()
                }
                return value
            }
            var value = 0L
            for (i in offset until offset + length) {
                val c = bytes[i].toInt()
                if (c == 0 || c == ' '.code) continue // 前导/尾随填充
                val digit = c - '0'.code
                if (digit !in 0..7) break
                value = value * 8 + digit
            }
            return value
        }
    }
}

/** 未压缩 tar 的按块读取抽象（文件 / 内存两种来源）。 */
internal interface BlockSource : Closeable {
    val size: Long
    fun readAt(offset: Long, length: Int): ByteArray
}

internal class FileBlockSource(private val file: File, private val raf: RandomAccessFile = RandomAccessFile(file, "r")) : BlockSource {
    override val size: Long = raf.length()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val buffer = ByteArray(length)
        raf.seek(offset)
        raf.readFully(buffer)
        return buffer
    }

    override fun close() {
        raf.close()
    }
}

internal class MemoryBlockSource(private val bytes: ByteArray) : BlockSource {
    override val size: Long = bytes.size.toLong()

    override fun readAt(offset: Long, length: Int): ByteArray = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)

    override fun close() = Unit
}
