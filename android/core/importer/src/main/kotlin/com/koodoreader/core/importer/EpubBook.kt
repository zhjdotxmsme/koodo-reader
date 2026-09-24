package com.koodoreader.core.importer

import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * A cover image extracted from a book archive. [extension] is the file
 * extension for the on-disk cover file (`<bookKey>.<extension>`, the desktop
 * `cover/` naming convention), e.g. "jpeg" (never "jpg").
 */
data class BookCover(val bytes: ByteArray, val extension: String) {
    override fun equals(other: Any?): Boolean =
        other is BookCover && other.extension == extension && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + extension.hashCode()
}

/** EPUB metadata extracted from the OPF (EPUB 2/3, both namespaced or not). */
data class EpubMetadata(
    val title: String?,
    val creator: String?,
    val description: String?,
    val publisher: String?,
)

/** Result of [EpubBook.parse]. [cover] is null when the package has none. */
data class EpubResult(val metadata: EpubMetadata, val cover: BookCover?)

/**
 * EPUB metadata + cover extraction (P1 import) — a minimal, dependency-free
 * reader of the OPF package, on a `java.util.zip.ZipFile` (the book file has
 * already been copied into app storage by [ImportPipeline]).
 *
 * Parity notes vs the desktop engine (kookit `EpubRender` / `epub.js`):
 *  - container: `META-INF/container.xml` → `<rootfile full-path="…">`;
 *    fallback first `*.opf` archive entry (desktop tolerates broken
 *    containers the same way).
 *  - metadata: `dc:title` / `dc:creator` / `dc:description` / `dc:publisher`
 *    (element form, also the `name="dc:*"` meta form); values are entity
 *    decoded and whitespace-collapsed, trimmed.
 *  - cover: `<meta name="cover" content="ID"/>` → manifest `id → href`
 *    (attribute order free); fallback = first manifest image item whose path
 *    contains "cover" (kookit's common-practice fallback).
 *
 * This is NOT the P2 rendering port: only the fields the bookshelf needs.
 */
object EpubBook {

    private const val CONTAINER_PATH = "META-INF/container.xml"

    /** Throws [IllegalArgumentException] when [file] is not a readable EPUB zip. */
    fun parse(file: File): EpubResult {
        if (!file.isFile) throw IllegalArgumentException("EPUB file not found: ${file.path}")
        val zip = try {
            ZipFile(file)
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("not a readable EPUB zip: ${file.path}", e)
        }
        zip.use {
            val opfPath = opfPath(zip) ?: throw IllegalArgumentException(
                "EPUB without an OPF package document",
            )
            val opfXml = readEntry(zip, opfPath)
                ?: throw IllegalArgumentException("OPF entry unreadable: $opfPath")

            val metadata = metadataOf(opfXml)
            val coverHref = coverHref(opfXml)
            val cover = coverHref
                ?.let { resolvePath(opfPath, it) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { entryName ->
                    readEntryBytes(zip, entryName)?.let {
                        BookCover(it, coverExtension(entryName))
                    }
                }
            return EpubResult(metadata, cover)
        }
    }

    // ---- package navigation ------------------------------------------------

    private fun opfPath(zip: ZipFile): String? {
        val container = readEntry(zip, CONTAINER_PATH)
        if (container != null) {
            val fullPath = firstAttribute(container, "rootfile", "full-path")
            if (fullPath != null && fullPath.isNotEmpty()) return fullPath
        }
        // Fallback (desktop-tolerant): first .opf entry in the archive.
        return zip.entries().asSequence()
            .map { it.name }
            .firstOrNull { it.endsWith(".opf", ignoreCase = true) }
    }

    private fun readEntry(zip: ZipFile, name: String): String? =
        readEntryBytes(zip, name)?.toString(Charsets.UTF_8)

    private fun readEntryBytes(zip: ZipFile, name: String): ByteArray? {
        // Zip entry names are case-sensitive; some packages mangle case —
        // try the exact name first, then a case-insensitive scan (bounded:
        // archives in this app's size range stay cheap).
        zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { return it.readBytes() } }
        val match = zip.entries().asSequence()
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
        return match?.let { zip.getInputStream(it).use { it.readBytes() } }
    }

    // ---- metadata ----------------------------------------------------------

    private fun metadataOf(opfXml: String) = EpubMetadata(
        title = valueOfElement(opfXml, "dc:title") ?: metaValueOf(opfXml, "dc:title")
            ?: valueOfElement(opfXml, "title"),
        creator = valueOfElement(opfXml, "dc:creator") ?: metaValueOf(opfXml, "dc:creator")
            ?: valueOfElement(opfXml, "creator"),
        description = valueOfElement(opfXml, "dc:description") ?: metaValueOf(opfXml, "dc:description"),
        publisher = valueOfElement(opfXml, "dc:publisher") ?: metaValueOf(opfXml, "dc:publisher")
            ?: valueOfElement(opfXml, "publisher"),
    )

    private fun valueOfElement(xml: String, name: String): String? {
        val re = Regex(
            Regex.escape("<$name") + """(?=[\s/>])[^>]*>(.*?)</""",
            RegexOption.DOT_MATCHES_ALL,
        )
        return re.find(xml)?.groupValues?.get(1)?.let { normalizeText(it) }
    }

    private fun metaValueOf(xml: String, dcName: String): String? {
        // <meta name="dc:title" content="X"/> — attribute order free.
        val re = Regex("""<meta(?=[\s/>])([^>]*)/>""")
        re.findAll(xml).forEach { m ->
            val attrs = m.groupValues[1]
            if (attr(attrs, "name") == dcName) {
                return attr(attrs, "content")?.let { normalizeText(it) }
            }
        }
        return null
    }

    private fun normalizeText(raw: String): String? {
        val decoded = decodeEntities(raw).trim()
        if (decoded.isEmpty()) return null
        return decoded.split(Regex("[\\s\\u00A0]+")).joinToString(" ")
    }

    private fun decodeEntities(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("&#([0-9]+);")) { m -> m.groupValues[1].toInt().toChar().toString() }
        .replace("&amp;", "&")

    // ---- cover -------------------------------------------------------------

    private fun coverHref(opfXml: String): String? {
        // 1) <meta name="cover" content="ID"/> → manifest id → href
        val coverId = metaCoverId(opfXml)
        if (coverId != null) {
            val href = manifestHrefById(opfXml, coverId)
            if (href != null && href.isNotEmpty()) return href
        }
        // 2) Fallback: first image item whose path contains "cover"
        //    (kookit's common-practice fallback; order = manifest order).
        val fallback = manifestItems(opfXml).firstOrNull { item ->
            (item.mediaType?.startsWith("image/", ignoreCase = true) == true) ||
                (item.href
                    ?.substringAfterLast('.', "")
                    ?.substringBefore('.')?.let { it in IMAGE_FILE_TYPES } == true)
        }
        return fallback?.href?.takeIf { it.contains("cover", ignoreCase = true) }
    }

    private fun metaCoverId(opfXml: String): String? {
        val re = Regex("""<meta(?=[\s/>])([^>]*)>""")
        re.findAll(opfXml).forEach { m ->
            val attrs = m.groupValues[1]
            val name = attr(attrs, "name")?.lowercase(Locale.ROOT)
            if (name == "cover" || name == "cover image") {
                val content = attr(attrs, "content")
                if (!content.isNullOrBlank()) return content.trim()
            }
        }
        return null
    }

    private class ManifestItem(val id: String?, val href: String?, val mediaType: String?)

    private fun manifestItems(opfXml: String): List<ManifestItem> {
        val items = ArrayList<ManifestItem>()
        Regex("""<item(?=[\s/>])([^>]*)/?""").findAll(opfXml).toList().forEach { m ->
            val attrs = m.groupValues[1]
            items.add(
                ManifestItem(
                    id = attr(attrs, "id"),
                    href = attr(attrs, "href"),
                    mediaType = attr(attrs, "media-type"),
                ),
            )
        }
        return items
    }

    private fun manifestHrefById(opfXml: String, id: String): String? =
        manifestItems(opfXml).firstOrNull { it.id == id }?.href

    /**
     * Resolve [href] against the OPF document directory (OPF spec §3),
     * handling absolute (leading "/"), dot and dot-dot segments. Returned in
     * forward-slash zip-entry form.
     */
    internal fun resolvePath(opfPath: String, href: String): String {
        val base = opfPath.substringBeforeLast('/', "")
        val raw = if (href.startsWith("/")) href.removePrefix("/") else base + "/" + href
        val segments = ArrayList<String>(raw.split("/"))
        val out = ArrayList<String>(segments.size)
        for (seg in segments) {
            when (seg) {
                "", "." -> Unit
                ".." -> out.removeLastOrNull()
                else -> out.add(seg)
            }
        }
        return out.joinToString("/")
    }

    private val IMAGE_FILE_TYPES = setOf(
        "jpeg", "jpg", "png", "gif", "webp", "svg", "avif", "bmp", "tiff", "ico",
    )

    private fun coverExtension(entryName: String): String {
        val ext = entryName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (ext in IMAGE_FILE_TYPES) (if (ext == "jpg") "jpeg" else ext) else "png"
    }

    // ---- minimal attribute scanner (kxml-free, both JVM and Android) -------

    /** Extracts attribute [name] from an unquoted-free attribute list. */
    private fun attr(attributes: String, name: String): String? {
        val re = Regex("""$name\s*=\s*("([^"]*)"|'([^']*)')""")
        return re.find(attributes)?.let { m ->
            m.groupValues[2].ifEmpty { m.groupValues[3] }
        }
    }

    private fun firstAttribute(xml: String, tag: String, attrName: String): String? {
        val re = Regex("""<$tag(?=[\s/>])([^>]*)>""")
        return re.find(xml)?.let { attr(it.groupValues[1], attrName) }
    }
}
