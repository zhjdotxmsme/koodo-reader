package com.koodoreader.feature.dictionary

import com.koodoreader.feature.dictionary.mdx.ByteArrayMdictSource
import com.koodoreader.feature.dictionary.mdx.MdictCore
import com.koodoreader.feature.dictionary.mdx.MdictEncoding
import com.koodoreader.feature.dictionary.mdx.MdictFixtureWriter
import com.koodoreader.feature.dictionary.mdx.MdictFixtureWriter.Entry
import com.koodoreader.feature.dictionary.mdx.MdictFormatException
import com.koodoreader.feature.dictionary.mdx.MdxParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MDX parsing happy paths + the layout variants that actually differ between the
 * dictionaries in the wild.
 *
 * Every fixture is produced by [MdictFixtureWriter] (an independent writer, see its
 * class comment), and the same bytes are additionally proven interoperable with
 * js-mdict 6.0.8 in `GoldenInteropTest`.
 */
class MdxParserTest {

    private val entries = listOf(
        Entry("apple", "<div class=\"entry\"><b>apple</b> n. 苹果</div>"),
        Entry("banana", "<div class=\"entry\"><b>banana</b> n. 香蕉</div>"),
        Entry("cherry", "<div class=\"entry\"><b>cherry</b> n. 樱桃</div>"),
        Entry("date", "<div class=\"entry\"><b>date</b> n. 枣</div>"),
        Entry("elderberry", "<div class=\"entry\"><b>elderberry</b> n. 接骨木果</div>"),
    )

    @Test
    fun `v2 dictionary exposes header keywords and definitions`() {
        val bytes = MdictFixtureWriter.writeMdx(entries, title = "P6 Test")
        MdxParser.open(bytes, "p6-test.mdx").use { parser ->
            assertEquals("P6 Test", parser.header.title)
            assertEquals("2.0", parser.header["GeneratedByEngineVersion"])
            assertEquals("UTF-8", parser.header["Encoding"])
            assertEquals(2.0, parser.meta.version, 0.0)
            assertEquals(8, parser.meta.numWidth)
            assertEquals(5, parser.keywordCount)
            assertEquals(entries.map { it.key }, parser.keywords)

            for (entry in entries) {
                val result = parser.lookup(entry.key)
                assertTrue("${entry.key} should be found", result.found)
                assertEquals(String(entry.definition), result.definition)
            }
        }
    }

    @Test
    fun `a missing word returns null instead of throwing`() {
        MdxParser.open(MdictFixtureWriter.writeMdx(entries)).use { parser ->
            val result = parser.lookup("zzzz-not-a-word")
            assertNull(result.definition)
            assertEquals("zzzz-not-a-word", result.keyText)
            assertNull(parser.lookupDefinition("zzzz-not-a-word"))
        }
    }

    /** Two key blocks + two record blocks: the offset accumulators must line up. */
    @Test
    fun `multiple key blocks keep their global record offsets`() {
        val bytes = MdictFixtureWriter.writeMdx(entries, keyBlockCount = 2)
        MdxParser.open(bytes, "multiblock.mdx").use { parser ->
            assertEquals(5, parser.keywordCount)
            assertEquals(2, parser.keyBlockCount)
            assertEquals(2, parser.recordBlockCount)
            for (entry in entries) {
                assertEquals(String(entry.definition), parser.lookupDefinition(entry.key))
            }
            // The last entry of block 1 takes its end offset from block 2's first entry.
            val cherry = parser.lookup("cherry")
            assertEquals(String(entries[2].definition), cherry.definition)
        }
    }

    /** `compression type 0` on the key *and* record blocks (`mdict-base.js:632`, `mdict.js:134`). */
    @Test
    fun `stored (uncompressed) blocks are read verbatim`() {
        val bytes = MdictFixtureWriter.writeMdx(
            entries,
            compressKeyInfo = false,
            compressKeyBlocks = false,
            compressRecordBlocks = false,
        )
        MdxParser.open(bytes, "stored.mdx").use { parser ->
            assertEquals(5, parser.keywordCount)
            assertEquals(String(entries[1].definition), parser.lookupDefinition("banana"))
        }
    }

    /** `Encrypted="Yes"` — record blocks are RIPEMD-128/XOR encrypted (mdict.js:141-146). */
    @Test
    fun `encrypted record blocks are decrypted`() {
        val bytes = MdictFixtureWriter.writeMdx(entries, encryptRecordBlocks = true)
        MdxParser.open(bytes, "encrypted.mdx").use { parser ->
            assertEquals("Yes", parser.header["Encrypted"])
            assertEquals(1, parser.meta.encrypt)
            for (entry in entries) {
                assertEquals(String(entry.definition), parser.lookupDefinition(entry.key))
            }
        }
    }

    /**
     * Deviation from the reference: js-mdict throws for `Encrypted="Yes"` before it can
     * ever reach its decryption code (`mdict-base.js:444-457` vs `mdict.js:141`).
     */
    @Test
    fun `encrypted dictionaries are readable where js-mdict refuses them`() {
        val bytes = MdictFixtureWriter.writeMdx(entries, encryptRecordBlocks = true)
        MdxParser.open(bytes, "encrypted.mdx").use { parser ->
            assertNotNull(parser.lookupDefinition("date"))
        }
    }

    /** `Options.verifyChecksums` walks the adler32 fields the reference leaves as TODO. */
    @Test
    fun `adler32 verification accepts a well formed file`() {
        val bytes = MdictFixtureWriter.writeMdx(entries)
        MdictCore(
            ByteArrayMdictSource(bytes, "verified.mdx"),
            MdictCore.Options(verifyChecksums = true),
        ).use { core ->
            assertEquals(5, core.keywordList.size)
        }
    }

    @Test
    fun `adler32 verification rejects a corrupted record block`() {
        val bytes = MdictFixtureWriter.writeMdx(entries, compressRecordBlocks = false)
        // Flip a bit inside the last record block (its payload starts 8 bytes in).
        val corrupted = bytes.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0xFF).toByte()
        val failure = runCatching {
            MdictCore(
                ByteArrayMdictSource(corrupted, "corrupt.mdx"),
                MdictCore.Options(verifyChecksums = true),
            ).use { core -> core.lookupDef("date") }
        }.exceptionOrNull()
        assertNotNull("a corrupted block must be reported, not silently decoded", failure)
        assertTrue(failure is MdictFormatException)
    }

    @Test
    fun `prefix suggest and fuzzy search use the keyword list`() {
        val extra = entries + Entry("apricot", "<i>apricot</i>") + Entry("avatar", "<i>avatar</i>")
        MdxParser.open(MdictFixtureWriter.writeMdx(extra)).use { parser ->
            // `mdx.js:50-55` — prefix filter.
            assertEquals(listOf("apple", "apricot"), parser.prefix("ap").map { it.keyText })
            // `mdx.js:78-93` — edit distance suggestions.
            val suggestions = parser.suggest("aple", 2).map { it.keyText }
            assertTrue("apple should be suggested for aple, got $suggestions", suggestions.contains("apple"))
            // Distance outside 0..5 is refused by the reference too.
            assertTrue(parser.suggest("aple", 9).isEmpty())
            // `mdx.js:115-129` — capped, distance-sorted fuzzy search.
            val fuzzy = parser.fuzzySearch("aple", 3, 2).map { it.keyText }
            assertTrue(fuzzy.contains("apple"))
            assertTrue(fuzzy.size <= 3)
        }
    }

    @Test
    fun `opens from a file source and survives reuse`() {
        val file = File.createTempFile("p6-dict", ".mdx")
        try {
            file.writeBytes(MdictFixtureWriter.writeMdx(entries))
            MdxParser.open(file).use { parser ->
                assertEquals(5, parser.keywordCount)
                // Repeat lookups must not depend on the record block staying cached.
                repeat(3) {
                    assertEquals(String(entries[3].definition), parser.lookupDefinition("date"))
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a non mdict file reports a format error`() {
        val failure = runCatching { MdxParser.open("not a dictionary at all".toByteArray(), "junk.mdx") }
            .exceptionOrNull()
        assertNotNull(failure)
        assertTrue(
            "unexpected exception: $failure",
            failure is MdictFormatException,
        )
    }

    /** Engine 1.2: 4-byte numbers, raw key-info table, no adler32 after the key header. */
    @Test
    fun `engine 1_2 dictionaries are read with 4 byte numbers`() {
        val bytes = MdictFixtureWriter.writeMdx(entries, version = "1.2")
        MdxParser.open(bytes, "v12.mdx").use { parser ->
            assertEquals(1.2, parser.meta.version, 0.0)
            assertEquals(4, parser.meta.numWidth)
            assertEquals(5, parser.keywordCount)
            for (entry in entries) {
                assertEquals(String(entry.definition), parser.lookupDefinition(entry.key))
            }
        }
    }

    /** A UTF-16 key dictionary (Chinese dictionaries in the wild) uses 2-byte terminators. */
    @Test
    fun `utf16 encoded keys are decoded`() {
        // In a UTF-16 dictionary the *definitions* are UTF-16LE as well
        // (`mdict-base.js:404-416` picks one decoder for the whole file).
        val utf16Entries = listOf(
            Entry("苹果", "apple".toByteArray(Charsets.UTF_16LE)),
            Entry("香蕉", "banana".toByteArray(Charsets.UTF_16LE)),
            Entry("樱桃", "cherry".toByteArray(Charsets.UTF_16LE)),
        )
        val bytes = MdictFixtureWriter.writeMdx(utf16Entries, encoding = "UTF-16")
        MdxParser.open(bytes, "utf16.mdx").use { parser ->
            assertEquals(MdictEncoding.UTF16LE, parser.meta.encoding)
            assertEquals(2, parser.meta.keyTerminatorWidth)
            // Keyword ORDER is collation-defined (js-mdict sorts with `localeCompare`,
            // this port with java.text.Collator — for CJK that is code-point order), so
            // assert the set, not the sequence; lookups are order-independent because
            // sorting and the binary search share one comparator.
            assertEquals(setOf("苹果", "香蕉", "樱桃"), parser.keywords.toSet())
            // All three keys decode with the 2-byte terminator and are findable.
            for ((word, definition) in mapOf("苹果" to "apple", "香蕉" to "banana", "樱桃" to "cherry")) {
                assertEquals(definition, parser.lookupDefinition(word))
            }
        }
    }

    private fun MdxParser.coreKeyInfoCount(): Int = keyBlockCount

    private fun MdictCore.lookupDef(word: String): String? {
        val item = lookupKeyBlockByWord(word) ?: return null
        val raw = lookupRecordByKeyBlock(item) ?: return null
        return meta.encoding.decode(raw, 0, raw.size)
    }
}
