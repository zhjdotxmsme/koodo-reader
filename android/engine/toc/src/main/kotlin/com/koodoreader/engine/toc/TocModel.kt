package com.koodoreader.engine.toc

/**
 * The EPUB table-of-contents model.
 *
 * Built from the EPUB's `nav.xhtml` (EPUB 3 `toc` nav) or `toc.ncx` (EPUB 2
 * NCX), both of which reflect the spine order. The TOC is stored as a tree of
 * [TocNode]s and also as a pre-order flat list for linear search and progress
 * mapping.
 *
 * Construction is split into two phases:
 *  1. Parse the nav/NCX XML and build the tree — this is the responsibility of
 *     the caller (the `engine/epub` module), because it holds the ZIP / XML.
 *  2. Resolve each `TocNode.href` to a CFI via [EpubPackageResolver].
 *
 * Example usage:
 * ```
 * val resolver = object : EpubPackageResolver {
 *     fun resolveHref(href: String): String? {
 *         val spineIdx = spineIndexOf(resolveHrefToSpineItem(href)) ?: return null
 *         return CfiFake.fromIndex(spineIdx) + "!/2/0"
 *     }
 * }
 * val model = TocModel.build(rootNodes, resolver)
 * ```
 */
class TocModel private constructor(
    val rootNodes: List<TocNode>,
    val flatNodes: List<TocNode>,
) {
    /**
     * Pre-order traversal of all nodes.
     *
     * The order matches the reading order (root → first child → sibling), which
     * is the order the TOC appears in the nav / NCX document.
     */
    private fun flatten(nodes: List<TocNode>): List<TocNode> =
        nodes.flatMap { node -> listOf(node) + flatten(node.children) }

    /**
     * Exact-lookup by href as written in the source document.
     *
     * Note: this compares the raw [TocNode.href] string, so `chapter01.xhtml`
     * and `chapter01.xhtml#anchor` are distinct keys. Fragment-only hrefs (`#anchor`)
     * are matched literally.
     *
     * @return the first node with a matching href, or `null` if not found.
     */
    fun lookupByHref(href: String): TocNode? =
        flatNodes.firstOrNull { it.href == href }

    /**
     * Title-based search: exact or prefix-free.
     *
     * @param title the query string.
     * @return all nodes whose [TocNode.title] equals (case-insensitive) or
     *   starts with [title] (case-insensitive prefix), ordered by pre-order
     *   appearance.
     */
    fun lookupByTitle(title: String): List<TocNode> {
        val lower = title.lowercase()
        return flatNodes.filter {
            it.title.lowercase() == lower ||
                it.title.lowercase().startsWith(lower)
        }
    }

    companion object {
        /**
         * Build a [TocModel] from a tree of [TocNode]s by resolving their
         * hrefs to CFI targets via [resolver].
         *
         * @param rootNodes the raw parsed TOC tree (with `cfiTarget = null`).
         * @param resolver resolves each node's href to a CFI string; may return
         *   `null` for unresolvable hrefs.
         * @return a fully-resolved [TocModel].
         */
        fun build(rootNodes: List<TocNode>, resolver: EpubPackageResolver): TocModel {
            fun resolveNode(node: TocNode): TocNode =
                node.copy(
                    cfiTarget = resolver.resolveHref(node.href),
                    children = node.children.map { resolveNode(it) },
                )

            val resolved = rootNodes.map { resolveNode(it) }
            return TocModel(
                rootNodes = resolved,
                flatNodes = resolved.flatMap { flatten(listOf(it)) },
            )
        }

        /**
         * Build a [TocModel] from a tree whose nodes already carry resolved
         * CFI targets (useful for testing with fixed data).
         */
        fun fromResolved(rootNodes: List<TocNode>): TocModel =
            TocModel(rootNodes = rootNodes, flatNodes = rootNodes.flatMap { flatten(it) })
    }
}
