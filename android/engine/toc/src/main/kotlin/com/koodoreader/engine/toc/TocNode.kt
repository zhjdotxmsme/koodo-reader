package com.koodoreader.engine.toc

/**
 * One node in the EPUB table-of-contents tree.
 *
 * EPUB TOC entries come from two possible sources (checked in order):
 *  1. `nav.xhtml` — `<nav epub:type="toc">` with `<ol>/<li>/<a href>` hierarchy
 *  2. `toc.ncx` — the legacy NCX format with `<navPoint>/<navLabel>/<content src>`
 *
 * Both are mapped to this flat data class by [TocModel].
 *
 * @property title human-readable label (from `<text>` / `<a>`).
 * @property href the href as written in the source file: a relative path
 *   (`chapter01.xhtml`), a fragment-only (`#anchor`), or a full epubcfi:.
 *   Use [EpubPackageResolver] to resolve to a CFI target.
 * @property level nesting depth: 0 = top-level (root `<ol>` children), 1+ = nested.
 * @property cfiTarget resolved epubcfi: CFI string, or `null` when [href] could
 *   not be resolved (e.g. the target spine item does not exist in the package).
 * @property children ordered sub-nodes; empty list for leaf entries.
 */
data class TocNode(
    val title: String,
    val href: String,
    val level: Int,
    val cfiTarget: String?,
    val children: List<TocNode>,
)

/**
 * Contract for resolving a href (from the spine, nav, or TOC) to an epubcfi: CFI
 * inside an EPUB package.
 *
 * The caller holds the open EPUB package (zip entries, opf XML), so this
 * interface lets [TocModel] stay decoupled from the actual EPUB parsing logic.
 *
 * Return `null` when [href] cannot be resolved — [TocNode.cfiTarget] will be
 * set to `null` and parsing continues gracefully.
 */
fun interface EpubPackageResolver {
    /**
     * Resolve [href] (a relative path like `chapter01.xhtml` or a fragment
     * like `chapter01.xhtml#anchor`) to an epubcfi: CFI string.
     *
     * @param href the raw href from the TOC / NCX / spine.
     * @return a resolved epubcfi: CFI string, or `null` if the target does not
     *   exist in the package.
     */
    fun resolveHref(href: String): String?
}
