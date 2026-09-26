package com.koodoreader.core.archive

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Programmatic zip fixtures for the archive tests (no binary resources). */
internal object TestZips {

    /** Build a zip at [path]; [entries] maps entry name → content bytes. */
    fun write(path: File, entries: Map<String, ByteArray>): File {
        path.parentFile?.mkdirs()
        ZipOutputStream(path.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                if (bytes.isNotEmpty()) zos.write(bytes)
                zos.closeEntry()
            }
        }
        return path
    }

    fun bytes(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    /** The standard fixture used by lookup/stream tests. */
    fun sample(path: File): File = write(
        path,
        linkedMapOf(
            "META-INF/container.xml" to bytes("<container/>"),
            "OEBPS/content.opf" to bytes("<package/>"),
            "OEBPS/images/cover.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            "Images/Page1.PNG" to bytes("page-one"), // case-mangled package
            "chapter1.txt" to bytes("hello world"),
        ),
    )
}

