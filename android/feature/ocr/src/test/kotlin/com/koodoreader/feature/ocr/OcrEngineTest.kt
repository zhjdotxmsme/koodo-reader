package com.koodoreader.feature.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Script→model mapping and the OCR text normaliser. The normaliser is the
 * desktop `cleanWindowsOcrText` port (src/utils/main/ocr-util.js): OCR engines
 * break lines and space out CJK runs, and both must be undone before the text
 * is indexed or searched.
 */
class OcrEngineTest {

    @Test
    fun `language tags select the ML Kit script`() {
        assertEquals(OcrScript.CHINESE, OcrScript.forLanguageTag("zh-CN"))
        assertEquals(OcrScript.CHINESE, OcrScript.forLanguageTag("zh_TW"))
        assertEquals(OcrScript.JAPANESE, OcrScript.forLanguageTag("ja"))
        assertEquals(OcrScript.KOREAN, OcrScript.forLanguageTag("ko-KR"))
        assertEquals(OcrScript.DEVANAGARI, OcrScript.forLanguageTag("hi"))
        assertEquals(OcrScript.LATIN, OcrScript.forLanguageTag("en"))
        assertEquals(OcrScript.LATIN, OcrScript.forLanguageTag(null))
        assertEquals(OcrScript.LATIN, OcrScript.forLanguageTag(""))
    }

    @Test
    fun `manifest meta-data value lists the requested models`() {
        assertEquals("ocr", OcrScript.manifestValue(listOf(OcrScript.LATIN)))
        assertEquals(
            "ocr,ocr_chinese,ocr_japanese",
            OcrScript.manifestValue(listOf(OcrScript.LATIN, OcrScript.CHINESE, OcrScript.JAPANESE)),
        )
        // Duplicates are collapsed — the manifest token must stay stable.
        assertEquals("ocr_chinese", OcrScript.manifestValue(listOf(OcrScript.CHINESE, OcrScript.CHINESE)))
    }

    @Test
    fun `every script has both artifact coordinates`() {
        OcrScript.entries.forEach { script ->
            assertTrue(script.unbundledArtifact.contains("play-services-mlkit-text-recognition"))
            assertTrue(script.bundledArtifact.startsWith("com.google.mlkit:text-recognition"))
            assertTrue(script.bundledBytes > script.unbundledBytes)
        }
    }

    @Test
    fun `normalize joins CJK lines without a space`() {
        assertEquals("扫描的页面文字", OcrTextNormalizer.normalize(listOf("扫描的", "页面文字")))
        assertEquals("第一章开始", OcrTextNormalizer.normalize(listOf("第一章", "开始")))
    }

    @Test
    fun `normalize dehyphenates latin words broken across lines`() {
        assertEquals(
            "international reader",
            OcrTextNormalizer.normalize("inter-\nnational reader"),
        )
        // A hyphen that is not a line break must survive.
        assertEquals("well-known", OcrTextNormalizer.normalize("well-known"))
    }

    @Test
    fun `normalize collapses whitespace and trims`() {
        assertEquals("hello world", OcrTextNormalizer.normalize("  hello \t world \n"))
        assertEquals("", OcrTextNormalizer.normalize("   \n\n  "))
        assertEquals("a b", OcrTextNormalizer.normalize("a\n\n\nb"))
    }

    @Test
    fun `normalize keeps the space between latin and CJK`() {
        assertEquals("阅读 reader", OcrTextNormalizer.normalize("阅读\nreader"))
        assertEquals("reader 阅读", OcrTextNormalizer.normalize("reader\n阅读"))
    }

    @Test
    fun `tokens split latin words and index CJK as uni and bigrams`() {
        assertEquals(listOf("hello", "world"), OcrTextNormalizer.tokens("Hello, world!"))
        assertEquals(
            listOf("阅", "阅读", "读", "读页", "页"),
            OcrTextNormalizer.tokens("阅读页"),
        )
        assertEquals(listOf("三", "三体", "体", "reader"), OcrTextNormalizer.tokens("三体 reader"))
        assertTrue(OcrTextNormalizer.tokens("").isEmpty())
    }

    @Test
    fun `isCjk covers kana hangul and ideographs but not latin`() {
        assertTrue(OcrTextNormalizer.isCjk('中'))
        assertTrue(OcrTextNormalizer.isCjk('あ'))
        assertTrue(OcrTextNormalizer.isCjk('한'))
        assertTrue(!OcrTextNormalizer.isCjk('a'))
        assertTrue(!OcrTextNormalizer.isCjk('1'))
        assertTrue(!OcrTextNormalizer.isCjk('《'))
    }
}
