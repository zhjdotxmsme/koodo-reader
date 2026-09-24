package com.koodoreader.engine.image

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 归档解包抽象 —— 漫画阅读器的 IO 边界。
 *
 * 设计意图（P5）：
 *  - `PageLoader` 只依赖本接口，因此「懒加载窗口」可以在内存里被单测覆盖，
 *    不需要真的 archive / Android 组件；
 *  - 每种容器一个实现（zip/tar/tree 已实现，7z/rar 见骨架），扩展名与卷宗的
 *    探测集中在 [ArchiveKind] / [ArchiveExtractors]，不在业务代码里散落判断。
 *
 * 线程约定：实现**不保证**线程安全；`DefaultPageLoader` 用单锁串行化访问，
 * UI/预取共用同一个 loader 实例（与桌面 comic-book.js 的串行 getPage 对齐）。
 */
interface ArchiveExtractor : Closeable {

    val kind: ArchiveKind

    /** 仅文件名（不含目录），用于日志与错误信息。 */
    val name: String

    /** 归档文件字节数；目录树实现返回其包含的图片字节总和。 */
    val fileSizeBytes: Long

    /** 已按 natural 顺序排好的图片条目（index 即页号，0 = 封面，与 P1 一致）。 */
    val entries: List<PageEntry>

    val pageCount: Int get() = entries.size

    /** 读取第 [index] 页的原始字节（未解码）。越界抛 [IndexOutOfBoundsException]。 */
    fun readPage(index: Int): ByteArray

    /** 流式读取第 [index] 页；调用方负责 close（`use {}`）。 */
    fun openPage(index: Int): InputStream

    override fun close()

    fun entry(index: Int): PageEntry = entries[index]
}

/** 归档里的一页（图片条目）。 */
data class PageEntry(
    val index: Int,
    /** 归档内全路径（`ch01/001.jpg`）；目录树实现用 `/` 归一化的相对路径。 */
    val name: String,
    /** 归一化扩展名（`jpg` → `jpeg`），与 P1 封面文件名一致。 */
    val ext: String,
    /** 未压缩字节数，未知为 -1（zip 的 data descriptor 场景）。 */
    val sizeBytes: Long = -1L,
)

/**
 * 容器类型 + 原生支持状态。`support` 是**照实**的，不做乐观标注：
 * 骨架就是 [Support.PLANNED]/[Support.DEFERRED]，UI 据此决定是否回退兜底岛。
 */
enum class ArchiveKind(
    val label: String,
    val extensions: Set<String>,
    val support: Support,
) {
    /** CBZ / .zip */
    ZIP("zip", setOf("cbz", "zip"), Support.READY),

    /** CBT / .tar（未压缩，可随机访问） */
    TAR("tar", setOf("cbt", "tar"), Support.READY),

    /** .tar.gz / .tgz（CBT 的压缩变体，见 TarExtractor 的体积上限取舍） */
    TAR_GZIP("tar.gz", setOf("tgz"), Support.READY),

    /** CB7 / .7z —— 纯 Java 方案已接线（commons-compress SevenZFile，见 [SevenZExtractor]） */
    SEVEN_ZIP("7z", setOf("cb7", "7z"), Support.READY),

    /** CBR / .rar —— 暂不原生（许可证 + .so），维持兜底岛 */
    RAR("rar", setOf("cbr", "rar"), Support.DEFERRED),

    /** 散图目录（SAF document tree 导出后的真实目录） */
    DIRECTORY("dir", emptySet(), Support.READY),

    UNKNOWN("?", emptySet(), Support.UNSUPPORTED);

    enum class Support {
        /** 本模块已能读。 */
        READY,

        /** 方案已定、依赖未接（7z）。 */
        PLANNED,

        /** 明确不做原生，走兜底岛（rar）。 */
        DEFERRED,

        /** 不是漫画容器。 */
        UNSUPPORTED,
    }

    val nativelyReadable: Boolean get() = support == Support.READY

    companion object {
        /** 魔数探测（比扩展名可靠：`.cbz` 里其实可能是 rar）。 */
        fun sniff(header: ByteArray): ArchiveKind? {
            if (header.matches(0, 0x50, 0x4B, 0x03, 0x04) ||
                header.matches(0, 0x50, 0x4B, 0x05, 0x06) ||
                header.matches(0, 0x50, 0x4B, 0x07, 0x08)
            ) return ZIP
            if (header.matches(0, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)) return RAR
            if (header.matches(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return SEVEN_ZIP
            if (header.matches(0, 0x1F, 0x8B)) return TAR_GZIP
            // POSIX ustar：magic 位于 257 字节处
            if (header.matches(257, 0x75, 0x73, 0x74, 0x61, 0x72)) return TAR
            return null
        }

        fun ofExtension(fileName: String): ArchiveKind {
            val lower = fileName.lowercase()
            if (lower.endsWith(".tar.gz")) return TAR_GZIP
            val ext = lower.substringAfterLast('.', "")
            return entries.firstOrNull { ext in it.extensions } ?: UNKNOWN
        }
    }
}

private fun ByteArray.matches(offset: Int, vararg expected: Int): Boolean {
    if (offset + expected.size > size) return false
    for (i in expected.indices) {
        if (this[offset + i].toInt() and 0xFF != expected[i]) return false
    }
    return true
}

/** 容器类型已知但当前不能原生读（rar）或数据损坏。 */
class UnsupportedArchiveException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * 归档打开入口：魔数优先、扩展名兜底，然后分派到具体实现。
 *
 * 消费方（宿主 ViewModel）应先看 [ArchiveKind.support]，`DEFERRED` 的 CBR
 * 直接走兜底岛，不要捕异常当控制流。
 */
object ArchiveExtractors {

    /** 读前 512 字节用于魔数（tar 的 magic 在 257）。 */
    private const val MAGIC_BYTES = 512

    fun kindOf(file: File): ArchiveKind {
        if (file.isDirectory) return ArchiveKind.DIRECTORY
        if (!file.isFile) return ArchiveKind.UNKNOWN
        val header = runCatching {
            file.inputStream().use { stream ->
                val buffer = ByteArray(MAGIC_BYTES)
                val read = stream.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            }
        }.getOrDefault(ByteArray(0))
        return ArchiveKind.sniff(header) ?: ArchiveKind.ofExtension(file.name)
    }

    /**
     * 打开漫画归档。不支持的容器抛 [UnsupportedArchiveException]，
     * 消息里带上「该走哪条路」，便于宿主直接把提示透给用户。
     */
    fun open(file: File): ArchiveExtractor = when (kindOf(file)) {
        ArchiveKind.ZIP -> ZipExtractor.open(file)
        ArchiveKind.TAR, ArchiveKind.TAR_GZIP -> TarExtractor.open(file)
        ArchiveKind.DIRECTORY -> TreeExtractor.open(file)
        ArchiveKind.SEVEN_ZIP -> SevenZExtractor.open(file)
        // CBR 明确不做原生：错误信息写在它自己的骨架文件里（避免两处漂移）
        ArchiveKind.RAR -> RarExtractor.open(file)
        ArchiveKind.UNKNOWN -> throw UnsupportedArchiveException("无法识别的漫画容器: ${file.name}")
    }

    /** 散图目录入口（SAF tree 已落盘为真实目录时）。 */
    fun openDirectory(root: File): ArchiveExtractor = TreeExtractor.open(root)

    /** 该容器当前是否能原生读（宿主路由表用）。 */
    fun isNativelyReadable(file: File): Boolean = kindOf(file).nativelyReadable

    fun nativeReadableKinds(): Set<ArchiveKind> =
        ArchiveKind.entries.filter { it.nativelyReadable }.toSet()
}
