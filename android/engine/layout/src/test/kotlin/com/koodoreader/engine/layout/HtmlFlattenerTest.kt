package com.koodoreader.engine.layout

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Functional coverage for the D0 flattener: block elements (≥8 kinds),
 * inline elements (≥5 kinds), CFI identity (elementIndex/elementId),
 * whitespace/entity behaviour, style presets.
 */
class HtmlFlattenerTest {

    private val flattener = DefaultHtmlFlattener()

    private fun blocks(html: String): List<TextBlock> = flattener.blocks(html)

    private fun texts(html: String): List<String> = blocks(html).map { it.text }

    // -------------------------------------------------------------- basics

    @Test
    fun `single paragraph yields one block with sibling index 1`() {
        val bs = blocks("<p>Hello</p>")
        assertEquals(1, bs.size)
        assertEquals("Hello", bs[0].text)
        assertEquals(1, bs[0].elementIndex)
    }

    @Test
    fun `adjacent paragraphs get consecutive sibling indices`() {
        val bs = blocks("<p>a</p><p>b</p>")
        assertEquals(listOf("a", "b"), bs.map { it.text })
        assertEquals(listOf(1, 2), bs.map { it.elementIndex })
    }

    @Test
    fun `nested paragraphs count siblings within their container`() {
        val bs = blocks("<div><p>a</p><p>b</p></div>")
        assertEquals(listOf("a", "b"), bs.map { it.text })
        assertEquals(listOf(1, 2), bs.map { it.elementIndex })
    }

    @Test
    fun `flatten returns a spine item with index href title`() {
        val item = flattener.flatten("<p>x</p>", spineIndex = 3, href = "ch1.xhtml", title = "One")
        assertEquals(3, item.index)
        assertEquals("ch1.xhtml", item.href)
        assertEquals("One", item.title)
        assertEquals(1, item.blocks.size)
    }

    // ----------------------------------------------------- block elements

    @Test
    fun `headings h1 to h6 each yield a block with descending font scale`() {
        val bs = blocks("<h1>1</h1><h2>2</h2><h3>3</h3><h4>4</h4><h5>5</h5><h6>6</h6>")
        assertEquals(6, bs.size)
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), bs.map { it.text })
        val scales = bs.map { it.style.fontSizeScale }
        assertEquals(scales, scales.sortedDescending())
        assertTrue(scales.first() > 1f && scales.last() < 1.01f)
    }

    @Test
    fun `section and article wrappers still yield their text`() {
        assertEquals(listOf("a", "b"), texts("<section>a</section><article>b</article>"))
    }

    @Test
    fun `list items become indented blocks`() {
        val bs = blocks("<ul><li>x</li><li>y</li></ul>")
        assertEquals(listOf("x", "y"), bs.map { it.text })
        assertTrue(bs.all { it.style.textIndentEm > 0f })
    }

    @Test
    fun `ordered list items are handled the same`() {
        assertEquals(listOf("one", "two"), texts("<ol><li>one</li><li>two</li></ol>"))
    }

    @Test
    fun `blockquote carries an indent and spacing`() {
        val b = blocks("<blockquote>quoted</blockquote>").single()
        assertTrue(b.style.textIndentEm > 0f && b.style.spaceBeforePx > 0f)
    }

    @Test
    fun `pre blocks keep a tighter line height preset`() {
        val b = blocks("<pre>code()</pre>").single()
        assertEquals("code()", b.text)
        assertTrue(b.style.lineHeightMultiple!! < 1.5f)
    }

    @Test
    fun `table cells become blocks`() {
        assertEquals(listOf("a", "b", "c"), texts("<table><tr><td>a</td><td>b</td></tr><tr><td>c</td></tr></table>"))
    }

    @Test
    fun `th cells become blocks too`() {
        assertEquals(listOf("H"), texts("<table><tr><th>H</th></tr></table>"))
    }

    @Test
    fun `definition lists map dt and dd`() {
        assertEquals(listOf("term", "def"), texts("<dl><dt>term</dt><dd>def</dd></dl>"))
    }

    @Test
    fun `figcaption becomes a block`() {
        assertEquals(listOf("cap"), texts("<figure><img src='x'/><figcaption>cap</figcaption></figure>"))
    }

    @Test
    fun `header footer aside main are block containers`() {
        assertEquals(listOf("h", "f", "a", "m"), texts("<header>h</header><footer>f</footer><aside>a</aside><main>m</main>"))
    }

    // ---------------------------------------------------- inline elements

    @Test
    fun `inline markup is dropped but text flows into the block`() {
        val html = "<p><b>B</b><strong>S</strong><i>I</i><em>E</em><u>U</u>" +
            "<span>sp</span><a href='x'>A</a><sup>sup</sup><sub>sub</sub>" +
            "<code>c</code><small>sm</small><mark>m</mark></p>"
        // No whitespace between the tags: the text concatenates as-is.
        assertEquals("BSIEUspAsupsubcsmm", blocks(html).single().text)
    }

    @Test
    fun `br forces a block boundary`() {
        val bs = blocks("<p>line1<br/>line2</p>")
        assertEquals(listOf("line1", "line2"), bs.map { it.text })
    }

    @Test
    fun `br pair creates an empty middle block that is dropped`() {
        assertEquals(listOf("a", "b"), texts("<p>a<br><br>b</p>"))
    }

    @Test
    fun `whitespace collapses across inline boundaries`() {
        assertEquals("a b c", blocks("<p>a <b> b</b>  c</p>").single().text)
    }

    @Test
    fun `nbsp survives collapsing`() {
        assertEquals("a\u00A0b", blocks("<p>a&nbsp;b</p>").single().text)
    }

    // ------------------------------------------------------- entities

    @Test
    fun `named numeric and hex entities decode`() {
        val expected = "<a>&\"'" + "\u00A9\u2026\u2014"
        assertEquals(expected, blocks("<p>&lt;a&gt;&amp;&quot;&apos;&#169;&#x2026;&#8212;</p>").single().text)
    }

    @Test
    fun `unknown entities stay literal`() {
        assertEquals("&unknown; &nope;", blocks("<p>&unknown; &nope;</p>").single().text)
    }

    @Test
    fun `broken numeric entities stay literal`() {
        assertEquals("&#zz; &#xGG;", blocks("<p>&#zz; &#xGG;</p>").single().text)
    }

    // ------------------------------------------------- hidden content

    @Test
    fun `script and style content is dropped`() {
        assertEquals(listOf("text"), texts("<script>var x = '<p>no</p>';</script><style>p{}</style><p>text</p>"))
    }

    @Test
    fun `head title and template are dropped`() {
        assertEquals(listOf("body"), texts("<head><title>t</title></head><template>x</template><p>body</p>"))
    }

    @Test
    fun `comments and doctype are dropped`() {
        assertEquals(listOf("x"), texts("<!DOCTYPE html><!-- note --><p>x</p>"))
    }

    @Test
    fun `cdata content is treated as text`() {
        assertEquals(listOf("raw <b> not markup"), texts("<![CDATA[raw <b> not markup]]>"))
    }

    // -------------------------------------------------- CFI identity

    @Test
    fun `element id is captured as the cfi assertion`() {
        val b = blocks("<p id='ch1-para2'>x</p>").single()
        assertEquals("ch1-para2", b.elementId)
    }

    @Test
    fun `void elements consume a sibling slot for cfi fidelity`() {
        val bs = blocks("<div><img src='i.png'/><p>a</p></div>")
        assertEquals(1, bs.size)
        // img took slot 1, p took slot 2 — matching the CFI element step.
        assertEquals(2, bs[0].elementIndex)
    }

    @Test
    fun `sourceOffsets survive inline boundaries`() {
        // A whitespace run spanning the inline boundary must collapse to one
        // space AND keep the mapping to the raw source.
        val b = blocks("<p>a  <b>b</b></p>").single()
        assertEquals("a b", b.text)
        val offsets = b.sourceOffsets
        assertTrue(offsets != null && offsets.size == 3)
        assertEquals(listOf(0, 1, 3), offsets!!.toList())
    }

    // --------------------------------------------------- misc shapes

    @Test
    fun `empty and whitespace-only blocks are dropped`() {
        assertEquals(0, blocks("<p></p><p>   </p><div>\n\t</div>").size)
    }

    @Test
    fun `full xhtml document wrapper is transparent`() {
        val html = "<html><head><title>T</title></head><body><h1>C</h1><p>x</p></body></html>"
        assertEquals(listOf("C", "x"), texts(html))
    }

    @Test
    fun `uppercase and mixed case tags are recognised`() {
        assertEquals(listOf("a", "b"), texts("<P>a</P><DiV>b</DiV>"))
    }

    @Test
    fun `bogus tag-like names produce no phantom text`() {
        // "<Br-TagImpossible/>" parses as an unknown self-closing element —
        // not a `<br>`, not text.
        assertEquals(listOf("a"), texts("<p>a</p><Br-TagImpossible/>"))
    }

    @Test
    fun `attribute values may contain gt and lt when quoted`() {
        val b = blocks("<p title='a>b'>x</p>").single()
        assertEquals("x", b.text)
    }

    @Test
    fun `self closing block does not hold a scope`() {
        assertEquals(listOf("a"), texts("<div/><p>a</p>"))
    }

    @Test
    fun `deep nesting flattens linearly`() {
        val depth = 50
        val html = "<div>".repeat(depth) + "deep" + "</div>".repeat(depth)
        val bs = blocks(html)
        assertEquals(listOf("deep"), bs.map { it.text })
    }

    @Test
    fun `large text block stays in one piece`() {
        val big = "word ".repeat(20_000)
        val bs = blocks("<p>$big</p>")
        assertEquals(1, bs.size)
        assertTrue(bs[0].text.length > 90_000)
    }
}

