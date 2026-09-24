package com.koodoreader.engine.image

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * CBZ / `.zip` 实现（本卡的主路径）。
 *
 * 与 P1 的一致性：条目过滤用 [ImageEntries]（= `ComicCover.IMAGE_EXTS`），
 * 排序用 [NaturalOrder]（= `ComicCover.naturalComparator` 的行为），因此
 * **`pages[0]` 就是封面抽取用的那张图**、`pageCount` 就是导入时写进
 * `books.page` 的数字。一致性由 `ZipExtractorTest` 的行为断言守住。
 */
class ZipExtractor private constructor(
    private val file: File,
    private val zip: ZipFile,
    override val entries: List<PageEntry>,
) : ArchiveExtractor {

    override val kind: ArchiveKind = ArchiveKind.ZIP

    override val name: String = file.name

    override val fileSizeBytes: Long = file.length()

    override fun readPage(index: Int): ByteArray = openPage(index).use { it.readBytes() }

    override fun openPage(index: Int): InputStream {
        val entryName = entries[index].name
        // 与 ComicCover.readEntryBytes 同策略：先精确匹配，再大小写不敏感兜底
        val entry = zip.getEntry(entryName)
            ?: zip.entries().asSequence().firstOrNull { it.name.equals(entryName, ignoreCase = true) }
            ?: throw java.io.FileNotFoundException("zip 条目丢失: $entryName")
        return zip.getInputStream(entry)
    }

    override fun close() {
        zip.close()
    }

    companion object {

        fun open(file: File): ZipExtractor {
            val zip = ZipFile(file)
            return try {
                ZipExtractor(file, zip, listPages(zip))
            } catch (t: Throwable) {
                runCatching { zip.close() }
                throw t
            }
        }

        /** 图片条目 → 页号（natural 顺序）。 */
        internal fun listPages(zip: ZipFile): List<PageEntry> {
            val imageNames = zip.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .filter { ImageEntries.isImage(it) }
                .toList()
            return NaturalOrder.sorted(imageNames).mapIndexed { index, entryName ->
                val size = zip.getEntry(entryName)?.size ?: -1L
                PageEntry(
                    index = index,
                    name = entryName,
                    ext = ImageEntries.extOf(entryName),
                    // data descriptor 场景下 size 可能是 -1（流式写入的 zip）
                    sizeBytes = if (size < 0) -1L else size,
                )
            }
        }

        /** 便于测试与宿主：从任意 [ZipEntry] 序列建页表（不持有文件）。 */
        internal fun pageNames(entries: Sequence<ZipEntry>): List<String> =
            NaturalOrder.sorted(entries.filter { !it.isDirectory }.map { it.name }.filter { ImageEntries.isImage(it) }.toList())
    }
}
