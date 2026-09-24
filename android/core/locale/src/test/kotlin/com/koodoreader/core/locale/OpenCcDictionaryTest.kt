package com.koodoreader.core.locale

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * OpenCC file format, MaxMatch segmentation, reversal and **seed integrity**.
 * The integrity tests are the guard against hand-editing the curated seed: a
 * duplicated key, an identity mapping, or a value that is itself a key (which
 * would chain-convert and lose data) fails the build.
 */
class OpenCcDictionaryTest {

    @Test
    @DisplayName("parses the upstream OpenCC format: tabs, comments, multi-value entries")
    fun `parses opencc format`() {
        val dict = OpenCcDictionary.parse(
            "Test",
            """
            # leading comment
            网	網

            软	軟 软
            干	乾 幹
            """.trimIndent(),
        )
        assertEquals(3, dict.size)
        assertEquals(listOf("軟", "软"), dict.values("软"))
        assertEquals("軟", dict.firstValue("软")) // OpenCC default: first value
        assertEquals(listOf("乾", "幹"), dict.values("干"))
        assertEquals(null, dict.firstValue("不存在"))
    }

    @Test
    @DisplayName("empty and comment-only dictionaries are empty")
    fun `empty dictionaries`() {
        assertEquals(0, OpenCcDictionary.parse("Empty", "").size)
        assertEquals(0, OpenCcDictionary.parse("Empty", "# nothing\n\n  \n").size)
    }

    @Test
    @DisplayName("malformed lines fail loudly with the line number")
    fun `malformed lines fail`() {
        val spaced = assertThrows(IllegalArgumentException::class.java) {
            OpenCcDictionary.parse("Bad", "网 網\n")
        }
        assertTrue(spaced.message!!.contains("line 1"))
        assertThrows(IllegalArgumentException::class.java) {
            OpenCcDictionary.parse("Bad", "网\t\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenCcDictionary.parse("Bad", "\t網\n")
        }
    }

    @Test
    @DisplayName("duplicate keys keep the first value and are counted")
    fun `duplicate keys keep first value`() {
        val dict = OpenCcDictionary.parse("Dup", "网\t網\n网\t网\n")
        assertEquals(1, dict.size)
        assertEquals(1, dict.duplicateKeys)
        assertEquals("網", dict.firstValue("网"))
    }

    @Test
    @DisplayName("MaxMatch: the longest key wins, unmatched text is copied through")
    fun `max match segmentation`() {
        val dict = OpenCcDictionary.parse("MaxMatch", "干\t幹\n干净\t乾淨\n净\t淨\n")
        assertEquals(3, dict.maxKeyLength)
        assertEquals("乾淨", dict.convert("干净"))
        assertEquals("幹杯", dict.convert("干杯"))
        assertEquals("乾淨", dict.convert("干净")) // no double conversion of 淨
        assertEquals("a b", dict.convert("a b"))
        assertEquals("", dict.convert(""))
    }

    @Test
    @DisplayName("segments mark converted spans (what protects a user dictionary)")
    fun `segments mark converted spans`() {
        val dict = OpenCcDictionary.parse("Seg", "网\t網\n")
        val segments = dict.segments("网上")
        assertEquals(2, segments.size)
        assertEquals("網" to true, segments[0])
        assertEquals("上" to false, segments[1])
        assertEquals("网上", dict.convert("网上"))
        assertEquals(listOf("" to false), dict.segments("").filter { it.first.isEmpty() })
    }

    @Test
    @DisplayName("reverse builds the T→S table and drops identity mappings")
    fun `reverse builds t2s table`() {
        val st = OpenCcDictionary.parse("ST", "里\t裏\n网\t網\n干\t幹\n")
        val ts = OpenCcDictionary.reverse("TS", st)
        assertEquals(3, ts.size)
        assertEquals("里", ts.firstValue("裏"))
        assertEquals("网", ts.firstValue("網"))
        // identity entries disappear instead of producing a no-op rule
        val identity = OpenCcDictionary.reverse("TS", OpenCcDictionary.parse("I", "平\t平\n"))
        assertEquals(0, identity.size)
    }

    @Test
    @DisplayName("chain: locked stages only protect the pieces they rewrote")
    fun `chain protects only converted pieces`() {
        val user = OpenCcDictionary.parse("User", "里面\t里面\n", locked = true)
        val chars = OpenCcDictionary.parse("Chars", "里\t裏\n写\t寫\n")
        assertEquals(
            "里面寫",
            OpenCcDictionary.applyChain("里面写", listOf(user, chars)),
        )
        // unlocked stages behave like plain successive passes
        assertEquals(
            "裏面寫",
            OpenCcDictionary.applyChain("里面写", listOf(OpenCcDictionary.parse("P", "面\t面\n"), chars)),
        )
        assertEquals("x", OpenCcDictionary.applyChain("x", emptyList()))
        assertEquals("x", OpenCcDictionary.applyChain("x", listOf(OpenCcDictionary.parse("E", ""))))
    }

    @Test
    @DisplayName("merge/without helpers are deterministic")
    fun `merge and without helpers`() {
        val a = OpenCcDictionary.parse("A", "里\t裏\n")
        val b = OpenCcDictionary.parse("B", "里\t里\n里弄\t里弄\n")
        val merged = OpenCcDictionary.merge("M", a, b)
        assertEquals(2, merged.size)
        assertEquals("裏", merged.firstValue("里")) // first dictionary wins
        assertEquals(1, merged.duplicateKeys)
        assertEquals(0, OpenCcDictionary.without(a, setOf("里")).size)
        assertEquals(1, OpenCcDictionary.without(a, emptySet()).size)
    }

    @Test
    @DisplayName("seed integrity: no duplicates, no identity rules, no key/value chains")
    fun `seed integrity`() {
        val stChars = OpenCcDictionary.parse("STCharacters", OpenCcSeed.ST_CHARACTERS)
        val stPhrases = OpenCcDictionary.parse("STPhrases", OpenCcSeed.ST_PHRASES)
        val twPhrases = OpenCcDictionary.parse("TWPhrases", OpenCcSeed.TW_PHRASES)
        val tsExtra = OpenCcDictionary.parse("TSCharactersExtra", OpenCcSeed.TS_EXTRA_CHARACTERS)

        for (dict in listOf(stChars, stPhrases, twPhrases, tsExtra)) {
            assertEquals(0, dict.duplicateKeys, "${dict.name}: duplicated key in the seed")
            for (key in dict.keys()) {
                assertTrue(key.isNotBlank(), "${dict.name}: blank key")
                assertTrue(dict.firstValue(key)!!.isNotBlank(), "${dict.name}: blank value for $key")
                assertTrue(dict.firstValue(key) != key, "${dict.name}: identity rule for $key")
            }
        }

        // The curated subset must stay useful, and S→T characters must be 1:1
        // (a collision would silently lose information in reverse()).
        assertTrue(stChars.size >= 500, "STCharacters shrank: ${stChars.size}")
        assertTrue(stPhrases.size >= 60, "STPhrases shrank: ${stPhrases.size}")
        assertTrue(twPhrases.size >= 60, "TWPhrases shrank: ${twPhrases.size}")
        assertTrue(tsExtra.size >= 5, "TSCharactersExtra shrank: ${tsExtra.size}")

        val values = HashMap<String, String>()
        for (key in stChars.keys()) {
            val value = stChars.firstValue(key)!!
            val previous = values.put(value, key)
            assertEquals(null, previous, "STCharacters maps both $previous and $key to $value")
            assertEquals(null, stChars.firstValue(value), "$value is both a value and a key (chaining risk)")
        }

        // built tables: S→T and T→S are exact inverses for characters
        val dictionaries = ZhConvertDictionaries.fromSeedTexts()
        assertEquals(stChars.size + tsExtra.size, dictionaries.tsCharacters.size)
        assertEquals(stPhrases.size, dictionaries.tsPhrases.size)
        // Exactly three Taiwan terms are shared by two mainland terms
        // (接口/界面 → 介面, 缺省/默認 → 預設, 屏幕/顯示器 → 螢幕), so the
        // reversed table is smaller by those three (first occurrence wins).
        assertEquals(
            twPhrases.size - 3,
            dictionaries.twPhrasesReverse.size,
            "TWPhrases reverse collisions changed — update the seed or this count",
        )
        for (key in stChars.keys()) {
            val traditional = stChars.firstValue(key)!!
            assertEquals(key, dictionaries.tsCharacters.firstValue(traditional), "T→S of $traditional")
        }
    }
}
