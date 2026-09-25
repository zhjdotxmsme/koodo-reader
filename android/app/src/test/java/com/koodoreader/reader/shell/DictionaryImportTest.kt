package com.koodoreader.reader.shell

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [DictionaryImport] — the rules that decide what a dictionary import
 * actually installs (P6 dictionary entry point).
 *
 * Regression context: `.mdd` files are resource companions, not dictionaries.
 * Registering one as a dictionary put a phantom row in the manager, and a
 * companion that had not been staged yet made the `.mdx` import silently lose its
 * images — both were possible before this helper existed.
 */
class DictionaryImportTest {

    @Test
    fun `sanitize keeps ordinary names intact`() {
        assertEquals("汉语大词典.mdx", DictionaryImport.sanitizeName("汉语大词典.mdx"))
        assertEquals("dict-v2.mdx", DictionaryImport.sanitizeName("  dict-v2.mdx  "))
    }

    @Test
    fun `sanitize strips path and shell characters`() {
        // Every separator becomes `_`, so a path can never escape the staging
        // directory (the name is cosmetic, the directory is what matters).
        assertEquals("_etc_passwd.mdx", DictionaryImport.sanitizeName("/etc/passwd.mdx"))
        assertEquals("a_b_c.mdx", DictionaryImport.sanitizeName("a:b*c.mdx"))
        assertEquals("dictionary", DictionaryImport.sanitizeName("   "))
        assertEquals("dictionary", DictionaryImport.sanitizeName("\\/:*?\"<>|"))
    }

    @Test
    fun `mdd is a companion, mdx is not`() {
        assertTrue(DictionaryImport.isResourceCompanion("dict.mdd"))
        assertTrue(DictionaryImport.isResourceCompanion("DICT.MDD"))
        assertFalse(DictionaryImport.isResourceCompanion("dict.mdx"))
        assertFalse(DictionaryImport.isResourceCompanion("dict"))
        // A file named `mdd` without an extension is a (weird) dictionary name.
        assertFalse(DictionaryImport.isResourceCompanion("mdd"))
    }

    @Test
    fun `companions are installed first so the mdx sees them`() {
        val ordered = DictionaryImport.installOrder(listOf("a.mdx", "a.mdd", "b.mdx", "b.mdd"))
        assertEquals(listOf("a.mdd", "b.mdd", "a.mdx", "b.mdx"), ordered)
    }

    @Test
    fun `staging files land in the cache subdirectory`() {
        val cache = Files.createTempDirectory("koodo-cache").toFile()
        val staged = DictionaryImport.stagingFile(cache, "../escape.mdx")
        assertEquals(DictionaryImport.STAGING_DIR, staged.parentFile.name)
        assertEquals(cache, staged.parentFile.parentFile)
        // The traversal attempt must not escape the staging directory.
        assertEquals(".._escape.mdx", staged.name)
    }

    @Test
    fun `staging creates its directory`() {
        val cache = Files.createTempDirectory("koodo-cache").toFile()
        val staged: File = DictionaryImport.stagingFile(cache, "dict.mdx")
        assertTrue(staged.parentFile.isDirectory)
    }
}
