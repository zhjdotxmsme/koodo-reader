package com.koodoreader.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlatJsonTest {

    @Test
    fun `parses flat locale objects`() {
        val m = FlatJson.parse("""{"Books":"Books","Name":"Name"}""")
        assertEquals("Books", m["Books"])
        assertEquals(2, m.size)
    }

    @Test
    fun `handles escapes`() {
        val m = FlatJson.parse("""{"a":"line\nbreak","b":"tab\tstop","c":"quote\"inside","d":"back\\slash","e":"slash/slash","f":"脸"}""")
        assertEquals("line\nbreak", m["a"])
        assertEquals("tab\tstop", m["b"])
        assertEquals("quote\"inside", m["c"])
        assertEquals("back\\slash", m["d"])
        assertEquals("slash/slash", m["e"])
        assertEquals("脸", m["f"])
    }

    @Test
    fun `handles unicode escapes and surrounding whitespace`() {
        val m = FlatJson.parse("""  { "k" : "\u4F60\u597D" }  """)
        assertEquals("你好", m["k"])
    }

    @Test
    fun `skips non-string values instead of failing`() {
        val m = FlatJson.parse("""{"keep":"yes","n":12,"b":true,"nil":null,"arr":[1,2],"obj":{"x":1}}""")
        assertEquals("yes", m["keep"])
        assertEquals(1, m.size)
    }

    @Test
    fun `rejects malformed input`() {
        assertThrows(FlatJsonException::class.java) { FlatJson.parse("[1,2]") }
        assertThrows(FlatJsonException::class.java) { FlatJson.parse("""{"a":"unterminated}""") }
        assertThrows(FlatJsonException::class.java) { FlatJson.parse("""{a:1}""") }
    }
}

class LocalizationTest {

    private val catalogs = mapOf(
        "en" to mapOf(
            "Books" to "Books",
            "Trash" to "Trash",
            "OnlyEnglish" to "English only",
        ),
        "zh-CN" to mapOf(
            "Books" to "全部图书",
            "Trash" to "回收站",
        ),
    )

    @Test
    fun `selected language wins`() {
        val l = Localization(catalogs, "zh-CN")
        assertEquals("回收站", l.t("Trash"))
        assertEquals("全部图书", l.t("Books"))
    }

    @Test
    fun `falls back to english then to the key itself`() {
        val l = Localization(catalogs, "zh-CN")
        assertEquals("English only", l.t("OnlyEnglish")) // missing in zh-CN
        assertEquals("Missing key", l.t("Missing key")) // missing everywhere
    }

    @Test
    fun `language switch is live and normalized`() {
        val l = Localization(catalogs, "en")
        assertEquals("Books", l.t("Books"))
        l.language = "zh_cn"
        assertEquals("全部图书", l.t("Books"))
        assertEquals("zh-CN", l.language)
        assertTrue(l.availableLanguages().containsAll(listOf("en", "zh-CN")))
    }
}
