package com.koodoreader.feature.dictionary

import com.koodoreader.feature.dictionary.mdx.MdictFixtureWriter
import com.koodoreader.feature.dictionary.mdx.MdictFixtureWriter.Entry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Installed-dictionary registry: enable / order / default / persistence / lookup.
 *
 * The desktop counterparts are `dictUtil.ts:111-136` (`customDicts` + `dictList`) and
 * the settings page (dictSetting/component.tsx:28-122), which import / delete /
 * download only — the enable-order-default flags are the Android addition described
 * on [DictEntry].
 */
class DictRepositoryTest {

    private lateinit var root: File

    private val appleDict = MdictFixtureWriter.writeMdx(
        listOf(Entry("apple", "<b>apple</b>"), Entry("apricot", "<b>apricot</b>")),
        title = "Apple Dictionary",
    )

    private val bananaDict = MdictFixtureWriter.writeMdx(
        listOf(Entry("banana", "<b>banana</b>"), Entry("plantain", "<b>plantain</b>")),
        title = "Banana Dictionary",
    )

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "p6-dict-repo-${System.nanoTime()}")
        root.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun repository() = DictRepository(root)

    @Test
    fun `install writes the dictionary file and persists the index`() {
        val repo = repository()
        val entry = repo.install(name = "Apple Dictionary", bytes = appleDict, id = "1000")

        assertEquals("1000", entry.id)
        assertEquals("mdx", entry.extension)
        assertEquals(DictSourceKind.IMPORTED, entry.source)
        assertTrue("the first dictionary becomes the default", entry.isDefault)
        assertTrue(entry.enabled)
        assertNotNull(repo.dictFile("1000"))
        assertTrue(repo.dictFile("1000")!!.isFile)
        // Desktop layout: <root>/dict/<id>.mdx
        assertEquals("1000.mdx", repo.dictFile("1000")!!.name)
        assertEquals("dict", repo.dictFile("1000")!!.parentFile!!.name)

        // A new instance reads the same state back (JSON round-trip).
        val reloaded = repository().entry("1000")
        assertNotNull(reloaded)
        assertEquals("Apple Dictionary", reloaded!!.name)
        assertTrue(reloaded.isDefault)
        assertEquals(appleDict.size.toLong(), reloaded.sizeBytes)
    }

    @Test
    fun `lookup prefers the default dictionary then follows the order`() {
        val repo = repository()
        repo.install(name = "Apple Dictionary", bytes = appleDict, id = "a")
        repo.install(name = "Banana Dictionary", bytes = bananaDict, id = "b")
        // 'b' was installed second, so it is not the default.
        assertEquals("a", repo.defaultEntry()!!.id)

        val hit = repo.lookup("banana")
        assertTrue(hit.found)
        assertEquals("b", hit.entry!!.id)
        assertEquals("<b>banana</b>", hit.html)

        // The default dictionary answers for its own words.
        assertEquals("a", repo.lookup("apple").entry!!.id)
        assertFalse(repo.lookup("dragonfruit").found)
    }

    @Test
    fun `disabling a dictionary removes it from the lookup path`() {
        val repo = repository()
        repo.install(name = "Apple Dictionary", bytes = appleDict, id = "a")
        repo.install(name = "Banana Dictionary", bytes = bananaDict, id = "b")

        repo.setEnabled("b", false)
        assertFalse(repo.lookup("banana").found)
        assertEquals(1, repo.enabledDicts().size)

        repo.setEnabled("b", true)
        assertTrue(repo.lookup("banana").found)
    }

    @Test
    fun `disabling the default clears the default flag`() {
        val repo = repository()
        repo.install(name = "Apple Dictionary", bytes = appleDict, id = "a")
        repo.setDefault("a")

        val store = repo.setEnabled("a", false)
        assertNull(store.defaultEntry)
        assertFalse(repo.entry("a")!!.enabled)
        assertFalse(repo.entry("a")!!.isDefault)
    }

    @Test
    fun `exactly one default survives setDefault`() {
        val repo = repository()
        repo.install(name = "Apple Dictionary", bytes = appleDict, id = "a")
        repo.install(name = "Banana Dictionary", bytes = bananaDict, id = "b")

        repo.setDefault("b")
        assertTrue(repo.entry("b")!!.isDefault)
        assertFalse(repo.entry("a")!!.isDefault)
        assertEquals(1, repo.dicts().count { it.isDefault })
        // A default is always enabled.
        assertTrue(repo.entry("b")!!.enabled)
    }

    @Test
    fun `move and reorder change the stored order`() {
        val repo = repository()
        repo.install(name = "A", bytes = appleDict, id = "a")
        repo.install(name = "B", bytes = bananaDict, id = "b")
        repo.install(name = "C", bytes = appleDict, id = "c")
        assertEquals(listOf("a", "b", "c"), repo.dicts().map { it.id })

        repo.move("c", -1)
        assertEquals(listOf("a", "c", "b"), repo.dicts().map { it.id })
        // Out-of-range moves are clamped, not an error.
        repo.move("a", -5)
        assertEquals(listOf("a", "c", "b"), repo.dicts().map { it.id })

        repo.reorder(listOf("b", "a"))
        assertEquals(listOf("b", "a", "c"), repo.dicts().map { it.id })
        // `order` is always contiguous so the lookup order is unambiguous.
        assertEquals(listOf(0, 1, 2), repo.dicts().map { it.order })
    }

    @Test
    fun `delete removes the files and promotes a new default`() {
        val repo = repository()
        repo.install(name = "A", bytes = appleDict, id = "a")
        repo.install(name = "B", bytes = bananaDict, id = "b")
        repo.setDefault("a")
        val file = repo.dictFile("a")!!

        assertTrue(repo.delete("a"))
        assertFalse(file.exists())
        assertNull(repo.entry("a"))
        assertEquals("b", repo.defaultEntry()!!.id)
        assertFalse(repo.delete("does-not-exist"))
    }

    /**
     * BACKUP COMPATIBILITY: the desktop writes `dictList` + `customDicts` entries with
     * only `name` / `extension` (dictUtil.ts:111-136). Those must still load.
     */
    @Test
    fun `decodes a desktop shaped index`() {
        val repo = repository()
        repo.dictFolder.mkdirs()
        File(repo.dictFolder, "dict-index.json").writeText(
            """
            {
              "dictList": ["1700000000000", "1700000000001"],
              "customDicts": {
                "1700000000000": {"id":"1700000000000","name":"Oxford","extension":"mdx"},
                "1700000000001": {"id":"1700000000001","name":"朗道英汉","extension":"mdx","isDefault":true}
              }
            }
            """.trimIndent()
        )
        val dicts = repo.dicts()
        assertEquals(2, dicts.size)
        assertEquals(listOf("Oxford", "朗道英汉"), dicts.map { it.name })
        assertTrue("unknown fields default to enabled", dicts.all { it.enabled })
        assertEquals("1700000000001", repo.defaultEntry()!!.id)
    }

    @Test
    fun `suggest offers candidates when a word is missing`() {
        val repo = repository()
        repo.install(name = "Apple Dictionary", bytes = appleDict, id = "a")
        val result = repo.lookup("aple")
        assertFalse(result.found)
        assertTrue(repo.suggest("aple").contains("apple"))
    }

    @Test
    fun `a corrupt dictionary file is skipped instead of crashing the lookup`() {
        val repo = repository()
        repo.install(name = "Broken", bytes = "definitely not an mdx".toByteArray(), id = "broken")
        repo.install(name = "Good", bytes = bananaDict, id = "good")
        assertNull(repo.openParser("broken"))
        val result = repo.lookup("banana")
        assertTrue(result.found)
        assertEquals("good", result.entry!!.id)
    }
}
