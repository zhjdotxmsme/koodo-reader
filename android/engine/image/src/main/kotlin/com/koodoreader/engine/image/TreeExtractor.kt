package com.koodoreader.engine.image

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * 散图目录实现（`TreeExt`）：SAF document tree 落盘后的真实目录，或桌面
 * 「导入文件夹版的漫画」场景。
 *
 * 语义与归档版本保持一致：
 *  - 递归收集图片文件（用 [ImageEntries] 的同一张扩展名清单）；
 *  - 页序 = **相对路径 natural 排序**（`ch2/9.jpg` < `ch2/10.jpg`，
 *    章节目录也参与比较，所以 `ch1/...` 一定排在 `ch2/...` 前）；
 *  - `0` 号页仍是封面（与 P1 的 CBZ 行为对齐；目录版桌面走同一套排序）。
 *
 * 跳过项：隐藏文件/目录（`.` 前缀）、macOS 资源分支（`._` 前缀）、符号链接
 * （避免 SAF 导出的循环目录把递归带飞）。
 */
class TreeExtractor private constructor(
    private val root: File,
    override val entries: List<PageEntry>,
    private val files: List<File>,
) : ArchiveExtractor {

    override val kind: ArchiveKind = ArchiveKind.DIRECTORY

    override val name: String = root.name

    override val fileSizeBytes: Long = files.sumOf { it.length() }

    override fun readPage(index: Int): ByteArray = files[index].readBytes()

    override fun openPage(index: Int): InputStream = ByteArrayInputStream(readPage(index))

    override fun close() = Unit

    companion object {

        fun open(root: File): TreeExtractor {
            require(root.isDirectory) { "不是目录: ${root.name}" }
            val collected = collect(root)
            val entries = collected.mapIndexed { index, item ->
                PageEntry(index, item.relativePath, ImageEntries.extOf(item.relativePath), item.file.length())
            }
            return TreeExtractor(root, entries, collected.map { it.file })
        }

        internal data class Item(val file: File, val relativePath: String)

        /** 递归收集 + 相对路径 natural 排序。 */
        internal fun collect(root: File): List<Item> {
            val rootPath = root.toPath()
            val items = ArrayList<Item>()
            root.walkTopDown()
                .onEnter { dir ->
                    !dir.name.startsWith(".") && !java.nio.file.Files.isSymbolicLink(dir.toPath())
                }
                .filter { file ->
                    file.isFile &&
                        !file.name.startsWith(".") &&
                        !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                        ImageEntries.isImage(file.name)
                }
                .forEach { file ->
                    val relative = rootPath.relativize(file.toPath()).joinToString("/") { it.toString() }
                    items += Item(file, relative)
                }
            return items.sortedWith(compareBy(NaturalOrder) { it.relativePath })
        }
    }
}
