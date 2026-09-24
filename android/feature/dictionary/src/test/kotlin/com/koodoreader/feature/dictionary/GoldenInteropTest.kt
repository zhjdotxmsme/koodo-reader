package com.koodoreader.feature.dictionary

import com.koodoreader.feature.dictionary.mdx.ByteArrayMdictSource
import com.koodoreader.feature.dictionary.mdx.MdictCore
import com.koodoreader.feature.dictionary.mdx.MdxParser
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CROSS-IMPLEMENTATION PARITY — the strongest evidence in this suite.
 *
 * The fixtures below were produced by an **independent JavaScript writer**
 * (`.scratch/mkfixture.mjs`, kept in the verification notes) that encodes the MDX v2.0
 * container by hand from the format description, and were then read back with
 * **js-mdict 6.0.8 itself** (`node_modules/js-mdict/dist/esm/index.js`). Every
 * expected value in this file is literally what js-mdict returned:
 *
 * ```js
 * const mdx = new MDX('golden.mdx');
 * mdx.lookup('banana').definition  // -> '<div class="entry"><b>banana</b> n. 香蕉</div>'
 * mdx.prefix('a').map(k => k.keyText) // -> ['apple']
 * mdx.keyInfoList[0].keyBlockPackSize // -> 58
 * const mdd = new MDD('golden.mdd');
 * mdd.locate('\\style.css').definition // -> 'Ym9keXttYXJnaW46MH0='
 * ```
 *
 * So this test proves three things at once: the JS writer, the JS reader and this
 * Kotlin port all agree on the same bytes; the adler32 fields written by JS match the
 * ones this port computes (`verifyChecksums = true`); and the UTF-16 `.mdd` key layout
 * is identical. A shared bug in one implementation cannot hide here, because the
 * fixture never went through [com.koodoreader.feature.dictionary.mdx.MdictFixtureWriter].
 */
class GoldenInteropTest {

    private fun mdx(): ByteArray = Base64.getDecoder().decode(GOLDEN_MDX_B64)

    private fun mdd(): ByteArray = Base64.getDecoder().decode(GOLDEN_MDD_B64)

    @Test
    fun `golden mdx header matches what js-mdict parsed`() {
        MdxParser.open(mdx(), "golden.mdx").use { parser ->
            assertEquals("2.0", parser.header["GeneratedByEngineVersion"])
            assertEquals("2.0", parser.header["RequiredEngineVersion"])
            assertEquals("No", parser.header["Encrypted"])
            assertEquals("UTF-8", parser.header["Encoding"])
            assertEquals("Html", parser.header["Format"])
            assertEquals("No", parser.header["KeyCaseSensitive"])
            assertEquals("Yes", parser.header["StripKey"])
            assertEquals("P6 Golden MDX", parser.header.title)
            assertEquals("p6 dictionary fixture", parser.header.description)
        }
    }

    @Test
    fun `golden mdx keyword list matches js-mdict`() {
        MdxParser.open(mdx(), "golden.mdx").use { parser ->
            assertEquals(5, parser.keywordCount)
            assertEquals(listOf("apple", "banana", "cherry", "date", "elderberry"), parser.keywords)
            // js-mdict: mdx.keyInfoList -> one block, firstKey apple, lastKey elderberry.
            assertEquals(1, parser.keyBlockCount)
            assertEquals(2, parser.recordBlockCount)
        }
    }

    @Test
    fun `every definition js-mdict read is read here byte for byte`() {
        val expected = mapOf(
            "apple" to "<div class=\"entry\"><b>apple</b> n. 苹果</div>",
            "banana" to "<div class=\"entry\"><b>banana</b> n. 香蕉</div>",
            "cherry" to "<div class=\"entry\"><b>cherry</b> n. 樱桃</div>",
            "date" to "<div class=\"entry\"><b>date</b> n. 枣; 日期</div>",
            "elderberry" to "<div class=\"entry\"><b>elderberry</b> n. 接骨木果</div>",
        )
        MdxParser.open(mdx(), "golden.mdx").use { parser ->
            for ((word, definition) in expected) {
                assertEquals("lookup($word)", definition, parser.lookupDefinition(word))
            }
            // js-mdict: mdx.lookup('zzz-not-there').definition === null
            assertNull(parser.lookupDefinition("zzz-not-there"))
            // js-mdict: mdx.prefix('a') -> ['apple']
            assertEquals(listOf("apple"), parser.prefix("a").map { it.keyText })
        }
    }

    @Test
    fun `the adler32 fields written by the js writer are accepted`() {
        MdictCore(
            ByteArrayMdictSource(mdx(), "golden.mdx"),
            MdictCore.Options(verifyChecksums = true),
        ).use { core ->
            assertEquals(5, core.keywordList.size)
            // js-mdict: recordInfoList[1] = {packSize:108, packAccumulateOffset:81, unpackSize:157, unpackAccumulatorOffset:95}
            assertEquals(2, core.recordInfoList.size)
            assertEquals(81L, core.recordInfoList[0].packSize)
            assertEquals(0L, core.recordInfoList[0].unpackAccumulatorOffset)
            assertEquals(108L, core.recordInfoList[1].packSize)
            assertEquals(81L, core.recordInfoList[1].packAccumulateOffset)
            assertEquals(95L, core.recordInfoList[1].unpackAccumulatorOffset)
            // js-mdict: keyInfoList[0] = {firstKey:'apple', lastKey:'elderberry', keyBlockPackSize:58, keyBlockUnpackSize:76}
            assertEquals("apple", core.keyInfoList[0].firstKey)
            assertEquals("elderberry", core.keyInfoList[0].lastKey)
            assertEquals(58L, core.keyInfoList[0].keyBlockPackSize)
            assertEquals(76L, core.keyInfoList[0].keyBlockUnpackSize)
            assertEquals(5L, core.keyInfoList[0].keyBlockEntriesNum)
        }
    }

    @Test
    fun `golden mdd resources match js-mdict base64 output`() {
        MddParser.open(mdd(), "golden.mdd").use { mdd ->
            // js-mdict: mdd.keywordList -> ['\\img\\logo.png', '\\style.css'] (sorted)
            assertEquals(listOf("\\img\\logo.png", "\\style.css"), mdd.keywords)

            val css = mdd.locate("\\style.css")
            assertNotNull(css)
            assertEquals("Ym9keXttYXJnaW46MH0=", css!!.base64())
            assertEquals("body{margin:0}", String(css.bytes))
            assertEquals("text/css", css.mimeType)

            val png = mdd.locate("\\img\\logo.png")
            assertNotNull(png)
            assertEquals("iVBORw0KGgoBAgME", png!!.base64())
            assertEquals("image/png", png.mimeType)
            assertTrue(png.bytes.size == 12)
        }
    }

    private companion object {
        /** Golden MDX v2.0 produced by `.scratch/mkfixture.mjs` (835 bytes). */
        const val GOLDEN_MDX_B64 = "AAABqjwARABpAGMAdABpAG8AbgBhAHIAeQAgAEcAZQBuAGUAcgBhAHQAZQBkAEIAeQBFAG4AZwBpAG4AZQBWAGUAcgBzAGkAbwBuAD0AIgAyAC4AMAAiACAAUgBlAHEAdQBpAHIAZQBkAEUAbgBnAGkAbgBlAFYAZQByAHMAaQBvAG4APQAiADIALgAwACIAIABFAG4AYwByAHkAcAB0AGUAZAA9ACIATgBvACIAIABFAG4AYwBvAGQAaQBuAGcAPQAiAFUAVABGAC0AOAAiACAARgBvAHIAbQBhAHQAPQAiAEgAdABtAGwAIgAgAEsAZQB5AEMAYQBzAGUAUwBlAG4AcwBpAHQAaQB2AGUAPQAiAE4AbwAiACAAUwB0AHIAaQBwAEsAZQB5AD0AIgBZAGUAcwAiACAAVABpAHQAbABlAD0AIgBQADYAIABHAG8AbABkAGUAbgAgAE0ARABYACIAIABEAGUAcwBjAHIAaQBwAHQAaQBvAG4APQAiAHAANgAgAGQAaQBjAHQAaQBvAG4AYQByAHkAIABmAGkAeAB0AHUAcgBlACIALwA+AFWdSKkAAAAAAAAAAQAAAAAAAAAFAAAAAAAAACsAAAAAAAAALgAAAAAAAAA6AAAAAAIAAACeWAbbeJxjYAADVgaWxIKCnFQGztSclNSipNSiokqIDIMVlPYBAJ5YBtsCAAAAz0gOrnicY2CAgMSCgpxUKJtBPykxDwhh3PjkjNSiokoYtz8lsQSu9FBqTkpqURJYHgDPSA6uAAAAAAAAAAIAAAAAAAAABQAAAAAAAAAgAAAAAAAAAL0AAAAAAAAAUQAAAAAAAABfAAAAAAAAAGwAAAAAAAAAnQIAAACIOiOYeJyzScksU0jOSSwutlVKzSspqlSys0mySywoyEm10U+yU8jTU3jRvfPZvDk2+kCVdjZYlScl5gEhTP3LZTNfTO2EqAcAiDojmAIAAABEmT4HeJyzScksU0jOSSwutlVKzSspqlSys0myS85ILSqqtNFPslPI01N4tmLjs4XNNvpApXY2WNWnJJakwlXPW2yt8Gz60mdz5uPTkpqTklqUhGJN39KXq1Y8m7Pi2bw5EJ0ARJk+Bw=="

        /** Golden MDD v2.0 with UTF-16 keys (631 bytes). */
        const val GOLDEN_MDD_B64 = "AAABZDwARABpAGMAdABpAG8AbgBhAHIAeQAgAEcAZQBuAGUAcgBhAHQAZQBkAEIAeQBFAG4AZwBpAG4AZQBWAGUAcgBzAGkAbwBuAD0AIgAyAC4AMAAiACAAUgBlAHEAdQBpAHIAZQBkAEUAbgBnAGkAbgBlAFYAZQByAHMAaQBvAG4APQAiADIALgAwACIAIABFAG4AYwByAHkAcAB0AGUAZAA9ACIATgBvACIAIABFAG4AYwBvAGQAaQBuAGcAPQAiAFUAVABGAC0AMQA2ACIAIABGAG8AcgBtAGEAdAA9ACIASAB0AG0AbAAiACAASwBlAHkAQwBhAHMAZQBTAGUAbgBzAGkAdABpAHYAZQA9ACIATgBvACIAIABTAHQAcgBpAHAASwBlAHkAPQAiAFkAZQBzACIAIABUAGkAdABsAGUAPQAiAFAANgAgAEcAbwBsAGQAZQBuACAATQBEAEQAIgAvAD4ACVk7fAAAAAAAAAABAAAAAAAAAAIAAAAAAAAASgAAAAAAAABFAAAAAAAAAEAAAAAAAgAAAH4sCbd4nGNgAAMmBs4YhmKGEoZKhhyGVAY9hmQgr5iBgSeGIZMhlyGdIQYong+k84FyBQx5QBYUOEBpJwB+LAm3AgAAABjvCSx4nGNggIAYhmKGEoZKhhyGVAY9hmQgr5gBDvhiGDIZchnSgapyGPKBdD5QTQFDHpDFwAAAGO8JLAAAAAAAAAABAAAAAAAAAAIAAAAAAAAAEAAAAAAAAAAqAAAAAAAAACoAAAAAAAAAGgIAAAB/HgdCeJxLyk+prM5NLErPzLMyqO0M8HPn5ZLiYmRiZgEAfx4HQg=="
    }
}
