package com.koodoreader.feature.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun `parses nested documents`() {
        val value = MiniJson.parse(
            """{"a":{"b":[1,2.5,true,false,null,"s"]},"c":"d"}""",
        )

        assertEquals("d", value.stringAt("c"))
        assertEquals("s", value.stringAt("a", "b", 5))
        assertEquals(1, value.intAt("a", "b", 0))
        assertTrue(value.at("a", "b", 2) is JsonValue.JsonBool)
        assertNull(value.at("a", "b", 9))
        assertNull(value.stringAt("missing"))
    }

    @Test
    fun `decodes escapes and unicode`() {
        val value = MiniJson.parse("""{"s":"a\nb\t\"q\"\\ \u4f60\u597d"}""")

        assertEquals("a\nb\t\"q\"\\ 你好", value.stringAt("s"))
    }

    @Test
    fun `malformed documents return null instead of throwing`() {
        assertNull(MiniJson.parse(""))
        assertNull(MiniJson.parse("{"));
        assertNull(MiniJson.parse("""{"a":}"""))
        assertNull(MiniJson.parse("[1,2"))
        assertNull(MiniJson.parse("not json"))
        assertNull(MiniJson.parse("""{"a":1} {"b":2}"""))
        assertNull(MiniJson.parseArray("""{"a":1}"""))
        assertNull(MiniJson.parseObject("[1]"))
    }

    @Test
    fun `writes bodies with proper escaping`() {
        assertEquals("""{"q":"a\"b","n":2,"ok":true}""", MiniJson.body("q" to "a\"b", "n" to 2, "ok" to true))
        assertEquals("""{"text":["a","b"]}""", MiniJson.body("text" to listOf("a", "b")))
        assertEquals("""{"text":["x"],"target_lang":"ZH"}""", MiniJson.body("text" to listOf("x"), "target_lang" to "ZH"))
        assertEquals("null", MiniJson.write(null))
        assertEquals(""""line\nbreak"""", MiniJson.write("line\nbreak"))
    }

    @Test
    fun `round trips a written body`() {
        val body = MiniJson.body("model" to "m", "messages" to listOf(mapOf("role" to "user", "content" to "hi")))

        val parsed = MiniJson.parseObject(body)

        assertEquals("m", parsed.stringAt("model"))
        assertEquals("user", parsed.stringAt("messages", 0, "role"))
        assertEquals("hi", parsed.stringAt("messages", 0, "content"))
    }

    @Test
    fun `html entities are decoded like the desktop textarea trick`() {
        assertEquals("你好 & 欢迎 '朋友'", HtmlEntities.unescape("你好 &amp; 欢迎 &#39;朋友&#39;"))
        assertEquals("\"quoted\" <tag>", HtmlEntities.unescape("&quot;quoted&quot; &lt;tag&gt;"))
        assertEquals("你", HtmlEntities.unescape("&#x4F60;"))
        assertEquals("plain", HtmlEntities.unescape("plain"))
        assertEquals("&unknown; stays", HtmlEntities.unescape("&unknown; stays"))
        assertEquals("100% & more", HtmlEntities.unescape("100% & more"))
    }
}
