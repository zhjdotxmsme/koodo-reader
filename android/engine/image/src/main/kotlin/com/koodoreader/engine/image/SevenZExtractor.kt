package com.koodoreader.engine.image

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/**
 * CB7 / `.7z` 实现（P5-CB7 补强，卡 `t-mufbaoux-yjmwrl`）。
 *
 * ## 为什么 CB7 可以原生，而 CBR 不行
 * 7z 有纯 Java 的开源实现：`org.apache.commons:commons-compress` 的 [SevenZFile]
 * （Apache-2.0，与主仓 AGPL-3.0 兼容）。解码器全在 Java 侧（LZMA/LZMA2 由
 * `org.tukaani:xz` 提供，其余方法 commons-compress 自带），因此
 * **零 `.so`** —— Android 15+ 的 16 KB 页对齐问题在这里根本不存在。
 * CBR 需要 UnRAR 许可证 + native 库，保持 [RarExtractor] 的 `DEFERRED` 状态。
 *
 * 只支持读（commons-compress 的 SevenZFile 无写能力），漫画阅读只需要读。
 *
 * ## 页表一致性（与 P1 导入规则）
 * 条目过滤走 [ImageEntries]（= `ComicCover.IMAGE_EXTS`）、排序走 [NaturalOrder]，
 * 与 [ZipExtractor] 完全同形，所以「导入时数出来的页数 / 封面 = 第 0 页」在 CB7 上同样成立。
 *
 * ## 读页策略：每次读页各开一个 [SevenZFile]
 * `SevenZFile` 是**顺序游标**，随机跳页要么重新打开、要么把整个固实块留在内存里。
 * 这里选择「每次 `openPage` 重新打开 + 顺序前进到目标条目」：
 *  - 正确性最好（不依赖游标位置，跳页/回翻都不会读到脏数据）；
 *  - 内存可控（不驻留固实块；页字节由 [DefaultPageLoader] 的窗口策略管理）；
 *  - 代价是每次读页要重新解析头 —— 顺序阅读时命中窗口缓存，实际只发生在换页时。
 * `close()` 因此是空操作：本类不持有任何需要释放的原生/句柄资源。
 *
 * ## 明确处理的异常
 *  - **头部加密**（`7z a -mhe=on`）：无法在无密码下列出条目 → 打开即失败，提示走兜底岛；
 *  - **条目加密**：条目可列举但读取时要求密码 → 读页时失败，同样提示；
 *  - **损坏 / 非 7z**：打开或读页失败 → [UnsupportedArchiveException] 带底层原因；
 *  - **超大页**：单页解压后超过 [MAX_PAGE_BYTES] 时不读，避免低端机 OOM。
 */
class SevenZExtractor private constructor(
    private val file: File,
    override val entries: List<PageEntry>,
) : ArchiveExtractor {

    override val kind: ArchiveKind = ArchiveKind.SEVEN_ZIP

    override val name: String = file.name

    override val fileSizeBytes: Long = file.length()

    override fun readPage(index: Int): ByteArray = openPage(index).use { it.readBytes() }

    override fun openPage(index: Int): InputStream {
        if (index !in entries.indices) {
            throw IndexOutOfBoundsException("page index $index out of range [0..${entries.lastIndex}]")
        }
        val wanted = entries[index]
        val seven = openArchive()
        var page: InputStream? = null
        try {
            var entry: SevenZArchiveEntry? = seven.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && normalise(entry.name) == wanted.name) {
                    if (entry.size > MAX_PAGE_BYTES) {
                        throw UnsupportedArchiveException(
                            "CB7 第 $index 页解压后约 ${entry.size / 1024 / 1024} MB，超过 " +
                                "${MAX_PAGE_BYTES / 1024 / 1024} MB 上限（${file.name}）：" +
                                "请先转换为 CBZ 或单独取出该页。",
                        )
                    }
                    page = seven.getInputStream(entry)
                    break
                }
                entry = seven.nextEntry
            }
        } catch (e: PasswordRequiredException) {
            closeQuietly(seven)
            throw encryptedEntry(file.name, wanted.name, e)
        } catch (e: IOException) {
            closeQuietly(seven)
            throw UnsupportedArchiveException(
                "CB7 读页失败（文件损坏或条目异常）：${file.name} / ${wanted.name}（${e.message}）",
                e,
            )
        } catch (t: Throwable) {
            closeQuietly(seven)
            throw t
        }

        val opened = page
        if (opened == null) {
            closeQuietly(seven)
            throw FileNotFoundException("7z 条目丢失: ${wanted.name}（${file.name}）")
        }
        // 流的所有权交给调用方：关闭流时一并关闭它底下的 SevenZFile。
        return OwnedEntryStream(seven, opened)
    }

    /** 无状态实现：每次读页自行打开/关闭 [SevenZFile]。 */
    override fun close() = Unit

    private fun openArchive(): SevenZFile = try {
        SevenZFile.builder().setFile(file).get()
    } catch (e: PasswordRequiredException) {
        throw UnsupportedArchiveException(
            "CB7/7z 已加密（头部加密，需要密码）：${file.name} —— " +
                "请把该文件路由到兜底岛渲染，或提示用户解压为 CBZ。",
            e,
        )
    } catch (e: IOException) {
        throw openingFailed(file.name, e)
    }

    private fun encryptedEntry(name: String, entryName: String, cause: Throwable): UnsupportedArchiveException =
        UnsupportedArchiveException(
            "CB7/7z 条目已加密（需要密码）：$name / $entryName —— " +
                "请把该文件路由到兜底岛渲染，或提示用户解压为 CBZ。",
            cause,
        )

    /** 关闭 [SevenZFile]，忽略关闭期间的次级异常（不能覆盖调用方正在抛的错）。 */
    private fun closeQuietly(seven: SevenZFile) {
        runCatching { seven.close() }
    }

    /** 把 `SevenZFile` 的生命周期绑到对外暴露的页流上。 */
    private class OwnedEntryStream(
        private val seven: SevenZFile,
        private val delegate: InputStream,
    ) : InputStream() {

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun skip(n: Long): Long = delegate.skip(n)

        override fun available(): Int = delegate.available()

        override fun close() {
            try {
                delegate.close()
            } finally {
                runCatching { seven.close() }
            }
        }
    }

    companion object {

        /** 7z 已原生可读（commons-compress 接线完成）。 */
        const val NATIVE_SUPPORTED: Boolean = true

        /** 实际使用的依赖（[PLANNED_DEPENDENCY] 是接线前的历史记录，保留供追溯）。 */
        const val DEPENDENCY: String = "org.apache.commons:commons-compress:1.27.1 (+ org.tukaani:xz:1.10)"

        /** 接线前计划引入的依赖，保留以便追溯（见 docs/p5-image-engine-adr.md §11）。 */
        const val PLANNED_DEPENDENCY: String = "org.apache.commons:commons-compress:1.27.1"

        /**
         * 单页解压上限（64 MB，与 [DefaultPageLoader.DEFAULT_MAX_RESIDENT_BYTES] 同量级）：
         * 超限不读，抛可执行的错误，避免把低端机的堆打爆。
         */
        const val MAX_PAGE_BYTES: Long = 64L * 1024 * 1024

        /**
         * 打开失败的统一提示。两类原因都会走到这里，而**用户侧的处理相同**（回退兜底岛）：
         *  - 文件损坏 / 根本不是 7z；
         *  - 7z 合法但用了纯 Java 栈解不了的过滤器 —— 实测 commons-compress 对
         *    **BCJ2**（多输入/输出流编码器，`-m0=BCJ2`）会抛
         *    `IOException: Multi input/output stream coders are not yet supported`。
         *    BCJ2 是 x86 可执行文件的过滤器，图片归档几乎不会用到；一旦遇到就走兜底岛，
         *    与 CBR 的处理口径一致（不假装支持）。
         */
        private fun openingFailed(name: String, cause: IOException): UnsupportedArchiveException =
            UnsupportedArchiveException(
                "CB7/7z 打开失败（文件损坏或不是 7z，或使用了纯 Java 栈不支持的过滤器如 BCJ2）：" +
                    "$name（${cause.message}）—— 若文件本身完好，请路由到兜底岛渲染。",
                cause,
            )

        fun open(file: File): SevenZExtractor {
            val seven = try {
                SevenZFile.builder().setFile(file).get()
            } catch (e: PasswordRequiredException) {
                throw UnsupportedArchiveException(
                    "CB7/7z 已加密（头部加密，需要密码）：${file.name} —— " +
                        "请把该文件路由到兜底岛渲染，或提示用户解压为 CBZ。",
                    e,
                )
            } catch (e: IOException) {
                throw openingFailed(file.name, e)
            }
            return try {
                SevenZExtractor(file, listPages(seven))
            } catch (e: PasswordRequiredException) {
                throw UnsupportedArchiveException(
                    "CB7/7z 已加密（头部加密，需要密码）：${file.name} —— " +
                        "请把该文件路由到兜底岛渲染，或提示用户解压为 CBZ。",
                    e,
                )
            } catch (e: IOException) {
                // 列举条目也要解码头部，因此损坏 / 不支持的过滤器（BCJ2）会在这里就失败
                throw openingFailed(file.name, e)
            } finally {
                runCatching { seven.close() }
            }
        }

        /** 图片条目 → 页号（natural 顺序），与 [ZipExtractor.listPages] 同形。 */
        internal fun listPages(seven: SevenZFile): List<PageEntry> {
            val names = ArrayList<String>()
            val sizes = HashMap<String, Long>()
            var entry = seven.nextEntry
            while (entry != null) {
                val name = normalise(entry.name)
                if (!entry.isDirectory && ImageEntries.isImage(name)) {
                    names.add(name)
                    sizes[name] = entry.size
                }
                entry = seven.nextEntry
            }
            return NaturalOrder.sorted(names).mapIndexed { index, entryName ->
                PageEntry(
                    index = index,
                    name = entryName,
                    ext = ImageEntries.extOf(entryName),
                    sizeBytes = sizes[entryName] ?: -1L,
                )
            }
        }

        /**
         * 7z 存在「按调用方路径原样存名」的行为：Windows 上用 `7z a x.7z pages\*` 建出来的
         * 归档，条目名会带反斜杠（`pages\1.jpg`）。本模块的页表约定用 `/`
         * （见 [PageEntry.name] 与 [ImageEntries.baseName]），所以在这里统一归一化。
         */
        internal fun normalise(entryName: String): String = entryName.replace('\\', '/')
    }
}
