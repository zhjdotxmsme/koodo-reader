package com.koodoreader.engine.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 图片头解析：排版/预算需要的固有尺寸，JVM 侧不依赖 BitmapFactory。 */
class ImageHeaderTest {

    @Test
    fun `png gif and jpeg sizes are read from the header`() {
        assertEquals(ImageHeader.Size(1200, 1800), ImageHeader.read(Pics.png(1200, 1800)))
        assertEquals(ImageHeader.Size(800, 600), ImageHeader.read(Pics.gif(800, 600)))
        assertEquals(ImageHeader.Size(1920, 1080), ImageHeader.read(Pics.jpeg(1920, 1080)))
    }

    @Test
    fun `bmp and webp are read too`() {
        val bmp = ByteArray(30)
        bmp[0] = 'B'.code.toByte()
        bmp[1] = 'M'.code.toByte()
        bmp[18] = 0x40; bmp[19] = 0x01 // 320 (LE)
        bmp[22] = 0xF0.toByte(); bmp[23] = 0x00 // 240 (LE)
        assertEquals(ImageHeader.Size(320, 240), ImageHeader.read(bmp))

        // VP8X：RIFF/WEBP + 24 bit 画布宽高（存的是 width-1 / height-1）
        val webp = ByteArray(32)
        "RIFF".forEachIndexed { i, c -> webp[i] = c.code.toByte() }
        "WEBP".forEachIndexed { i, c -> webp[8 + i] = c.code.toByte() }
        "VP8X".forEachIndexed { i, c -> webp[12 + i] = c.code.toByte() }
        webp[24] = 0x8F.toByte(); webp[25] = 0x01 // 399
        webp[27] = 0x2B; webp[28] = 0x01 // 299
        assertEquals(ImageHeader.Size(400, 300), ImageHeader.read(webp))
    }

    @Test
    fun `svg falls back to the viewBox when width and height are absent`() {
        val withAttrs = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"640\" height=\"960\"></svg>"
        assertEquals(ImageHeader.Size(640, 960), ImageHeader.read(withAttrs.toByteArray()))

        val withViewBox = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 800 1200\"></svg>"
        assertEquals(ImageHeader.Size(800, 1200), ImageHeader.read(withViewBox.toByteArray()))
    }

    @Test
    fun `malformed or unknown data degrades to null instead of throwing`() {
        assertNull(ImageHeader.read(ByteArray(0)))
        assertNull(ImageHeader.read("not an image".toByteArray()))
        assertNull(ImageHeader.read(Pics.png(100, 100).copyOf(12)), "截断的 PNG")
        assertNull(ImageHeader.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00)), "截断的 JPEG")
        assertNull(ImageHeader.read(ByteArray(64)), "全零")
    }

    @Test
    fun `zero sized images are treated as unknown`() {
        assertNull(ImageHeader.read(Pics.png(0, 10)))
        assertNull(ImageHeader.read(Pics.gif(10, 0)))
    }
}
