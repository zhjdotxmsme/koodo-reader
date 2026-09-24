package com.koodoreader.engine.image

import java.io.File

/**
 * CB7 / `.7z` —— 方案已定、依赖未接（TODO 骨架）。
 *
 * ## 结论：CB7 可以原生，而且**不需要 .so**
 * 与 RAR 的关键差别：7z 有纯 Java 的开源实现。推荐
 * `org.apache.commons:commons-compress:1.27.1` 的 `SevenZFile`：
 *  - Apache-2.0，与主仓 AGPL-3.0 兼容；
 *  - 纯 Java（LZMA/LZMA2/BCJ 都在 Java 侧），**零 .so**，16 KB 页对齐问题不存在；
 *  - 体积约 1 MB jar（可被 R8 缩减），远小于引入 native 库的代价；
 *  - 只支持读（`SevenZFile` 无写能力）—— 漫画阅读只需要读，正好。
 *
 * ## 为什么不在这张卡里接
 * `:engine:image` 目前**运行时零依赖**（同 :engine:cfi / :engine:text），
 * 加依赖会让本卡的离线单测链路（`gradle :engine:image:test`）依赖网络下载。
 * 接线步骤写在 `docs/patches/p5-image.patch` §2，两步：
 *   1. `implementation 'org.apache.commons:commons-compress:1.27.1'`
 *   2. 打开 [SevenZExtractor.open] 的实现（下面的 `TODO` 处），
 *      页表/读页逻辑与 [ZipExtractor] 完全同形（条目名 → [ImageEntries] →
 *      [NaturalOrder] → `SevenZFile.getInputStream(entry)`）。
 * 在此之前 CB7 与 CBR 一样回落兜底岛（桌面用 `public/lib/7z-wasm`）。
 */
object SevenZExtractor {

    /** 7z 是否已原生可读 —— 依赖未接前恒为 false。 */
    const val NATIVE_SUPPORTED: Boolean = false

    /** 计划引入的依赖（接线时启用，见 patch §2）。 */
    const val PLANNED_DEPENDENCY: String = "org.apache.commons:commons-compress:1.27.1"

    fun open(file: File): ArchiveExtractor = throw UnsupportedArchiveException(
        "CB7/7z 尚未接线（计划用纯 Java 的 $PLANNED_DEPENDENCY，无 .so）：" +
            "接线前请把 ${file.name} 路由到兜底岛渲染（桌面侧 public/lib/7z-wasm）。",
    )

    // TODO(P5b)：commons-compress 接线后，这里补 SevenZFile 实现：
    //   SevenZFile(file).use { seven ->
    //       val names = seven.entries.filter { !it.isDirectory }.map { it.name }
    //           .filter { ImageEntries.isImage(it) }  → NaturalOrder.sorted
    //       readPage(i) = seven.getInputStream(entry).use { it.readBytes() }
    //   }
    // 注意：SevenZFile 是流式游标（顺序读更友好），随机跳页需要 reopen 或按块缓存，
    // 由 DefaultPageLoader 的「当前页 + 后 3 页」窗口天然覆盖（顺序性足够）。
}
