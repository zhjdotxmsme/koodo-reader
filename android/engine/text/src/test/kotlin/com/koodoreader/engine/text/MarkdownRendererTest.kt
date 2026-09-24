package com.koodoreader.engine.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Markdown subset rendering: every supported construct plus the escaping posture. */
class MarkdownRendererTest {

    private fun render(md: String) = MarkdownRenderer.render(md)

    // ------------------------------------------------------------ headings ---

    @Test
    @DisplayName("# ~ ###### map to h1 ~ h6, with optional closing hashes")
    fun atxHeadings() {
        assertEquals("<h1>Title</h1>\n", render("# Title"))
        assertEquals("<h6>Deep</h6>\n", render("###### Deep"))
        assertEquals("<h2>Closed</h2>\n", render("## Closed ##"))
    }

    @Test
    @DisplayName("Setext headings (=== / --- underline) render as h1/h2")
    fun setextHeadings() {
        assertEquals("<h1>Title</h1>\n", render("Title\n====="))
        assertEquals("<h2>Sub</h2>\n", render("Sub\n---"))
        // A lone `---` with no preceding paragraph is still a thematic break.
        assertEquals("<hr />\n", render("---"))
    }

    @Test
    @DisplayName("Thematic break renders as <hr /> for all three spellings")
    fun thematicBreaks() {
        assertEquals("<hr />\n", render("***"))
        assertEquals("<hr />\n", render("___"))
        assertEquals("<hr />\n", render("- - -"))
    }

    // -------------------------------------------------------------- inline ---

    @Test
    @DisplayName("Bold and italic combinations render with strong/em")
    fun emphasis() {
        assertEquals("<p><strong>bold</strong></p>\n", render("**bold**"))
        assertEquals("<p><em>it</em></p>\n", render("*it*"))
        assertEquals("<p><strong><em>both</em></strong></p>\n", render("***both***"))
        assertEquals("<p><strong>a</strong> and <em>b</em></p>\n", render("**a** and *b*"))
    }

    @Test
    @DisplayName("Intraword underscores stay literal (snake_case)")
    fun intrawordUnderscore() {
        assertEquals("<p>some_variable_name</p>\n", render("some_variable_name"))
        assertEquals("<p><em>emphasised</em></p>\n", render("_emphasised_"))
    }

    @Test
    @DisplayName("Inline code renders verbatim and is never emphasis-parsed")
    fun inlineCode() {
        assertEquals("<p><code>a*b*c</code></p>\n", render("`a*b*c`"))
        assertEquals("<p><code>code</code></p>\n", render("``code``"))
        // Unclosed backtick is literal.
        assertEquals("<p>`unclosed</p>\n", render("`unclosed"))
    }

    @Test
    @DisplayName("Fenced code blocks keep content verbatim and carry a language class")
    fun fencedCode() {
        val md = "```kotlin\nval x = 1 < 2 && \"a\"\n```\n"
        assertEquals(
            "<pre><code class=\"language-kotlin\">val x = 1 &lt; 2 &amp;&amp; &quot;a&quot;\n</code></pre>\n",
            render(md),
        )
        // Tilde fences and an unclosed fence (render to EOF) also work.
        assertEquals("<pre><code>x\n</code></pre>\n", render("~~~\nx\n~~~"))
        assertEquals("<pre><code>dangling\n</code></pre>\n", render("```\ndangling"))
    }

    @Test
    @DisplayName("Indented code blocks (4 spaces) render as <pre><code>")
    fun indentedCode() {
        assertEquals("<pre><code>indented\n</code></pre>\n", render("    indented"))
    }

    // --------------------------------------------------------------- lists ---

    @Test
    @DisplayName("Unordered lists render as a flat <ul> when tight")
    fun unorderedList() {
        // A tight item is unwrapped (`one`, not `<p>one</p>`).
        assertEquals("<ul>\n<li>one</li>\n<li>two</li>\n</ul>\n", render("- one\n- two"))
        assertEquals("<ul>\n<li>one</li>\n<li>two</li>\n</ul>\n", render("* one\n+ two"))
    }

    @Test
    @DisplayName("Ordered lists honour the start number")
    fun orderedList() {
        assertEquals("<ol>\n<li>first</li>\n<li>second</li>\n</ol>\n", render("1. first\n2. second"))
        assertEquals("<ol start=\"3\">", render("3. third\n4. fourth").substringBefore('>') + ">")
    }

    @Test
    @DisplayName("Nested lists keep their nesting")
    fun nestedList() {
        val html = render("- outer\n  - inner\n- outer2")
        assertTrue(html.startsWith("<ul>\n<li>"), html)
        assertTrue(html.contains("<ul>\n<li>inner</li>\n</ul>"), html)
        assertTrue(html.contains("<li>outer2</li>"), html)
        assertTrue(html.trimEnd().endsWith("</ul>"), html)
        // The sub-list must be nested INSIDE the parent item, not a sibling list.
        assertTrue(html.contains("<li><p>outer</p>\n<ul>"), html)
        assertEquals(2, Regex("<ul>").findAll(html).count(), html)
    }

    @Test
    @DisplayName("A blank line between different list markers starts a new list")
    fun listAfterBlankLine() {
        val html = render("- a\n- b\n\n1. c\n2. d")
        assertEquals(
            "<ul>\n<li>a</li>\n<li>b</li>\n</ul>\n<ol>\n<li>c</li>\n<li>d</li>\n</ol>\n",
            html,
        )
    }
    // ---------------------------------------------------------- blockquote ---

    @Test
    @DisplayName("Blockquotes render (and nest)")
    fun blockquote() {
        assertEquals("<blockquote>\n<p>quoted</p>\n</blockquote>\n", render("> quoted"))
        val nested = render("> outer\n> > inner")
        assertTrue(nested.contains("<blockquote>"), nested)
        assertTrue(nested.count { it == '<' } >= 4, nested)
    }

    // --------------------------------------------------------------- links ---

    @Test
    @DisplayName("Inline, reference and shortcut links render as <a>")
    fun links() {
        assertEquals(
            "<p><a href=\"https://ex.com/a?b=1&amp;c=2\">text</a></p>\n",
            render("[text](https://ex.com/a?b=1&c=2)"),
        )
        assertEquals(
            "<p><a href=\"/x\" title=\"T\">t</a></p>\n",
            render("[t](/x \"T\")"),
        )
        assertEquals(
            "<p><a href=\"https://ref.example\">id</a></p>\n",
            render("[id][r]\n\n[r]: https://ref.example"),
        )
        assertEquals(
            "<p><a href=\"https://short.example\">short</a></p>\n",
            render("[short]\n\n[short]: https://short.example"),
        )
    }

    @Test
    @DisplayName("Images render with escaped alt text")
    fun images() {
        assertEquals(
            "<p><img src=\"a.png\" alt=\"alt &quot;x&quot;\" /></p>\n",
            render("![alt \"x\"](a.png)"),
        )
    }

    @Test
    @DisplayName("Autolinks render, other angle brackets are escaped")
    fun autolinks() {
        assertEquals(
            "<p><a href=\"https://ex.com\">https://ex.com</a></p>\n",
            render("<https://ex.com>"),
        )
        assertEquals("<p>&lt;div&gt;</p>\n", render("<div>"))
    }

    @Test
    @DisplayName("Tables render with thead/tbody and alignment")
    fun tables() {
        val md = "| A | B |\n| --- | ---: |\n| 1 | 2 |\n"
        val html = render(md)
        assertTrue(html.contains("<table>"), html)
        assertTrue(html.contains("<th>A</th>"), html)
        assertTrue(html.contains("<th style=\"text-align:right\">B</th>"), html)
        assertTrue(html.contains("<td>1</td>"), html)
        assertTrue(html.contains("<td style=\"text-align:right\">2</td>"), html)
    }

    // ------------------------------------------------------------ security ---

    @Test
    @DisplayName("Raw HTML is escaped: <script> can never reach the DOM")
    fun scriptInjectionIsEscaped() {
        val html = render("<script>alert('xss')</script>")
        assertFalse(html.contains("<script"), html)
        assertTrue(html.contains("&lt;script&gt;"), html)
        assertEquals("<p>&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;</p>\n", html)
    }

    @Test
    @DisplayName("Escaping applies to text, code and attribute values")
    fun escapingEverywhere() {
        assertEquals("<p>a &amp; b &lt; c &gt; d &quot;e&quot;</p>\n", render("a & b < c > d \"e\""))
        assertEquals(
            "<pre><code>&lt;img src=x onerror=alert(1)&gt;\n</code></pre>\n",
            render("```\n<img src=x onerror=alert(1)>\n```"),
        )
        // A quote in the URL cannot break out of the href attribute.
        val link = render("[x](https://e.com/\" onmouseover=\"alert(1))")
        assertFalse(link.contains("onmouseover=\"alert(1)\""), link)
        assertTrue(link.contains("&quot;"), link)
    }

    @Test
    @DisplayName("javascript:/vbscript:/data: URLs are dropped, text is kept")
    fun dangerousUrlsRejected() {
        assertFalse(render("[x](javascript:alert(1))").contains("href"))
        assertFalse(render("[x](JaVaScRiPt:alert(1))").contains("href"))
        assertFalse(render("![x](data:text/html;base64,PHNjcmlwdD4=)").contains("src"))
        // A newline inside the destination splits it into url + title (CommonMark
        // semantics), so the dangerous scheme never reaches `href` verbatim.
        val split = render("[x](java\nscript:alert(1))")
        assertFalse(split.contains("javascript:alert(1)"), split)
        assertFalse(split.contains("href=\"java\n"), split)
        // The link text survives even though the destination was rejected.
        assertEquals("<p>x</p>\n", render("[x](vbscript:msgbox)"))
        // Safe schemes survive.
        assertTrue(render("[x](mailto:a@b.c)").contains("href=\"mailto:a@b.c\""))
    }

    @Test
    @DisplayName("Unsupported syntax is reported by UnsupportedSyntax.scan, not silently dropped")
    fun unsupportedSyntaxScan() {
        val md = """
            |---
            |title: x
            |---
            |Term
            |: definition
            |~~strike~~
            |- [ ] todo
            |Footnote[^1]
            |
            |[^1]: note
            |```
            |~~not counted~~
            |```
        """.trimMargin()
        val found = MarkdownRenderer.UnsupportedSyntax.scan(TextNormalizer.normalize(md))
        assertEquals(5, found.size, "found=$found")
        assertTrue(found.any { it.contains("脚注") }, "$found")
        assertTrue(found.any { it.contains("删除线") }, "$found")
        assertTrue(found.any { it.contains("任务列表") }, "$found")
        assertTrue(found.any { it.contains("定义列表") }, "$found")
        assertTrue(found.any { it.contains("front matter") }, "$found")
        // Plain Markdown must not trip the detector.
        assertTrue(MarkdownRenderer.UnsupportedSyntax.scan("# H\n\n- a\n- b\n").isEmpty())
    }

    // ------------------------------------------------------ realistic input ---

    @Test
    @DisplayName("A realistic mixed document renders without leaking tags or crashing")
    fun mixedDocument() {
        val md = """
            |# 第一章 引言
            |
            |这是**粗体**、*斜体*与 `code` 的混合段落，还有 [链接](https://a.b/c)。
            |
            |> 引用一行
            |>
            |> 引用第二行
            |
            |- 列表项 **一**
            |- 列表项 二
            |
            |1. 有序一
            |2. 有序二
            |
            |```js
            |if (a < b) { console.log("<hi>") }
            |```
            |
            || 列1 | 列2 |
            || --- | --- |
            || a   | b   |
            |
            |---
            |
            |收尾段落。
        """.trimMargin()
        val html = render(md)
        assertTrue(html.startsWith("<h1>第一章 引言</h1>"), html)
        assertTrue(html.contains("<blockquote>"), html)
        assertTrue(html.contains("<ul>"), html)
        assertTrue(html.contains("<ol>"), html)
        assertTrue(html.contains("<table>"), html)
        assertTrue(html.contains("<hr />"), html)
        assertTrue(html.contains("&lt;hi&gt;"), html)
        assertTrue(html.contains("if (a &lt; b)"), html)
        // Both lists are tight, so items are unwrapped (no <p>).
        assertTrue(html.contains("<li>列表项 <strong>一</strong></li>"), html)
        assertTrue(html.contains("<li>有序一</li>"), html)
        // No unescaped raw tag from the input may appear.
        assertFalse(html.contains("<hi>"), html)
        // Every tag we emit is one of ours.
        val tags = Regex("</?([a-zA-Z0-9]+)").findAll(html).map { it.groupValues[1] }.toSet()
        assertTrue(
            tags.all { it in setOf("h1", "h2", "h3", "h4", "h5", "h6", "p", "strong", "em", "code", "pre", "ul", "ol", "li", "blockquote", "hr", "a", "img", "table", "thead", "tbody", "tr", "th", "td", "br") },
            "unexpected tags: $tags",
        )
    }

    @Test
    @DisplayName("Link reference definitions render as nothing (they are metadata)")
    fun referenceDefinitionsDisappear() {
        assertEquals(
            "<p><a href=\"https://ref.example\" title=\"T\">id</a></p>\n",
            render("[id][r]\n\n[r]: https://ref.example \"T\"\n"),
        )
        // A definition with no matching reference must not become a paragraph.
        assertEquals("", render("[unused]: https://nope.example\n"))
    }

    @Test
    @DisplayName("Backslash escapes and hard line breaks are honoured")
    fun escapesAndHardBreaks() {
        assertEquals("<p>*not emphasis*</p>\n", render("\\*not emphasis\\*"))
        assertEquals("<p>line one<br />line two</p>\n", render("line one  \nline two"))
        assertEquals("<p>line one<br />line two</p>\n", render("line one\\\nline two"))
    }

    @Test
    @DisplayName("renderNormalized folds CRLF and tabs first")
    fun renderNormalizedHelper() {
        assertEquals("<h1>A</h1>\n<p>B</p>\n", MarkdownRenderer.renderNormalized("# A\r\n\r\nB\r\n"))
    }

    @Test
    @DisplayName("isSafeUrl / escapeHtml behave as documented")
    fun helperFunctions() {
        assertTrue(MarkdownRenderer.isSafeUrl("images/a.png"))
        assertTrue(MarkdownRenderer.isSafeUrl("#anchor"))
        assertTrue(MarkdownRenderer.isSafeUrl("HTTPS://Example.com"))
        assertFalse(MarkdownRenderer.isSafeUrl("javascript:void(0)"))
        assertFalse(MarkdownRenderer.isSafeUrl(""))
        assertEquals("&lt;a&gt;&amp;&quot;&#39;", MarkdownRenderer.escapeHtml("<a>&\"'"))
        assertEquals("plain", MarkdownRenderer.escapeHtml("plain"))
    }
}
