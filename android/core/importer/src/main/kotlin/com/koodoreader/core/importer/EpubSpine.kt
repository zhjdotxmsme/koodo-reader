package com.koodoreader.core.importer

import com.koodoreader.core.archive.ZipArchive
import com.koodoreader.core.archive.ZipArchives
import java.io.File

/**
 * EPUB spine / chapter extraction (EPUB native reader, step ①).
 *
 * `EpubBook` only surfaces metadata + cover (the P1 import needs); the native
 * READER needs the actual reading order: OPF manifest + spine, so each chapter
 * can be flattened (D0 `HtmlFlattener`) and paginated (`engine/layout`).
 *
 * Lifecycle: [open] holds ONE ZipArchive for the whole reading session
 * (R1 facade) — [readChapter] / [readResource] reuse it; [close] when the
 * book screen goes away.
 *
 * CFI note (ADR-002): chapter identity for CFI is the spine order + the
 * (idref-based) element index inside the chapter — both come from the OPF,
 * which is why this class resolves spine order from the OPF and never from
 * the zip's physical entry order.
 */
class EpubSpine private constructor(
    val file: File,
    private val archive: ZipArchive,
    /** The OPF package document's path inside the zip (forward slashes). */
    val opfHref: String,
    /** The reading order, linearized exactly as the OPF `spine` declares. */
    val items: List<SpineItem>,
) : AutoCloseable {

    /** One readable chapter of the spine. [href] is absolute inside the zip. */
    data class SpineItem(
        val index: Int,
        val idref: String,
        val href: String,
        val mediaType: String,
        val isLinear: Boolean,
    )

    /** Chapters, in reading order (linear="no" items are skipped, per spec). */
    val chapters: List<SpineItem> = items.filter { it.isLinear }

    /** Decode an entry's bytes as UTF-8 XHTML text (chapters are XML). */
    fun readChapter(index: Int): String? =
        chapters.getOrNull(index)?.let { readEntryText(it.href) }

    /**
     * Raw bytes of a resource. [href] may be OPF-relative (`images/cover.png`,
     * as written in the manifest) or zip-absolute (`OEBPS/images/cover.png`)
     * — both resolve when a matching entry exists.
     */
    fun readResource(href: String): ByteArray? {
        archive.entry(href)?.let { return archive.readBytes(it.name) }
        val resolved = resolve(href)
        if (resolved != href) {
            archive.entry(resolved)?.let { return archive.readBytes(it.name) }
        }
        return null
    }

    /** Resolve [href] (as written in the OPF/chapter) against the OPF directory. */
    fun resolve(href: String): String = normalizePath(
        EpubBook.resolvePath(opfHref, decodeHref(href)),
    )

    override fun close() {
        archive.close()
    }

    private fun readEntryText(href: String): String? =
        archive.entry(href)?.let { archive.readBytes(it.name)?.toString(Charsets.UTF_8) }

    companion object {

        /** Opens [file] and parses container.xml → OPF → manifest/spine. */
        fun open(file: File): EpubSpine {
            if (!file.isFile) {
                throw IllegalArgumentException("EPUB file not found: ${file.path}")
            }
            val archive = try {
                ZipArchives.open(file)
            } catch (e: com.koodoreader.core.archive.ArchiveException) {
                throw IllegalArgumentException("not a readable EPUB zip: ${file.path}", e)
            }
            return try {
                fromArchive(archive, file)
            } catch (t: Throwable) {
                archive.close()
                throw t
            }
        }

        private fun fromArchive(archive: ZipArchive, file: File): EpubSpine {
            val container = archive.entry("META-INF/container.xml")
                ?.let { archive.readBytes(it.name)?.toString(Charsets.UTF_8) }
            val opfHref = container
                ?.let { firstAttribute(it, "rootfile", "full-path") }
                ?.takeIf { it.isNotEmpty() }
                // Desktop-tolerant fallback: first .opf entry in the archive.
                ?: archive.names().firstOrNull { it.endsWith(".opf", ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "EPUB without an OPF package document: ${file.path}",
                )

            val opfXml = archive.entry(opfHref)
                ?.let { archive.readBytes(it.name)?.toString(Charsets.UTF_8) }
                ?: throw IllegalArgumentException("OPF entry unreadable: $opfHref")

            // manifest: id → (href, media-type). `\b` keeps `<itemref` from
            // matching `<item`; the match spans the WHOLE opening tag so
            // tagAttrs sees the attributes.
            data class ManifestEntry(val href: String, val mediaType: String, val properties: String?)
            val manifest = HashMap<String, ManifestEntry>()
            for (m in Regex("""<item\b[^>]*>""").findAll(opfXml)) {
                val attrs = tagAttrs(m.value)
                val id = attrs["id"] ?: continue
                val href = attrs["href"] ?: continue
                manifest[id] = ManifestEntry(href, attrs["media-type"] ?: "", attrs["properties"])
            }

            // spine: linear reading order (linear="no" entries skipped — they
            // are supplementary, the reading flow never lands on them).
            val spineStart = Regex("""<spine(?=[\s/>])""").find(opfXml)
                ?: throw IllegalArgumentException("OPF without a <spine>: $opfHref")
            val spineBlock = spineBlockOf(opfXml, spineStart.range.first)
            val items = ArrayList<SpineItem>()
            var order = 0
            for (m in Regex("""<itemref\b[^>]*>""").findAll(spineBlock)) {
                val attrs = tagAttrs(m.value)
                val idref = attrs["idref"] ?: continue
                val entry = manifest[idref] ?: continue // dangling idref: skip
                val linear = attrs["linear"]?.lowercase() != "no"
                val href = normalizePath(
                    EpubBook.resolvePath(opfHref, decodeHref(entry.href)),
                )
                items.add(
                    SpineItem(
                        index = order++,
                        idref = idref,
                        href = href,
                        mediaType = entry.mediaType,
                        isLinear = linear,
                    ),
                )
            }
            if (items.isEmpty()) {
                throw IllegalArgumentException("EPUB spine has no readable items: ${file.path}")
            }
            return EpubSpine(file, archive, opfHref, items)
        }

        /**
         * The `<spine>…</spine>` block: from the opening tag to the FIRST
         * closing `</spine>` (a self-closing `<spine/>` yields an empty block,
         * which the empty-items guard reports).
         */
        private fun spineBlockOf(opfXml: String, from: Int): String {
            val openEnd = opfXml.indexOf('>', from)
            if (openEnd < 0) return ""
            if (opfXml[openEnd - 1] == '/') return "" // <spine/> — nothing inside
            val end = opfXml.indexOf("</spine>", openEnd, ignoreCase = true)
            return if (end < 0) opfXml.substring(openEnd + 1) else opfXml.substring(openEnd + 1, end)
        }

        /** Attributes of one tag string (`<item …>`), both quote styles, order-free. */
        private fun tagAttrs(tag: String): Map<String, String> {
            val attrs = HashMap<String, String>()
            for (m in Regex("""([a-zA-Z_:][\w:.-]*)\s*=\s*("([^"]*)"|'([^']*)')""").findAll(tag)) {
                val value = m.groupValues[3].ifEmpty { m.groupValues[4] }
                attrs.putIfAbsent(m.groupValues[1].lowercase(), value)
            }
            return attrs
        }

        private fun firstAttribute(xml: String, tag: String, attrName: String): String? {
            val m = Regex("""<$tag(?=[\s/>])""").find(xml) ?: return null
            return tagAttrs(xml.substring(m.range.first, xml.indexOf('>', m.range.first) + 1)
                .ifEmpty { xml.substring(m.range.first) })[attrName]
        }

        /**
         * EPUB hrefs are percent-encoded IRIs; decode %XX so the resolved
         * path matches the actual zip entry name. Decoding is byte-oriented
         * and re-assembles multi-byte UTF-8 sequences (中文文件名)；`+` is NOT
         * a space here (that's form encoding, not IRI percent-encoding).
         */
        private fun decodeHref(href: String): String {
            if (!href.contains('%')) return href
            val bytes = ByteArray(href.length)
            var n = 0
            var i = 0
            while (i < href.length) {
                val c = href[i]
                if (c == '%' && i + 2 < href.length) {
                    val byte = href.substring(i + 1, i + 3).toIntOrNull(16)
                    if (byte != null && byte in 0..0xFF) {
                        bytes[n++] = byte.toByte()
                        i += 3
                        continue
                    }
                }
                // Raw char: re-encode as UTF-8 (multi-byte chars stay correct).
                for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes[n++] = b
                i++
            }
            return String(bytes, 0, n, Charsets.UTF_8)
        }

        /** Zip entry names use forward slashes; drop leading "./". */
        private fun normalizePath(p: String): String =
            p.replace('\\', '/').removePrefix("./")
    }
}
