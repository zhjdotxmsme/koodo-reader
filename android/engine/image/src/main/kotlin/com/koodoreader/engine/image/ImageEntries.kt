package com.koodoreader.engine.image

import com.koodoreader.core.importer.ComicCover

/**
 * 漫画页条目过滤 —— **唯一事实源是 P1 的 `core:importer ComicCover`**。
 *
 * 桌面侧同一张清单来自 `src/components/importLocal` 的 `COMIC_IMAGE_EXTS`，
 * P1 已把它搬进 Kotlin（[ComicCover.IMAGE_EXTS]）。本模块不复制常量，直接引用，
 * 这样「导入时数出来的页数」与「阅读器里能翻的页数」不可能分叉。
 */
object ImageEntries {

    /** 与 P1/桌面一致的图片扩展名白名单。 */
    val EXTS: Set<String> get() = ComicCover.IMAGE_EXTS

    /**
     * 取条目的扩展名（小写）。`jpg/jfif/pjpeg/pjp` 归一为 `jpeg`，
     * 与 `ComicCover` 内部 `extOf` 归一规则一致（用于封面文件名 `<bookKey>.<ext>`）。
     */
    fun extOf(entryName: String): String {
        val name = baseName(entryName)
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jfif", "pjpeg", "pjp" -> "jpeg"
            else -> ext
        }
    }

    /** 目录条目（tar 的 `foo/`、zip 的显式目录项）不是页。 */
    fun isDirectory(entryName: String): Boolean = entryName.endsWith("/")

    /**
     * 是否为漫画页条目：非目录 + 扩展名在白名单内。
     * 与 `ComicCover.extract` 的过滤条件逐字对齐（它用
     * `substringAfterLast('/')` 后再比扩展名）。
     */
    fun isImage(entryName: String): Boolean {
        if (isDirectory(entryName)) return false
        val ext = baseName(entryName).substringAfterLast('.', "").lowercase()
        return ext in EXTS
    }

    /** 去掉目录前缀后的文件名。 */
    fun baseName(entryName: String): String = entryName.substringAfterLast('/')
}
