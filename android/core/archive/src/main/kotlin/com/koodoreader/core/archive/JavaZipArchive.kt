package com.koodoreader.core.archive

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * The java.util.zip-backed [ZipArchive]. Internal — create via [ZipArchives].
 *
 * Entry model is materialised once; lookups go through a lowercase-keyed
 * index so the case-insensitive fallback never rescans the archive.
 */
internal class JavaZipArchive(
    override val file: File,
    private val zip: ZipFile,
) : ZipArchive {

    /** name → ArchiveEntry, archive order. */
    private val byName: Map<String, ArchiveEntry>
    override val entries: List<ArchiveEntry>

    /** lowercase name → ORIGINAL entry name (case-mangled packages). */
    private val lowerIndex: Map<String, String>

    init {
        val seq = zip.entries().asSequence()
        val list = ArrayList<ArchiveEntry>()
        val lower = HashMap<String, String>()
        seq.forEach { e ->
            val entry = ArchiveEntry(
                name = e.name,
                sizeBytes = if (e.size < 0) -1L else e.size,
                isDirectory = e.isDirectory,
            )
            list.add(entry)
            lower.putIfAbsent(e.name.lowercase(), e.name)
        }
        entries = list
        byName = list.associateBy { it.name }
        lowerIndex = lower
    }

    private fun zipEntryOf(name: String): ZipEntry? {
        // Exact first (byName keys come from the same ZipFile), then CI.
        if (byName.containsKey(name)) return zip.getEntry(name)
        val original = lowerIndex[name.lowercase()] ?: return null
        return zip.getEntry(original)
    }

    override fun entry(name: String): ArchiveEntry? {
        byName[name]?.let { return it }
        val original = lowerIndex[name.lowercase()] ?: return null
        return byName[original]
    }

    override fun openStream(name: String): InputStream {
        val e = zipEntryOf(name)
            ?: throw ArchiveEntryNotFoundException(name)
        return zip.getInputStream(e)
    }

    override fun close() {
        zip.close()
    }
}
