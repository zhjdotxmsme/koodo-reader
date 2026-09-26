package com.koodoreader.engine.layout

/**
 * XHTML/HTML → [TextBlock] flattener (D0, ADR-007 — see
 * `docs/adr/ADR-007-textblock-flattener.md`).
 *
 * This is the "P2 后续卡" that [BlockModel]'s KDoc reserved: the EPUB parse
 * layer (and the future FB2/DOCX/HTML/MHTML converters, P5.5) run their
 * markup through here to get the flattened block model the layout engine
 * paginates — the DOM-equivalent layer the Kotlin side cannot get from a
 * browser.
 *
 * Semantics:
 *  - **Block elements** (`p div section article aside header footer main
 *    h1..h6 li blockquote pre dt dd td th figcaption hr br`) start a new
 *    [TextBlock]; nested/adjacent blocks each get their own.
 *  - **Inline elements** (`b strong i em u s span a sup sub small code mark
 *    cite q abbr kbd samp var del ins`) stay inside their block: markup is
 *    dropped, text content flows on (the flattened model is deliberately
 *    markup-free).
 *  - **Hidden content** (`script style head title template`, comments,
 *    doctype) is dropped.
 *  - **CFI fidelity**: `elementIndex` = the source element's 1-based index
 *    among its siblings (every element consumes a sibling slot, even the
 *    ones that produce no text); `elementId` comes from the `id` attribute;
 *    `sourceOffsets` (via [TextBlock.of]) map collapsed characters back to
 *    the block's raw source text.
 *  - **Tolerance**: malformed markup never throws — the tokenizer recovers
 *    (see [HtmlTokenizer]) and orphan text becomes its own block inheriting
 *    the enclosing block's index/id. Worst case is linear text, exactly the
 *    "降级到线性文本" the card requires.
 */
interface HtmlFlattener {

    /**
     * Flatten one spine item's markup.
     *
     * @param html the raw XHTML/HTML source of the spine item
     * @param spineIndex the spine position (becomes [SpineItem.index])
     * @param href the spine item's href (becomes [SpineItem.href])
     * @param title optional spine title (e.g. from the OPF manifest)
     */
    fun flatten(html: String, spineIndex: Int = 0, href: String = "", title: String? = null): SpineItem

    companion object {
        /** Shared default instance (stateless — the tokenizer is per-call). */
        val DEFAULT: HtmlFlattener = DefaultHtmlFlattener()
    }
}

/**
 * The stock implementation. Stateless; one [HtmlTokenizer] per [flatten] call.
 */
class DefaultHtmlFlattener : HtmlFlattener {

    override fun flatten(html: String, spineIndex: Int, href: String, title: String?): SpineItem =
        SpineItem(index = spineIndex, href = href, title = title, blocks = blocks(html))

    /** Flatten markup to blocks (no spine wrapper). */
    fun blocks(html: String): List<TextBlock> {
        val out = ArrayList<TextBlock>()
        val tokenizer = HtmlTokenizer(html)

        // The block under construction; null = between blocks (container
        // context). Orphan text after a stray close tag reuses the last
        // block's index/id ("linear degradation").
        var open: PendingBlock? = null
        var lastIndex = 0
        var lastId: String? = null
        var lastStyle = ParagraphStyle.DEFAULT

        fun flush() {
            val pending = open ?: return
            open = null
            // Whitespace-only raw text collapses to nothing — no line, no block.
            if (TextBlock.collapse(pending.raw.toString()).first.isEmpty()) return
            out.add(
                TextBlock.of(
                    text = pending.raw.toString(),
                    elementIndex = pending.elementIndex,
                    elementId = pending.elementId,
                    style = pending.style,
                ),
            )
        }

        fun startBlock(index: Int, id: String?, style: ParagraphStyle) {
            flush()
            open = PendingBlock(index, id, style)
        }

        // Sibling counter stack — every element consumes a slot in its
        // parent's child list (1-based), which is what CFI addressing uses.
        val siblingCounters = IntArray(64)
        var depth = 0

        // Elements whose ENTIRE subtree is hidden. `script`/`style` never
        // reach this loop (the tokenizer swallows content + close tag); for
        // `head`/`title`/`template` we skip tokens here until the matching
        // close tag.
        val dropWithContent: Set<String> = setOf("head", "title", "template")
        var skipping: String? = null

        while (tokenizer.hasNext()) {
            when (val token = tokenizer.next()) {
                is HtmlTokenizer.Token.Text -> {
                    if (skipping != null) continue
                    val pending = open
                    if (pending != null) {
                        pending.raw.append(HtmlTokenizer.decodeEntities(token.raw))
                    } else if (token.raw.isNotBlank()) {
                        // Orphan text (before any block / after a stray
                        // close): degrade to a linear block with the last
                        // block element's identity.
                        startBlock(lastIndex, lastId, lastStyle)
                        open?.raw?.append(HtmlTokenizer.decodeEntities(token.raw))
                    }
                }

                is HtmlTokenizer.Token.Open -> {
                    val name = token.name
                    if (skipping != null) continue
                    if (!token.selfClosing && name in dropWithContent) {
                        skipping = name
                        continue
                    }

                    if (depth < siblingCounters.size - 1) depth++
                    val siblingIndex = ++siblingCounters[depth]

                    if (name in VOID_ELEMENTS) {
                        when (name) {
                            "br" -> flush() // hard line break = block boundary
                            "hr" -> flush() // thematic break: separator, no content
                            // img / link / meta / input / … — no text
                        }
                        depth-- // void elements do not open a scope
                        continue
                    }

                    if (name in BLOCK_ELEMENTS) {
                        val style = styleFor(name)
                        val id = token.attrs["id"]
                        startBlock(siblingIndex, id, style)
                        lastIndex = siblingIndex
                        lastId = id
                        lastStyle = style
                    }
                    // Inline / container elements: keep scanning; their text
                    // lands in the current (or next) block.
                    if (token.selfClosing) depth-- // <div/> — no scope held
                }

                is HtmlTokenizer.Token.Close -> {
                    val name = token.name
                    if (skipping != null) {
                        if (name == skipping) skipping = null
                        continue
                    }
                    if (name in BLOCK_ELEMENTS || name in CONTAINER_ELEMENTS) {
                        flush()
                    }
                    // Every non-void Open pushed one scope (inline included);
                    // match it on close so the sibling counters stay aligned.
                    // Extra/mismatched closes bottom out at the root.
                    if (depth > 0) depth--
                }

                null -> Unit // unreachable behind hasNext()
            }
        }
        flush()
        return out
    }

    private class PendingBlock(
        val elementIndex: Int,
        val elementId: String?,
        val style: ParagraphStyle,
    ) {
        val raw = StringBuilder()
    }

    companion object {

        /** Elements that start a text block (own [TextBlock]). */
        val BLOCK_ELEMENTS: Set<String> = setOf(
            "p", "h1", "h2", "h3", "h4", "h5", "h6",
            "blockquote", "pre", "li", "dt", "dd",
            "td", "th", "figcaption",
            // Sectioning/div elements start blocks only when they carry
            // direct text; they are listed here so `<div>a</div>` yields a
            // block, while pure wrappers produce nothing (empty blocks are
            // dropped).
            "div", "section", "article", "aside", "header", "footer", "main", "caption",
        )

        /** Elements that only group children (no block of their own). */
        val CONTAINER_ELEMENTS: Set<String> = setOf(
            "html", "body", "ul", "ol", "dl", "table", "thead", "tbody", "tfoot", "tr", "figure",
        )

        /** No closing tag, no scope. */
        private val VOID_ELEMENTS: Set<String> = setOf(
            "br", "hr", "img", "area", "base", "col", "embed", "input",
            "link", "meta", "source", "track", "wbr",
        )

        /**
         * Paragraph-style presets per block tag — the same approximations the
         * desktop stylesheet applies for a paginated view.
         */
        private fun styleFor(tag: String): ParagraphStyle = when (tag) {
            "h1" -> ParagraphStyle(fontSizeScale = 1.5f, spaceBeforePx = 12f, spaceAfterPx = 8f)
            "h2" -> ParagraphStyle(fontSizeScale = 1.35f, spaceBeforePx = 10f, spaceAfterPx = 6f)
            "h3" -> ParagraphStyle(fontSizeScale = 1.2f, spaceBeforePx = 10f, spaceAfterPx = 6f)
            "h4" -> ParagraphStyle(fontSizeScale = 1.1f, spaceBeforePx = 8f, spaceAfterPx = 4f)
            "h5" -> ParagraphStyle(fontSizeScale = 1.0f, spaceBeforePx = 8f, spaceAfterPx = 4f)
            "h6" -> ParagraphStyle(fontSizeScale = 0.95f, spaceBeforePx = 8f, spaceAfterPx = 4f)
            "blockquote" -> ParagraphStyle(textIndentEm = 1f, spaceBeforePx = 6f, spaceAfterPx = 6f)
            "pre" -> ParagraphStyle(fontSizeScale = 0.9f, lineHeightMultiple = 1.2f)
            "li" -> ParagraphStyle(textIndentEm = 0.5f)
            "dd" -> ParagraphStyle(textIndentEm = 1f)
            "figcaption" -> ParagraphStyle(fontSizeScale = 0.9f, spaceBeforePx = 2f)
            "th" -> ParagraphStyle(spaceAfterPx = 2f)
            "td" -> ParagraphStyle(spaceAfterPx = 2f)
            else -> ParagraphStyle.DEFAULT
        }
    }
}
