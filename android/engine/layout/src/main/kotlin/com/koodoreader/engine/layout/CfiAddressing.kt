package com.koodoreader.engine.layout

import com.koodoreader.engine.cfi.Cfi
import com.koodoreader.engine.cfi.CfiStep
import com.koodoreader.engine.cfi.parseOrNull
import com.koodoreader.engine.cfi.toCfiString

/**
 * Bridge between the native pagination model and the CFI address format shared
 * with the desktop app (ADR-002).
 *
 * This class **never invents an address scheme**: it turns a [LayoutPosition]
 * into a `Cfi` value and lets `:engine:cfi` serialize it, so the native app
 * produces byte-compatible CFIs with the desktop engine.
 *
 * ## The CFI shape
 *
 * A [LayoutPosition] maps to a point CFI with two documents, separated by `!`:
 *
 * ```
 * epubcfi(/6/<spine>!<elementChain>/<elementIndex>/<offsetStep>:<charOffset>)
 *          └─ document 1: the OPF package, the spine item
 *            └──────────── document 2: the chapter's XHTML, down to the text node
 * ```
 *
 * `:<charOffset>` is attached to a **dedicated odd-indexed step**
 * ([CfiMapping.offsetStepIndex]) rather than to the element step. `:engine:cfi`
 * ports foliate-js' serializer, which only emits a character offset on steps
 * whose index is odd (`index % 2`); the block's own element index is
 * DOM-determined and may be even, so attaching the offset there would silently
 * lose the character position. The dedicated step keeps the round-trip exact.
 *
 * ## What is still open (the later `engine/epub` card)
 *
 * [CfiMapping.elementChain] defaults to `[2, 0]` (the shape the EPUB CFI spec's
 * example uses). The *exact* chain the desktop engine emits depends on how its
 * `nodeToParts` indexes the XHTML elements, which only becomes observable once
 * the native EPUB parse exists. The `engine/epub` card pins the real chain with
 * a golden test against desktop-produced CFIs (the "≥99% CFI match" acceptance
 * criterion); every call site then needs only to change the mapping, not the
 * addressing code.
 */
object CfiAddressing {

    /** How a [LayoutPosition] is written as a CFI. */
    data class Mapping(
        /** The package step: `/6` in the desktop convention. */
        val packageIndex: Int = 6,

        /**
         * Element steps between the package step and the block step, i.e. the
         * path to the element that owns the text (`html` → `body`).
         */
        val elementChain: List<Int> = listOf(2, 0),

        /**
         * The step that carries the character offset. Must be **odd** — see the
         * class KDoc for why.
         */
        val offsetStepIndex: Int = 1,
    ) {
        init {
            require(packageIndex >= 0) { "packageIndex must be >= 0 (was $packageIndex)" }
            require(offsetStepIndex % 2 == 1) { "offsetStepIndex must be odd (was $offsetStepIndex)" }
        }
    }

    private val DEFAULT = Mapping()

    /**
     * A chapter-level position (no element) becomes `epubcfi(/6/n!/2/0)` — the
     * chapter's entry element, with no character offset.
     */
    fun toCfi(position: LayoutPosition, mapping: Mapping = DEFAULT): String {
        val spineDocument = listOf(CfiStep(mapping.packageIndex), CfiStep(position.spineIndex))
        val elementChain = mapping.elementChain.map { CfiStep(it) }

        val elementDocument: List<CfiStep> = if (position.isChapterStart) {
            elementChain
        } else {
            elementChain +
                CfiStep(position.elementIndex) +
                CfiStep(mapping.offsetStepIndex, offset = position.charOffset)
        }
        return Cfi.Point(listOf(spineDocument, elementDocument)).toCfiString()
    }

    /**
     * The inverse of [toCfi]. Returns `null` for malformed input, for a range
     * CFI (annotations are handled by the annotation layer), and for a CFI whose
     * document chain does not match [mapping].
     */
    fun fromCfi(cfi: String, mapping: Mapping = DEFAULT): LayoutPosition? {
        val point = parseOrNull(cfi) as? Cfi.Point ?: return null
        val documents = point.documents
        if (documents.size < 2) return null

        val spine = documents[0].lastOrNull() ?: return null
        val elementDocument = documents[1]
        val chain = mapping.elementChain
        if (elementDocument.size < chain.size) return null
        if (elementDocument.take(chain.size).map { it.index } != chain) return null

        val rest = elementDocument.drop(chain.size)
        if (rest.isEmpty()) return LayoutPosition(spine.index, -1, 0)
        if (rest.size < 2) return LayoutPosition(spine.index, rest.first().index, 0)

        val offset = rest.lastOrNull { it.offset != null }?.offset ?: 0
        return LayoutPosition(spine.index, rest.first().index, offset)
    }

    /**
     * [fromCfi] that also validates the result against a real [LayoutResult].
     * Use this when resolving a stored annotation or a reading position.
     */
    fun fromCfi(cfi: String, result: LayoutResult, mapping: Mapping = DEFAULT): LayoutPosition? =
        fromCfi(cfi, mapping)?.takeIf { result.contains(it) }

    /** Round-trip canonicalization, handy for tests and de-duplication. */
    fun canonicalize(position: LayoutPosition, mapping: Mapping = DEFAULT): String =
        toCfi(fromCfi(toCfi(position, mapping), mapping) ?: position, mapping)
}
