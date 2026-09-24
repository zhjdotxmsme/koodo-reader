package com.koodoreader.engine.layout

/**
 * The DOM-equivalent layer for the native engine.
 *
 * The desktop engine paginates an XHTML document tree inside the browser's DOM
 * (kookit `layoutUtil.ts` walks `document.elements`), which is impossible in
 * Kotlin. This module therefore paginates a **flattened block model** instead:
 * a spine item is a list of text blocks, each block a run of (whitespace
 * collapsed) characters plus the CSS properties the layout engine consumes.
 *
 * The model is deliberately *narrow* on purpose — it is the subset of the DOM
 * that affects pagination. Decorative elements (lists, tables, block quotes,
 * figures) are flattened into text blocks by the EPUB parse layer (P2, a later
 * card), which is the same approximation the desktop engine makes for a paginated
 * view.
 *
 * CFI fidelity: `TextBlock` keeps a mapping from a collapsed character index to
 * the original XHTML text-node character index ([TextBlock.sourceOffsets]).
 * Without it, collapsing whitespace would silently shift CFI character offsets
 * and annotations would land on the wrong character (ADR-002's core risk).
 */

/**
 * Per-block CSS overrides. Every field is optional: a `null` inherits from
 * [LayoutTokens], which mirrors how the desktop engine cascades the user's
 * reader settings onto the book's own CSS.
 *
 * @property fontSizeScale ratio against [LayoutTokens.fontSizePx] (an `<h1>`
 *   is typically 1.5x, a `<pre>` 0.9x).
 * @property lineHeightMultiple CSS `line-height`; overrides the user setting for
 *   this block only (e.g. the desktop stylesheet sets 1.8 for tables).
 * @property textIndentEm CSS `text-indent` in em, added to the user's
 *   first-line indent.
 */
data class ParagraphStyle(
    val fontSizeScale: Float = 1f,
    val lineHeightMultiple: Float? = null,
    val textAlign: TextAlign? = null,
    val textIndentEm: Float = 0f,
    val spaceBeforePx: Float = 0f,
    val spaceAfterPx: Float = 0f,
) {
    init {
        require(fontSizeScale > 0f) { "fontSizeScale must be > 0 (was $fontSizeScale)" }
        require(lineHeightMultiple == null || lineHeightMultiple > 0f) {
            "lineHeightMultiple must be > 0 (was $lineHeightMultiple)"
        }
    }

    companion object {
        val DEFAULT: ParagraphStyle = ParagraphStyle()
    }
}

/**
 * One text block — the native equivalent of an XHTML block element (`<p>`,
 * `<h1>`..`<h6>`, `<li>`...).
 *
 * @property text the block's text with CSS whitespace already collapsed
 *   (see [TextBlock.of]).
 * @property elementIndex the block's index among its siblings, i.e. the CFI
 *   element step. This is what makes a CFI addressable.
 * @property elementId the element's `id` attribute; used as the CFI `[id]`
 *   assertion when present (kookit prefers ids over indices).
 * @property sourceOffsets collapsed char i -> original XHTML text-node char,
 *   or `null` when no collapsing happened.
 */
data class TextBlock(
    val text: String,
    val style: ParagraphStyle = ParagraphStyle.DEFAULT,
    val elementIndex: Int = 0,
    val elementId: String? = null,
    val sourceOffsets: IntArray? = null,
) {
    init {
        require(elementIndex >= 0) { "elementIndex must be >= 0 (was $elementIndex)" }
        require(sourceOffsets == null || sourceOffsets.size == text.length) {
            "sourceOffsets must be the same length as text (text=${text.length}, offsets=${sourceOffsets?.size})"
        }
    }

    /**
     * Convert a collapsed-text index to the original XHTML text-node index, so
     * a CFI character offset stays valid after whitespace collapsing.
     * Out-of-range indices are clamped to the last character.
     */
    fun sourceIndexFor(collapsedIndex: Int): Int {
        val offsets = sourceOffsets ?: return collapsedIndex.coerceIn(0, (text.length - 1).coerceAtLeast(0))
        return offsets[collapsedIndex.coerceIn(0, (text.length - 1).coerceAtLeast(0))]
    }

    /** True when this block would produce no line at all (whitespace-only text). */
    val isEmpty: Boolean
        get() = text.isEmpty()

    companion object {
        /**
         * Build a block, applying the CSS `white-space: normal` processing the
         * browser does before layout:
         *
         *  - runs of collapsible whitespace (` `, `\t`, `\n`, `\r`, `\f`, `\v`)
         *    collapse to a single space;
         *  - leading/trailing whitespace is trimmed;
         *  - U+00A0 (NBSP) is **not** collapsed — the browser treats it as a
         *    regular, non-breakable character.
         *
         * The character index mapping is recorded so CFI offsets can still be
         * resolved against the original text node.
         */
        fun of(
            text: String,
            elementIndex: Int,
            elementId: String? = null,
            style: ParagraphStyle = ParagraphStyle.DEFAULT,
        ): TextBlock {
            val (collapsed, offsets) = collapse(text)
            return TextBlock(collapsed, style, elementIndex, elementId, offsets)
        }

        /**
         * @return the collapsed text and the index mapping (`null` when the text
         *   is unchanged).
         */
        fun collapse(raw: String): Pair<String, IntArray?> {
            val out = StringBuilder(raw.length)
            val offsets = IntArray(raw.length)
            var j = 0
            var runStart = -1
            for (i in raw.indices) {
                val ch = raw[i]
                if (isCollapsibleWhitespace(ch)) {
                    if (runStart < 0) runStart = i
                    continue
                }
                if (runStart >= 0) {
                    // Only insert a space when it is *between* characters: leading
                    // whitespace is trimmed, which matches the browser.
                    if (out.isNotEmpty()) {
                        out.append(' ')
                        offsets[j++] = runStart
                    }
                    runStart = -1
                }
                out.append(ch)
                offsets[j++] = i
            }
            if (j == 0) return "" to null
            return out.toString() to if (j == raw.length && out.length == raw.length) null else offsets.copyOf(j)
        }

        /** True when no collapsing happened at all. */
        fun isCollapsed(raw: String): Boolean = collapse(raw).second != null
    }
}

/**
 * One spine item (chapter) with its flattened text blocks.
 *
 * @property index the spine index, which becomes the CFI spine step (`/6/n`).
 */
data class SpineItem(
    val index: Int,
    val href: String,
    val title: String? = null,
    val blocks: List<TextBlock> = emptyList(),
) {
    init {
        require(index >= 0) { "index must be >= 0 (was $index)" }
    }

    /** Total character count of the chapter (after whitespace collapsing). */
    val charCount: Int get() = blocks.sumOf { it.text.length }
}

/** One spine item of the document, with its flat block index range. */
data class FlatBlock(
    val spine: SpineItem,
    val block: TextBlock,
    /** Index of this block within the document, across all spine items. */
    val blockIndex: Int,
    /** Character offset of this block's first char within the whole document. */
    val globalStart: Int,
)

/** A document: the spine (reading order) flattened into blocks. */
data class EpubDocument(val spine: List<SpineItem>) {

    /** The blocks in reading order, with global indices. */
    fun flatBlocks(): List<FlatBlock> {
        val out = ArrayList<FlatBlock>()
        var blockIndex = 0
        var globalStart = 0
        for (spine in this.spine) {
            for (block in spine.blocks) {
                out.add(FlatBlock(spine, block, blockIndex, globalStart))
                blockIndex++
                globalStart += block.text.length
            }
        }
        return out
    }

    val charCount: Int
        get() = spine.sumOf { it.charCount }
}
