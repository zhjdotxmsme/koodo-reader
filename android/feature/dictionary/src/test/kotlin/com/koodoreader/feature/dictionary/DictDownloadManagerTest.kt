package com.koodoreader.feature.dictionary

import com.koodoreader.feature.dictionary.mdx.MdictFixtureWriter
import com.koodoreader.feature.dictionary.mdx.MdictFixtureWriter.Entry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Dictionary acquisition: the cloud catalog, the download pipeline and the bundled
 * (in-APK) install path.
 *
 * Desktop parity targets (src/utils/file/dictUtil.ts): `getCloudDictUrl` :145-151,
 * `downloadCloudDict` :154-199, `saveDownloadedDict` :201-209,
 * `getCloudDictDisplayName` :139-142.
 *
 * The HTTP layer is exercised through [ConnectionFactory] so the suite stays offline.
 */
class DictDownloadManagerTest {

    private lateinit var root: File
    private val dictionaryBytes = MdictFixtureWriter.writeMdx(listOf(Entry("apple", "<b>apple</b>")))

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "p6-dict-dl-${System.nanoTime()}")
        root.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // --------------------------------------------------------------- catalog

    @Test
    fun `cloud urls match the desktop scheme`() {
        val manager = DictDownloadManager(DictRepository(root), FakeDownloader(dictionaryBytes))
        // dictUtil.ts:145-151 — .cn only for a signed-in China-region account.
        assertEquals(
            "https://storage.koodoreader.com/dicts/oxford.mdx",
            manager.cloudUrl("oxford", isAuthed = false, region = ServerRegion.GLOBAL),
        )
        assertEquals(
            "https://storage.koodoreader.cn/dicts/oxford.mdx",
            manager.cloudUrl("oxford", isAuthed = true, region = ServerRegion.CHINA),
        )
        assertEquals(
            "https://storage.koodoreader.com/dicts/oxford.mdx",
            manager.cloudUrl("oxford", isAuthed = false, region = ServerRegion.CHINA),
        )
    }

    @Test
    fun `display name follows the ui language`() {
        val manager = DictDownloadManager(DictRepository(root), FakeDownloader(dictionaryBytes))
        val item = CloudDictItem(id = "oxford", name = "Oxford Advanced", translation = "牛津高阶")
        // dictUtil.ts:139-142 — `zh*` locales read the translation.
        assertEquals("牛津高阶", manager.displayName(item, "zh-CN"))
        assertEquals("Oxford Advanced", manager.displayName(item, "en"))
        // A missing translation falls back to the English name.
        assertEquals("Oxford", manager.displayName(CloudDictItem("oxford", "Oxford"), "zh"))
    }

    @Test
    fun `manifest parsing reads both sections`() {
        val manager = DictDownloadManager(DictRepository(root), FakeDownloader(dictionaryBytes)) {
            """
            {
              "dicts": [
                {"id":"oxford","name":"Oxford Advanced","translation":"牛津高阶","source":"CC-BY","bytes":123}
              ],
              "bundled": [
                {"id":"cambridge","name":"Cambridge","asset":"dicts/cambridge.mdx","resourceAsset":"dicts/cambridge.mdd"}
              ]
            }
            """.trimIndent()
        }
        val catalog = manager.catalog()
        assertEquals(1, catalog.size)
        assertEquals("oxford", catalog[0].id)
        assertEquals("牛津高阶", catalog[0].translation)
        assertEquals(123L, catalog[0].bytes)

        val bundled = manager.bundled()
        assertEquals(1, bundled.size)
        assertEquals("dicts/cambridge.mdx", bundled[0].assetPath)
        assertEquals("dicts/cambridge.mdd", bundled[0].resourceAssetPath)

        // A missing / broken manifest degrades to an empty catalog, not a crash.
        val empty = DictDownloadManager(DictRepository(root), FakeDownloader(dictionaryBytes)) { "{ not json" }
        assertTrue(empty.catalog().isEmpty())
    }

    // -------------------------------------------------------------- download

    @Test
    fun `download installs the dictionary and marks it cloud`() {
        val repo = DictRepository(root)
        val downloader = FakeDownloader(dictionaryBytes)
        val manager = DictDownloadManager(repo, downloader) { MANIFEST }

        val outcome = manager.download(
            CloudDictItem(id = "oxford", name = "Oxford Advanced", translation = "牛津高阶"),
            isAuthed = false,
            region = ServerRegion.GLOBAL,
            language = "zh-CN",
        )
        assertTrue("unexpected outcome: $outcome", outcome is OnDemandDownloader.Outcome.Success)

        val entry = repo.entry("oxford")
        assertNotNull(entry)
        assertEquals("牛津高阶", entry!!.name)
        assertEquals(DictSourceKind.CLOUD, entry.source)
        assertEquals(dictionaryBytes.size.toLong(), entry.sizeBytes)
        assertEquals("oxford.mdx", repo.dictFile("oxford")!!.name)

        // The download really is a dictionary: it parses after installation.
        assertEquals("<b>apple</b>", repo.lookup("apple").html)

        // dictUtil.downloadCloudDict sends these two headers (:160-165).
        val request = downloader.lastRequest!!
        assertEquals("no-transform", request.headers["Cache-Control"])
        assertEquals("identity", request.headers["Accept-Encoding"])
        assertEquals("https://storage.koodoreader.com/dicts/oxford.mdx", request.url)
        // The staging copy is moved into place, so no `.part` file is left behind.
        assertFalse(File(repo.tempFolder, "oxford.mdx").exists())
    }

    @Test
    fun `download refuses to overwrite an installed dictionary`() {
        val repo = DictRepository(root)
        val manager = DictDownloadManager(repo, FakeDownloader(dictionaryBytes)) { MANIFEST }
        val item = CloudDictItem(id = "oxford", name = "Oxford")
        manager.download(item, isAuthed = false, region = ServerRegion.GLOBAL)

        val second = manager.download(item, isAuthed = false, region = ServerRegion.GLOBAL)
        assertTrue(second is OnDemandDownloader.Outcome.Failure)
        assertTrue(manager.isInstalled("oxford"))
    }

    @Test
    fun `a failing download leaves no dictionary behind`() {
        val repo = DictRepository(root)
        val manager = DictDownloadManager(repo, FailingDownloader) { MANIFEST }
        val outcome = manager.download(
            CloudDictItem(id = "oxford", name = "Oxford"),
            isAuthed = false,
            region = ServerRegion.GLOBAL,
        )
        assertTrue(outcome is OnDemandDownloader.Outcome.Failure)
        assertNull(repo.entry("oxford"))
        assertFalse(File(repo.dictFolder, "oxford.mdx").exists())
    }

    // --------------------------------------------------------------- bundled

    @Test
    fun `bundled dictionaries are installed from the APK assets`() {
        val repo = DictRepository(root)
        val manager = DictDownloadManager(repo, FakeDownloader(dictionaryBytes)) { MANIFEST }
        val bundled = BundledDict(
            id = "cambridge",
            name = "Cambridge",
            assetPath = "dicts/cambridge.mdx",
            resourceAssetPath = "dicts/cambridge.mdd",
        )

        assertEquals(listOf("cambridge"), manager.pendingBundled().map { it.id })
        val entry = manager.installBundled(bundled, dictionaryBytes, resourceBytes = byteArrayOf(1, 2, 3))
        assertEquals(DictSourceKind.BUNDLED, entry.source)
        assertTrue(entry.enabled)
        assertTrue(manager.pendingBundled().isEmpty())

        // Bundled files live in <dict>/bundled and are found by the repository.
        assertEquals("cambridge.mdx", repo.dictFile("cambridge")!!.name)
        assertEquals("bundled", repo.dictFile("cambridge")!!.parentFile!!.name)
        assertNotNull(repo.resourceFile("cambridge"))
        assertEquals("<b>apple</b>", repo.lookup("apple").html)
    }

    // ------------------------------------------------------------ http layer

    @Test
    fun `http downloader streams to a part file and renames atomically`() {
        val target = File(root, "downloads/oxford.mdx")
        val body = dictionaryBytes
        val downloader = HttpOnDemandDownloader(FakeConnectionFactory { _, _, _ ->
            FakeConnection(200, body)
        })
        val progress = ArrayList<Float>()

        val outcome = downloader.download(
            OnDemandDownloader.Request("oxford", "https://example.com/oxford.mdx", target),
        ) { progress.add(it.fraction) }

        assertTrue("unexpected outcome: $outcome", outcome is OnDemandDownloader.Outcome.Success)
        assertEquals(body.size.toLong(), (outcome as OnDemandDownloader.Outcome.Success).bytes)
        assertTrue(target.isFile)
        assertTrue(body.contentEquals(target.readBytes()))
        assertEquals(1f, progress.last(), 0.001f)
        assertFalse(File(target.parentFile, "oxford.mdx.part").exists())
    }

    @Test
    fun `http downloader resumes a partial file with a range request`() {
        val target = File(root, "downloads/oxford.mdx")
        target.parentFile!!.mkdirs()
        val half = dictionaryBytes.size / 2
        File(target.parentFile, "oxford.mdx.part").writeBytes(dictionaryBytes.copyOfRange(0, half))

        var seenRange: Long? = null
        val downloader = HttpOnDemandDownloader(FakeConnectionFactory { _, rangeStart, _ ->
            seenRange = rangeStart
            FakeConnection(206, dictionaryBytes.copyOfRange(half, dictionaryBytes.size))
        })
        val outcome = downloader.download(
            OnDemandDownloader.Request("oxford", "https://example.com/oxford.mdx", target),
        )

        assertEquals(half.toLong(), seenRange)
        assertTrue(outcome is OnDemandDownloader.Outcome.Success)
        assertTrue((outcome as OnDemandDownloader.Outcome.Success).resumed)
        assertTrue(dictionaryBytes.contentEquals(target.readBytes()))
    }

    @Test
    fun `http downloader rejects a truncated transfer`() {
        val target = File(root, "downloads/oxford.mdx")
        val downloader = HttpOnDemandDownloader(FakeConnectionFactory { _, _, _ ->
            FakeConnection(200, dictionaryBytes.copyOfRange(0, 10))
        })
        val outcome = downloader.download(
            OnDemandDownloader.Request(
                id = "oxford",
                url = "https://example.com/oxford.mdx",
                targetFile = target,
                expectedBytes = dictionaryBytes.size.toLong(),
            ),
        )
        assertTrue("expected a failure, got $outcome", outcome is OnDemandDownloader.Outcome.Failure)
        assertTrue((outcome as OnDemandDownloader.Outcome.Failure).reason.contains("truncated"))
        assertFalse("a truncated file must never be published", target.exists())
    }

    @Test
    fun `http downloader honours cancellation`() {
        val target = File(root, "downloads/oxford.mdx")
        val downloader = HttpOnDemandDownloader(FakeConnectionFactory { _, _, _ ->
            FakeConnection(200, dictionaryBytes)
        })
        val outcome = downloader.download(
            OnDemandDownloader.Request(
                id = "oxford",
                url = "https://example.com/oxford.mdx",
                targetFile = target,
                isCancelled = { true },
            ),
        )
        assertEquals(OnDemandDownloader.Outcome.Cancelled, outcome)
        assertFalse(target.exists())
    }

    // ----------------------------------------------------------------- fakes

    private class FakeDownloader(private val content: ByteArray) : OnDemandDownloader {
        var lastRequest: OnDemandDownloader.Request? = null

        override fun download(
            request: OnDemandDownloader.Request,
            onProgress: (OnDemandDownloader.Progress) -> Unit,
        ): OnDemandDownloader.Outcome {
            lastRequest = request
            request.targetFile.parentFile?.mkdirs()
            request.targetFile.writeBytes(content)
            onProgress(OnDemandDownloader.Progress(content.size.toLong(), content.size.toLong()))
            return OnDemandDownloader.Outcome.Success(request.targetFile, content.size.toLong(), false)
        }
    }

    private object FailingDownloader : OnDemandDownloader {
        override fun download(
            request: OnDemandDownloader.Request,
            onProgress: (OnDemandDownloader.Progress) -> Unit,
        ): OnDemandDownloader.Outcome = OnDemandDownloader.Outcome.Failure("network unavailable")
    }

    private class FakeConnectionFactory(
        private val handler: (String, Long?, Map<String, String>) -> Connection,
    ) : ConnectionFactory {
        override fun open(url: String, rangeStart: Long?, headers: Map<String, String>): Connection =
            handler(url, rangeStart, headers)
    }

    private class FakeConnection(
        override val responseCode: Int,
        private val body: ByteArray,
    ) : Connection {
        override val contentLength: Long get() = body.size.toLong()
        override fun stream(): InputStream = ByteArrayInputStream(body)
        override fun close() {}
    }

    private companion object {
        val MANIFEST = """
            {"dicts":[{"id":"oxford","name":"Oxford Advanced","translation":"牛津高阶"}],
             "bundled":[{"id":"cambridge","name":"Cambridge","asset":"dicts/cambridge.mdx"}]}
        """.trimIndent()
    }
}
