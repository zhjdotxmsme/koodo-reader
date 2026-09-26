package com.koodoreader.core.archive

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class SafeZipExtractorTest {

    @TempDir
    lateinit var dir: File

    // ------------------------------------------------------- normal path

    @Test
    fun `extracts nested entries and skips directories`() {
        val zip = TestZips.write(
            File(dir, "book.zip"),
            linkedMapOf(
                "a/b/c.txt" to TestZips.bytes("deep"),
                "top.txt" to TestZips.bytes("top"),
                "dir/" to ByteArray(0), // directory entry
            ),
        )
        val target = File(dir, "out")
        ZipArchives.open(zip).use { archive ->
            val written = SafeZipExtractor.extract(archive, target)
            assertEquals(listOf("a${File.separator}b${File.separator}c.txt", "top.txt"),
                written.map { it.relativeTo(target).path })
            assertEquals("deep", File(target, "a/b/c.txt").readText())
            assertEquals("top", File(target, "top.txt").readText())
        }
    }

    @Test
    fun `filter selects a subset of entries`() {
        val zip = TestZips.write(
            File(dir, "mixed.zip"),
            linkedMapOf(
                "images/cover.png" to byteArrayOf(1),
                "text/opf.xml" to TestZips.bytes("<x/>"),
            ),
        )
        val target = File(dir, "out2")
        ZipArchives.open(zip).use { archive ->
            val written = SafeZipExtractor.extract(archive, target) { e -> e.name.endsWith(".png") }
            assertEquals(1, written.size)
            assertTrue(written[0].path.endsWith("cover.png"))
        }
    }

    // ------------------------------------------------------- zip slip

    private fun assertSlipRejected(entryName: String) {
        val zip = TestZips.write(File(dir, "slip-${entryName.hashCode()}.zip"), mapOf(entryName to TestZips.bytes("x")))
        val target = File(dir, "slip-out-${entryName.hashCode()}")
        val e = assertThrows<SafeZipExtractor.UnsafeEntryNameException> {
            ZipArchives.open(zip).use { SafeZipExtractor.extract(it, target) }
        }
        assertTrue(e.message!!.contains(entryName))
    }

    @Test
    fun `rejects dot-dot escape`() = assertSlipRejected("../evil.txt")

    @Test
    fun `rejects nested dot-dot escape`() = assertSlipRejected("a/b/../../../evil.txt")

    @Test
    fun `rejects windows dot-dot escape`() = assertSlipRejected("..\\evil.txt")

    @Test
    fun `rejects absolute unix path`() = assertSlipRejected("/etc/evil.txt")

    @Test
    fun `rejects absolute windows path`() = assertSlipRejected("\\evil.txt")

    @Test
    fun `rejects drive letter path`() = assertSlipRejected("C:\\evil.txt")

    @Test
    fun `rejects nul byte name`() = assertSlipRejected("evil\u0000.txt")

    @Test
    fun `rejects empty name`() = assertSlipRejected("")

    @Test
    fun `destinationOf containment holds for lookalike sibling dirs`() {
        // "/target-evil" must not pass as inside "/target".
        val root = File(dir, "target").canonicalFile
        val sibling = File(root.parentFile, "target-evil")
        val e = assertThrows<SafeZipExtractor.UnsafeEntryNameException> {
            SafeZipExtractor.destinationOf(root, sibling.absolutePath)
        }
        assertTrue(e.message!!.contains("evil"))
    }

    // ---------------------------------------------------------- caps

    @Test
    fun `entry count cap is enforced`() {
        val zip = TestZips.write(
            File(dir, "many.zip"),
            (1..5).associate { "f$it.txt" to TestZips.bytes("x") },
        )
        ZipArchives.open(zip).use {
            val e = assertThrows<SafeZipExtractor.LimitExceededException> {
                SafeZipExtractor.extract(it, File(dir, "cap-out"), SafeZipExtractor.Limits(maxEntries = 3))
            }
            assertTrue(e.message!!.contains("maxEntries"))
        }
    }

    @Test
    fun `total uncompressed cap is enforced`() {
        val zip = TestZips.write(
            File(dir, "fat.zip"),
            mapOf(
                "a.bin" to ByteArray(1000),
                "b.bin" to ByteArray(1000),
            ),
        )
        ZipArchives.open(zip).use {
            assertThrows<SafeZipExtractor.LimitExceededException> {
                SafeZipExtractor.extract(
                    it,
                    File(dir, "fat-out"),
                    SafeZipExtractor.Limits(maxTotalUncompressedBytes = 1500),
                )
            }
        }
    }

    @Test
    fun `per entry cap is enforced`() {
        val zip = TestZips.write(File(dir, "one.zip"), mapOf("big.bin" to ByteArray(1000)))
        ZipArchives.open(zip).use {
            assertThrows<SafeZipExtractor.LimitExceededException> {
                SafeZipExtractor.extract(
                    it,
                    File(dir, "one-out"),
                    SafeZipExtractor.Limits(maxEntryUncompressedBytes = 500),
                )
            }
        }
    }
}

