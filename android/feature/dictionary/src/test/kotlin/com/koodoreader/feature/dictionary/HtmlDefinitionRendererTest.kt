package com.koodoreader.feature.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Definition → HTML pipeline: alias resolution, sanitising, `.mdd` resource rewriting
 * and the header stylesheet.
 *
 * The desktop renders the definition as raw HTML and then re-loads
 * `audio.audio-player` nodes (popupDict/component.tsx:224-242); everything that a
 * browser did implicitly has to be explicit on Android, which is what this class does.
 */
class HtmlDefinitionRendererTest {

    @Test
    fun `resolves at-link aliases through the lookup callback`() {
        val definitions = mapOf(
            "colour" to "<b>colour</b> n. 颜色",
            "color" to "@@@LINK=colour",
            "colour2" to "@@@LINK= color",
        )
        val resolve: (String) -> String? = { definitions[it] }

        assertEquals(
            "<b>colour</b> n. 颜色",
            HtmlDefinitionRenderer.render("@@@LINK=colour", lookup = resolve, options = OptionsPlain),
        )
        assertEquals(
            "<b>colour</b> n. 颜色",
            HtmlDefinitionRenderer.render("@@@LINK= color ", lookup = resolve, options = OptionsPlain),
        )
        // An unresolved alias is left alone rather than rendered as empty.
        assertTrue(
            HtmlDefinitionRenderer.render("@@@LINK=missing", lookup = resolve, options = OptionsPlain)
                .contains("@@@LINK=missing")
        )
    }

    @Test
    fun `link resolution is depth limited`() {
        // a -> b -> a ... must terminate.
        val resolve: (String) -> String? = { target -> if (target == "a") "@@@LINK=b" else "@@@LINK=a" }
        val out = HtmlDefinitionRenderer.resolveLinks("@@@LINK=a", resolve)
        assertTrue(out.startsWith("@@@LINK="))
    }

    @Test
    fun `strips active content from an untrusted definition`() {
        val hostile = """
            <div onclick="steal()">ok</div>
            <script>fetch('http://evil')</script>
            <style>.x{color:red}</style>
            <iframe src="http://evil"></iframe>
            <a href="javascript:alert(1)">click</a>
            <img src="x.png" onerror="alert(2)">
        """.trimIndent()
        val clean = HtmlDefinitionRenderer.sanitize(hostile)
        assertFalse(clean.contains("<script"))
        assertFalse(clean.contains("<iframe"))
        assertFalse(clean.contains("onclick"))
        assertFalse(clean.contains("onerror"))
        assertFalse(clean.contains("javascript:"))
        assertTrue(clean.contains("<div")) // the content itself survives
        assertTrue(clean.contains("<style")) // style is kept by default
        assertFalse(HtmlDefinitionRenderer.sanitize(hostile, HtmlDefinitionRenderer.Options(keepStyle = false)).contains("<style"))
    }

    @Test
    fun `rewrites container resources to the dict-res scheme`() {
        val html = """
            <img src="img/logo.png"><img src="/img/cat.jpg">
            <audio class="audio-player" src="sound/word.mp3"></audio>
            <link rel="stylesheet" href="style.css">
            <img src="https://cdn.example.com/remote.png">
            <img src="data:image/png;base64,AAAA">
        """.trimIndent()
        val out = HtmlDefinitionRenderer.render(html, options = HtmlDefinitionRenderer.Options(wrap = false, dictId = "oxford"))

        assertTrue(out.contains("src=\"dict-res://oxford/img/logo.png\""))
        assertTrue(out.contains("src=\"dict-res://oxford/img/cat.jpg\""))
        assertTrue(out.contains("src=\"dict-res://oxford/sound/word.mp3\""))
        assertTrue(out.contains("href=\"dict-res://oxford/style.css\""))
        // External and inline URLs are untouched.
        assertTrue(out.contains("src=\"https://cdn.example.com/remote.png\""))
        assertTrue(out.contains("src=\"data:image/png;base64,AAAA\""))
    }

    @Test
    fun `dict-res urls round trip back to a container key`() {
        val url = HtmlDefinitionRenderer.resourceUrl("oxford", "\\img\\logo.png")
        assertEquals("dict-res://oxford/img/logo.png", url)
        val (dictId, key) = HtmlDefinitionRenderer.resourceKeyFromUrl(url)!!
        assertEquals("oxford", dictId)
        assertEquals("\\img\\logo.png", key)
        // A resource with a space survives the round trip.
        val spaced = HtmlDefinitionRenderer.resourceUrl("d", "img/my logo.png")
        assertEquals("\\img\\my logo.png", HtmlDefinitionRenderer.resourceKeyFromUrl(spaced)!!.second)
        assertEquals(null, HtmlDefinitionRenderer.resourceKeyFromUrl("https://example.com/x.png"))
    }

    @Test
    fun `applies the header stylesheet markers`() {
        // utils.js:333 — every `` `N` `` marker is replaced by its StyleSheet begin/end
        // pair; like the reference, the text AFTER a marker is wrapped by that style
        // (which is why the trailing " n." picks up the second marker as well).
        val styleSheet = mapOf("1" to listOf("<b>", "</b>"))
        val out = HtmlDefinitionRenderer.render("`1`apple`1` n.", styleSheet = styleSheet, options = OptionsPlain)
        assertEquals("<b>apple</b><b> n.</b>", out)
        // No stylesheet -> the markers survive untouched (dictionaries without one).
        assertEquals(
            "`1`apple`1` n.",
            HtmlDefinitionRenderer.render("`1`apple`1` n.", options = OptionsPlain),
        )
    }

    @Test
    fun `plain text extraction drops markup for clipboard and tts`() {
        val text = HtmlDefinitionRenderer.toPlainText(
            "<div><b>apple</b> n. &lt;苹果&gt;</div><div>second line</div><script>x</script>"
        )
        assertTrue(text.contains("apple"))
        assertTrue(text.contains("<苹果>"))
        assertTrue(text.contains("second line"))
        assertFalse(text.contains("<b>"))
        assertFalse(text.contains("script"))
    }

    @Test
    fun `wraps the fragment for theming`() {
        val wrapped = HtmlDefinitionRenderer.render("<b>x</b>")
        assertTrue(wrapped.startsWith("<div class=\"koodo-dict-entry\">"))
        assertTrue(wrapped.endsWith("</div>"))
    }

    @Test
    fun `truncates pathologically large definitions`() {
        val huge = "a".repeat(5000)
        val out = HtmlDefinitionRenderer.render(
            huge,
            options = HtmlDefinitionRenderer.Options(wrap = false, maxLength = 100),
        )
        assertEquals(100, out.length)
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", HtmlDefinitionRenderer.render(""))
    }

    private companion object {
        /** No wrapper: the assertions compare the fragment itself. */
        val OptionsPlain = HtmlDefinitionRenderer.Options(wrap = false)
    }
}
