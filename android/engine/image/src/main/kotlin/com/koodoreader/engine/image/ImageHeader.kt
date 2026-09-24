package com.koodoreader.engine.image

/**
 * 纯 JVM 图片头解析：只读固有尺寸，不解码像素。
 *
 * 为什么需要它：漫画单/双页排版与「按需缩放」在**拿到 Bitmap 之前**就要知道
 * 每页宽高（跨页配对、预取预算、进度占位框）。JVM 侧不能依赖
 * `android.graphics.BitmapFactory`，所以这里按容器格式读文件头——同时让
 * `engine:image` 的排版/预算逻辑可以在无 Android SDK 的机器上单测。
 *
 * 宿主侧仍然用 BitmapFactory 解码像素；两者尺寸应一致（若不一致以解码结果为准，
 * 见 ComicViewerHost 的 `onPageDecoded`）。
 */
object ImageHeader {

    data class Size(val width: Int, val height: Int) {
        val isValid: Boolean get() = width > 0 && height > 0
        val isLandscape: Boolean get() = width > height
    }

    /** 解析失败/格式未支持返回 null（不抛异常——损坏页不应让阅读器崩）。 */
    fun read(bytes: ByteArray): Size? = runCatching { parse(bytes) }.getOrNull()?.takeIf { it.isValid }

    private fun parse(b: ByteArray): Size? = when {
        isPng(b) -> png(b)
        isJpeg(b) -> jpeg(b)
        isGif(b) -> gif(b)
        isBmp(b) -> bmp(b)
        isWebp(b) -> webp(b)
        isSvg(b) -> svg(b)
        else -> null // AVIF/HEIF（ISO-BMFF ispe）留待需要时补：ADR §8 待办
    }

    // ---- PNG ----------------------------------------------------------------

    private fun isPng(b: ByteArray) = b.size > 24 && b.u8(0) == 0x89 && b.u8(1) == 0x50 &&
        b.u8(2) == 0x4E && b.u8(3) == 0x47

    private fun png(b: ByteArray): Size? {
        // 8B 签名 + 4B 长度 + "IHDR" → 宽高各 4B 大端
        if (b.u8(12) != 0x49 || b.u8(13) != 0x48 || b.u8(14) != 0x44 || b.u8(15) != 0x52) return null
        return Size(b.be32(16), b.be32(20))
    }

    // ---- JPEG ---------------------------------------------------------------

    private fun isJpeg(b: ByteArray) = b.size > 4 && b.u8(0) == 0xFF && b.u8(1) == 0xD8

    private fun jpeg(b: ByteArray): Size? {
        var i = 2
        while (i + 3 < b.size) {
            if (b.u8(i) != 0xFF) { i++; continue }
            var marker = b.u8(i + 1)
            // 填充字节 0xFF 可以重复出现
            while (marker == 0xFF && i + 2 < b.size) {
                i++
                marker = b.u8(i + 1)
            }
            if (marker == 0xD8 || marker == 0x01 || (marker in 0xD0..0xD7)) { i += 2; continue }
            if (marker == 0xD9) return null // EOI，没找到 SOF
            val length = b.be16(i + 2)
            if (length < 2) return null
            // SOF0..SOF15（除 DHT=C4 / JPG=C8 / DAC=CC）
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                // 段内：长度(2) 精度(1) 高(2) 宽(2)
                return Size(b.be16(i + 7), b.be16(i + 5))
            }
            i += 2 + length
        }
        return null
    }

    // ---- GIF ----------------------------------------------------------------

    private fun isGif(b: ByteArray) = b.size > 10 && b.u8(0) == 0x47 && b.u8(1) == 0x49 && b.u8(2) == 0x46

    private fun gif(b: ByteArray) = Size(b.le16(6), b.le16(8))

    // ---- BMP ----------------------------------------------------------------

    private fun isBmp(b: ByteArray) = b.size > 26 && b.u8(0) == 0x42 && b.u8(1) == 0x4D

    private fun bmp(b: ByteArray) = Size(b.le32(18), kotlin.math.abs(b.le32(22)))

    // ---- WebP ---------------------------------------------------------------

    private fun isWebp(b: ByteArray): Boolean =
        b.size > 30 && b.u8(0) == 0x52 && b.u8(1) == 0x49 && b.u8(2) == 0x46 && b.u8(3) == 0x46 &&
            b.u8(8) == 0x57 && b.u8(9) == 0x45 && b.u8(10) == 0x42 && b.u8(11) == 0x50

    private fun webp(b: ByteArray): Size? = when {
        // "VP8 " 有损：帧头里 14 bit 宽高（位于 chunk payload 偏移 6/8）
        b.u8(12) == 0x56 && b.u8(13) == 0x50 && b.u8(14) == 0x38 && b.u8(15) == 0x20 ->
            Size(b.le16(26) and 0x3FFF, b.le16(28) and 0x3FFF)
        // "VP8L" 无损：位打包，宽高 = 14 bit 各一
        b.u8(12) == 0x56 && b.u8(13) == 0x50 && b.u8(14) == 0x38 && b.u8(15) == 0x4C -> {
            val bits = b.le32(21)
            Size((bits and 0x3FFF) + 1, ((bits shr 14) and 0x3FFF) + 1)
        }
        // "VP8X" 扩展：24 bit 画布宽高（减一）
        b.u8(12) == 0x56 && b.u8(13) == 0x50 && b.u8(14) == 0x38 && b.u8(15) == 0x58 ->
            Size(b.le24(24) + 1, b.le24(27) + 1)
        else -> null
    }

    // ---- SVG（桌面 COMIC_IMAGE_EXTS 含 svg） --------------------------------

    private fun isSvg(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 512), Charsets.ISO_8859_1).trimStart()
        return head.startsWith("<?xml") && head.contains("<svg") || head.startsWith("<svg")
    }

    private val SVG_WIDTH = Regex("""\bwidth\s*=\s*["']\s*([0-9.]+)""")
    private val SVG_HEIGHT = Regex("""\bheight\s*=\s*["']\s*([0-9.]+)""")
    private val SVG_VIEWBOX = Regex("""\bviewBox\s*=\s*["']\s*[0-9.eE+-]+\s+[0-9.eE+-]+\s+([0-9.eE+-]+)\s+([0-9.eE+-]+)""")

    private fun svg(b: ByteArray): Size? {
        val text = String(b, 0, minOf(b.size, 4096), Charsets.ISO_8859_1)
        val w = SVG_WIDTH.find(text)?.groupValues?.get(1)?.toFloatOrNull()
        val h = SVG_HEIGHT.find(text)?.groupValues?.get(1)?.toFloatOrNull()
        if (w != null && h != null && w > 0 && h > 0) return Size(w.toInt(), h.toInt())
        val vb = SVG_VIEWBOX.find(text) ?: return null
        val vw = vb.groupValues[1].toFloatOrNull() ?: return null
        val vh = vb.groupValues[2].toFloatOrNull() ?: return null
        return Size(vw.toInt(), vh.toInt())
    }

    // ---- 字节读取 ------------------------------------------------------------

    private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

    private fun ByteArray.be16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

    private fun ByteArray.le16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

    private fun ByteArray.be32(i: Int): Int =
        (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)

    private fun ByteArray.le32(i: Int): Int =
        u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)

    private fun ByteArray.le24(i: Int): Int = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
}
