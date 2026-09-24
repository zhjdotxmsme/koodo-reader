package com.koodoreader.engine.image

import java.io.File

/**
 * CBR / `.rar` —— **暂不原生**（TODO，不是遗漏）。
 *
 * ## 为什么不做（结论先写）
 * 1. **许可证**：RAR5 解压的事实标准实现（unrar / libunrar / junrar /
 *    7-Zip-JBinding 的 RAR 部分）都带 UnRAR restriction —— 它不是 OSI 认可的开源
 *    许可证，且要求「不得用于实现 RAR 压缩器」。塞进 AGPL-3.0 的 Android 主仓会
 *    引入额外的分发义务，需要单独法务判断（ADR §4.2）。
 * 2. **没有可用的纯 Java RAR5**：junrar 只完整支持 RAR4，RAR5 支持不完整；
 *    也就是说「像 7z 那样用纯 Java 库绕开 .so」这条路在 RAR 上不存在。
 * 3. **.so 与 16 KB 页对齐**：libunrar / 7-Zip-JBinding 都是 native 库，
 *    预编译产物未必按 16 KB 页对齐；Android 15+ 的 16 KB page size 设备
 *    （Google Play 2025-11 起对 targetSdk 35+ 的硬要求）会拒绝加载未对齐 .so。
 *    要引入就得自己用 NDK r27+ 加 `-Wl,-z,max-page-size=16384` 重编 + ABI 拆分，
 *    成本远高于 CBR 的实际占比（ADR §5）。
 *
 * ## 现在的行为（可执行的部分）
 *  - [ArchiveExtractors.kindOf] 能正确识别 RAR4/RAR5 魔数；
 *  - 宿主看到 [ArchiveKind.support] == [ArchiveKind.Support.DEFERRED] 时，
 *    把 CBR **路由回兜底岛**（WebView + `public/lib/libunrar` wasm）渲染，
 *    不抛异常、不弹技术错误给用户；
 *  - 用户侧可选缓解：引导「解压为 CBZ / 导入散图目录」，两条路都走已验证的原生路径。
 *
 * ## 立项时需要的东西（交接清单）
 *  - 许可证结论（是否接受 UnRAR restriction）；
 *  - NDK r27+ 自编译的 libunrar + 16 KB 对齐验证（`llvm-readelf -l` 看 LOAD 段对齐）；
 *  - ABI 拆分或 dynamic feature 的体积方案；
 *  - 与 [ArchiveExtractor] 同契约的实现（页表 + 按需读页 + close）。
 */
object RarExtractor {

    /** RAR 是否已原生可读 —— 当前恒为 false，宿主路由表据此回退兜底岛。 */
    const val NATIVE_SUPPORTED: Boolean = false

    /** 走兜底岛渲染（与 ADR-003 的四态生命周期一致）。 */
    const val FALLBACK_ROUTE: String = "feature/webisland (WebView + public/lib/libunrar wasm)"

    fun open(file: File): ArchiveExtractor = throw UnsupportedArchiveException(
        "CBR/rar 暂不原生（UnRAR 许可证 + .so 16 KB 页对齐，见 engine/image RarExtractor 注释）：" +
            "请把 ${file.name} 路由到兜底岛渲染，或提示用户解压为 CBZ。",
    )
}
