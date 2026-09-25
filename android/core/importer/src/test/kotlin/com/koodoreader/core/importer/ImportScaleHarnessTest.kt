package com.koodoreader.core.importer

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir

/**
 * Opt-in scale harness for the P1 import path (gap for the board's P1-IMPORT
 * card, which cannot run here: this machine has no adb device and no AVD).
 *
 * What it buys over [ImportPipelineTest]'s 1000-book TXT case:
 *  - **mixed formats**: 50 % TXT, 30 % EPUB *with a cover entry*, 20 % CBZ with
 *    pages — the enrich paths (ZIP open, OPF parse, cover bytes, natural sort)
 *    are what a real library stresses, not plain copies;
 *  - **per-book latency percentiles** (P50/P90/P95/P99), the shape the card asks
 *    for, so the device run has a JVM reference to be compared against;
 *  - **peak heap sampling** while the batch runs, under the heap cap the Gradle
 *    task sets (`-Pheap`, default 256m) — a bounded-heap claim rather than a
 *    vague "it passed";
 *  - **machine-readable output** (`IMPORT_SCALE_JSON=…`) so
 *    `scripts/measure-import-jvm.js` can record it in `docs/benchmarks/` and the
 *    baseline JSON instead of a human copying numbers out of a log.
 *
 * It is NOT the card's device measurement: JVM heap classes and a desktop CPU
 * are not an ARM device, and Android-only paths (SAF, Room, covers on disk) are
 * not exercised. Numbers from here must be labelled `measured-jvm`.
 *
 * Run:  node scripts/measure-import-jvm.js [--books 1000] [--heap 256m]
 *   or: gradle -p android :core:importer:importScaleTest -PimportBooks=1000 -PimportHeap=256m
 */
@EnabledIfSystemProperty(named = "koodo.import.scale", matches = "true")
class ImportScaleHarnessTest {

    @TempDir
    lateinit var workRoot: File

    private val books = System.getProperty("koodo.import.scale.n")?.toIntOrNull() ?: 1000

    /** 1×1 PNG; real image bytes so the cover paths do actual work. */
    private val png = ByteArray(67).also {
        // Minimal PNG signature + IHDR/IDAT/IEND are not required by the parsers
        // (they copy bytes), but a real signature keeps the fixture honest.
        it[0] = 0x89.toByte()
        it[1] = 0x50
        it[2] = 0x4E
        it[3] = 0x47
    }

    @Test
    fun `mixed format import scale`() = runBlocking {
        val srcDir = File(workRoot, "src").apply { mkdirs() }
        val batchOut = File(workRoot, "books-batch").apply { mkdirs() }
        val loopOut = File(workRoot, "books-loop").apply { mkdirs() }

        var txt = 0
        var epub = 0
        var cbz = 0
        val sources = (0 until books).map { index ->
            when (index % 10) {
                in 0..4 -> {
                    txt++
                    val file = File(srcDir, "t$index.txt")
                    file.writeText("Unique body $index\n".repeat(40))
                    BookSource("t$index.txt", { file.inputStream() })
                }

                in 5..7 -> {
                    epub++
                    val file = File(srcDir, "e$index.epub")
                    file.writeBytes(epubBytes(index))
                    BookSource("e$index.epub", { file.inputStream() })
                }

                else -> {
                    cbz++
                    val file = File(srcDir, "c$index.cbz")
                    file.writeBytes(cbzBytes(index))
                    BookSource("c$index.cbz", { file.inputStream() })
                }
            }
        }

        // ── batch: total throughput + peak heap under the task's heap cap ──────
        val runtime = Runtime.getRuntime()
        System.gc()
        var peakHeap = 0L
        var sampling = true
        val sampler = Thread {
            while (sampling) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                if (used > peakHeap) peakHeap = used
                try {
                    Thread.sleep(5)
                } catch (ignored: InterruptedException) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true; start() }

        val batchStart = System.nanoTime()
        val batch = pipeline().process(sources, batchOut)
        val batchMs = (System.nanoTime() - batchStart) / 1_000_000
        sampling = false
        sampler.join(500)

        assertEquals(books, batch.imported, "failures: ${batch.failures.take(3)}")
        assertEquals(0, batch.duplicates)
        assertEquals(0, batch.unsupported)
        assertEquals(books, batch.records.map { it.md5 }.toSet().size, "md5 uniqueness")
        assertEquals(books, batch.records.map { it.key }.toSet().size, "key uniqueness")

        // Enrich really happened for the archive formats.
        assertEquals(epub, batch.records.count { it.format == "EPUB" })
        assertEquals(cbz, batch.records.count { it.format == "CBZ" })
        assertEquals(txt, batch.records.count { it.format == "TXT" })
        assertTrue(
            batch.covers.size >= epub,
            "EPUB covers extracted: expected >=$epub, got ${batch.covers.size}",
        )

        // ── per book: the latency distribution the acceptance card asks for ────
        val perBookMs = LongArray(books)
        for ((index, source) in sources.withIndex()) {
            val started = System.nanoTime()
            pipeline().process(listOf(source), loopOut)
            perBookMs[index] = (System.nanoTime() - started) / 1_000_000
        }
        perBookMs.sort()

        val peakMb = peakHeap / (1024.0 * 1024.0)
        val capMb = (runtime.maxMemory() / (1024.0 * 1024.0))
        val report = buildString {
            append("IMPORT_SCALE_JSON={")
            append("\"books\":$books,")
            append("\"formats\":{\"txt\":$txt,\"epub\":$epub,\"cbz\":$cbz},")
            append("\"batch\":{")
            append("\"imported\":${batch.imported},")
            append("\"duplicates\":${batch.duplicates},")
            append("\"failed\":${batch.failures.size},")
            append("\"elapsedMs\":$batchMs,")
            append("\"peakHeapMb\":${peakMb.roundToInt()},")
            append("\"heapCapMb\":${capMb.roundToInt()}")
            append("},")
            append("\"perBookMs\":{")
            append("\"p50\":${percentile(perBookMs, 50)},")
            append("\"p90\":${percentile(perBookMs, 90)},")
            append("\"p95\":${percentile(perBookMs, 95)},")
            append("\"p99\":${percentile(perBookMs, 99)},")
            append("\"max\":${perBookMs.last()}")
            append("}}")
        }
        println(report)
        println(
            "[import-scale] $books books ($txt txt / $epub epub / $cbz cbz): " +
                "batch ${batchMs} ms, peak heap ${peakMb.roundToInt()} MB of ${capMb.roundToInt()} MB cap",
        )
    }

    private fun pipeline() = ImportPipeline()

    /** Nearest-rank percentile of an ascending array (the acceptance-card shape). */
    private fun percentile(sorted: LongArray, p: Int): Long {
        if (sorted.isEmpty()) return 0
        val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun epubBytes(index: Int): ByteArray {
        val container =
            "<?xml version=\"1.0\"?><container version=\"1.0\"><rootfiles>" +
                "<rootfile full-path=\"OEBPS/content.opf\" " +
                "media-type=\"application/oebps-package+xml\"/></rootfiles></container>"
        val opf =
            "<package xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><metadata>" +
                "<dc:title>Scale Book $index</dc:title><dc:creator>Harness</dc:creator>" +
                "<meta name=\"cover\" content=\"cover-img\"/></metadata>" +
                "<manifest>" +
                "<item id=\"cover-img\" href=\"images/cover.png\" media-type=\"image/png\"/>" +
                "<item id=\"c1\" href=\"c1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "</manifest><spine><itemref idref=\"c1\"/></spine></package>"
        val chapter = "<html><body><p>Chapter $index</p></body></html>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            put("META-INF/container.xml", container.toByteArray())
            put("OEBPS/content.opf", opf.toByteArray())
            put("OEBPS/images/cover.png", png)
            put("OEBPS/c1.xhtml", chapter.toByteArray())
        }
        return out.toByteArray()
    }

    private fun cbzBytes(index: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // Deliberately unsorted + a non-image entry, so the natural-order and
            // image-filter paths are exercised (page count must be 3).
            // The notes entry carries the index: identical archives would be
            // deduplicated by md5, which silently shrinks the corpus (caught by
            // the imported==books assertion on the first run of this harness).
            listOf("10.png", "notes.txt", "02.png", "01.png").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(if (name.endsWith(".png")) png else "note-$index".toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
