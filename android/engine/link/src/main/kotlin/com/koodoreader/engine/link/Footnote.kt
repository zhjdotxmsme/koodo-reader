package com.koodoreader.engine.link

/**
 * Footnote model: extraction entries → chapter-scoped list → two-way
 * body<->footnote CFI addressing.
 *
 * WHAT THIS LAYER OWNS (engine):
 *  - the DATA model (refs, definitions, the ordered chapter list),
 *  - NUMBER RESOLUTION (which ref belongs to which definition — see
 *    [FootnoteExtractor.extract]),
 *  - TWO-WAY ADDRESSING by number and by CFI ([ChapterFootnotes]).
 *
 * WHAT THIS LAYER DELIBERATELY LEAVES TO THE READER/UI LAYER:
 *  - walking the chapter DOM/XHTML to find `ref` / `definition` elements
 *    and collecting their CFIs + text (needs the document model, `engine/epub`),
 *  - rendering: the footnote dialog (the WebView track does this in
 *    `NativeEventDispatcher.showFootnoteDialog`, a read-only text popup with
 *    a copy button) and the highlight/scroll of a superscript in the body.
 *
 * Why: `NativeEventDispatcher`'s footnote path is payload-driven — the
 * engine sends the footnote TEXT and the shell shows it. This module is the
 * native-track equivalent of that contract but CFI-first, so the Compose
 * reader can also jump body<->footnote instead of only showing text.
 */

/**
 * A superscript/reference marker in the body text (the "[1]" the user taps).
 *
 * @property number resolved footnote number for this chapter (`0` = unresolved
 *   — a ref could not be linked to any definition, see [FootnoteExtractor]);
 *   positive otherwise.
 * @property anchor the ref's own anchor/id as authored (e.g. `#fn-3`, or the
 *   element `id`), preserved for round-tripping and debugging.
 * @property refCfi the CFI of the ref element IN THE BODY (the "正文 side" of
 *   the two-way positioning). Empty string when the producer did not supply
 *   one.
 */
data class FootnoteRef(
    val number: Int,
    val anchor: String = "",
    val refCfi: String = "",
)

/**
 * A footnote DEFINITION (the text block the ref points at).
 *
 * @property chapterKey the chapter identity this footnote belongs to. Kept on
 *   the item (not only on [ChapterFootnotes]) so a `Footnote` is self-describing
 *   when it crosses a boundary (persistence, sync) without its chapter object.
 * @property number resolved footnote number for its chapter (`0` = unresolved /
 *   orphan definition, see [FootnoteExtractor.orphanDefinitions]); positive otherwise.
 * @property text the footnote text (what `showFootnoteDialog` would display).
 * @property cfiTarget the CFI of the definition element (the "脚注 side" of the
 *   two-way positioning — where the reader jumps when the user picks a footnote
 *   from the list). Empty when unknown.
 */
data class Footnote(
    val chapterKey: String,
    val number: Int,
    val text: String,
    val cfiTarget: String = "",
)

/**
 * Footnotes of ONE chapter, ordered.
 *
 * Ordering contract (matches how numbers are read aloud/printed in EPUB):
 *  - [refs] in BODY ORDER (document order of the superscripts) — this is the
 *    order the user encounters them while reading;
 *  - [footnotes] sorted by ASCENDING [Footnote.number] — the numbered list
 *    shown when the user opens "footnotes of this chapter".
 *
 * The list is immutable; produce a new [ChapterFootnotes] to change it.
 *
 * Two-way addressing — the heart of 正文↔脚注双向定位:
 *  - forward  (body → note):  [byNumber] / [byRefCfi] from a tapped ref
 *  - reverse  (note → body):  [refsFor] / [refsForCfiTarget] to jump back
 *
 * CFI comparison is EXACT string equality on purpose: the reader layer owns
 * the document model and hands us already-canonical CFIs (via
 * `engine/cfi`'s `toCfiString`). Doing a full re-parse here would couple this
 * module to CFI syntax for no benefit, and would silently accept CFIs from a
 * different book.
 */
data class ChapterFootnotes(
    val chapterKey: String,
    val refs: List<FootnoteRef>,
    val footnotes: List<Footnote>,
) {
    /** Number of RESOLVED footnotes (orphan definitions are excluded). */
    val size: Int get() = footnotes.size

    val isEmpty: Boolean get() = footnotes.isEmpty()

    // ── forward direction: ref (in body) → footnote definition ──────────────

    /** Body → note, by number. */
    fun byNumber(number: Int): Footnote? =
        footnotes.firstOrNull { it.number == number }

    /** Body → note, by the ref's CFI (exact match on [FootnoteRef.refCfi]). */
    fun byRefCfi(refCfi: String): Footnote? {
        val ref = refs.firstOrNull { it.refCfi == refCfi } ?: return null
        return byNumber(ref.number)
    }

    /** Body → note, from a [FootnoteRef] value (number-driven). */
    fun forRef(ref: FootnoteRef): Footnote? = byNumber(ref.number)

    // ── reverse direction: footnote definition → refs (in body) ─────────────

    /** Note → body refs, by number (a footnote may have several superscripts). */
    fun refsFor(number: Int): List<FootnoteRef> =
        refs.filter { it.number == number }

    /** Note → body refs, by a [Footnote] value. */
    fun refsFor(footnote: Footnote): List<FootnoteRef> = refsFor(footnote.number)

    /** Note → body refs, by the definition's CFI (exact match, empty-safe). */
    fun refsForCfiTarget(cfiTarget: String): List<FootnoteRef> {
        if (cfiTarget.isEmpty()) return emptyList()
        val footnote = footnotes.firstOrNull { it.cfiTarget == cfiTarget } ?: return emptyList()
        return refsFor(footnote)
    }

    companion object {
        /** An empty chapter (no resolved footnotes, no refs, no orphans). */
        fun empty(chapterKey: String): ChapterFootnotes =
            ChapterFootnotes(chapterKey, emptyList(), emptyList())
    }
}

/**
 * A body reference as authored by the producer, BEFORE number resolution.
 *
 * @property refCfi CFI of the ref element (optional, but strongly preferred —
 *   it is what makes the two-way jump possible).
 * @property anchor the ref's anchor/id as authored (may encode the number).
 * @property number an EXPLICITLY supplied number; when non-null it wins over
 *   any number derivable from [anchor].
 */
data class RefEntry(
    val refCfi: String,
    val anchor: String = "",
    val number: Int? = null,
)

/**
 * A footnote definition as authored by the producer, BEFORE number resolution.
 *
 * @property cfiTarget CFI of the definition element (where the text lives).
 * @property anchor the definition's anchor/id as authored (may encode the
 *   number); the ref→definition cross-match uses exact anchor equality.
 * @property number an EXPLICITLY supplied number; wins over [anchor].
 * @property text the footnote text.
 */
data class DefinitionEntry(
    val cfiTarget: String,
    val anchor: String = "",
    val number: Int? = null,
    val text: String,
)

/**
 * Result of [FootnoteExtractor.extract]: the linked chapter plus what did
 * NOT link, so the caller (reader layer) can surface or log it instead of
 * dropping it silently.
 */
data class FootnoteExtraction(
    val chapter: ChapterFootnotes,
    val unlinkedRefs: List<FootnoteRef>,
    val orphanDefinitions: List<Footnote>,
)

/**
 * Number resolution + ref/definition linking for one chapter.
 *
 * RESOLUTION ORDER per item (first non-null wins):
 *  1. an EXPLICIT [RefEntry.number] / [DefinitionEntry.number];
 *  2. the first integer run found in the anchor ([numberFromAnchor],
 *     e.g. `fn-3` / `footnote-12` / `#note_7` → `7`);
 *  3. for REFS ONLY: exact ANCHOR equality with a definition whose anchor
 *     already resolved to a number (handles refs whose own anchor carries no
 *     number but was authored to point at a specific definition).
 *
 * Items that resolve to no number are reported, never thrown away:
 *  - ref   → [FootnoteRef(number = 0)] in [FootnoteExtraction.unlinkedRefs]
 *  - defn  → [Footnote(number = 0)] in [FootnoteExtraction.orphanDefinitions]
 *
 * Duplicate numbers are accepted: [ChapterFootnotes.byNumber] returns the
 * FIRST definition with that number, and [ChapterFootnotes.refsFor] returns
 * ALL refs with it.
 */
object FootnoteExtractor {
    /** First integer run in [anchor] (e.g. `"footnote-12"` → `12`, `"nope"` → `null`). */
    fun numberFromAnchor(anchor: String): Int? =
        NUMBER_REGEX.find(anchor)?.value?.toIntOrNull()

    /**
     * Link [refs] and [definitions] into [ChapterFootnotes] for [chapterKey].
     * Deterministic and allocation-cheap; safe to call per chapter render.
     */
    fun extract(
        chapterKey: String,
        refs: List<RefEntry>,
        definitions: List<DefinitionEntry>,
    ): FootnoteExtraction {
        // Resolve each definition's number (explicit > anchor-derived).
        val defs = definitions.map { it to (it.number ?: numberFromAnchor(it.anchor)) }

        // Anchor → number map for the fallback cross-match (first defn wins per
        // anchor; later duplicates are ignored for resolution purposes only).
        val defNumberByAnchor: Map<String, Int> = defs
            .filter { (_, n) -> n != null && it.first.anchor.isNotEmpty() }
            .associate { (entry, n) -> entry.anchor to (n as Int) }

        // Linked definitions → the ordered (ascending number) chapter list.
        val footnotes = defs
            .mapNotNull { (d, n) -> n?.let { d to it } }
            .sortedBy { it.second }
            .map { (d, n) -> Footnote(chapterKey, n, d.text, d.cfiTarget) }

        // Refs (body order preserved).
        val linked = mutableListOf<FootnoteRef>()
        val unlinked = mutableListOf<FootnoteRef>()
        for (r in refs) {
            val n = r.number
                ?: numberFromAnchor(r.anchor)
                ?: defNumberByAnchor[r.anchor]
            if (n != null) {
                linked += FootnoteRef(n, r.anchor, r.refCfi)
            } else {
                unlinked += FootnoteRef(0, r.anchor, r.refCfi)
            }
        }

        // Orphan definitions (no resolvable number).
        val orphans = defs
            .filter { (_, n) -> n == null }
            .map { (d, _) -> Footnote(chapterKey, 0, d.text, d.cfiTarget) }

        return FootnoteExtraction(
            chapter = ChapterFootnotes(chapterKey, linked, footnotes),
            unlinkedRefs = unlinked,
            orphanDefinitions = orphans,
        )
    }

    private val NUMBER_REGEX = Regex("\\d+")
}
