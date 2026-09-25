package com.koodoreader.reader.shell

import java.io.File

/**
 * Pure helpers for the dictionary import flow (P6, `DictionaryRoute`).
 *
 * Kept separate from the route so `:app`'s JVM tests pin the parts that decide
 * *what* gets installed: the staged file name (which becomes the dictionary's
 * display name) and the `.mdd`-companion rule.
 */
object DictionaryImport {

    /** Extension that is a *resource companion*, never a dictionary of its own. */
    const val RESOURCE_EXTENSION = "mdd"

    /**
     * Sanitise a SAF display name into a file name we can stage in `cacheDir`.
     *
     * A dictionary called `汉语大词典 (v2).mdx` must not escape the staging
     * directory, and a name without an extension has to stay importable.
     */
    fun sanitizeName(raw: String): String {
        val safe = raw
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim()
        // A name made only of sanitised characters carries no information; fall
        // back instead of staging something called "_________".
        if (safe.isEmpty() || safe.all { it == '_' }) return "dictionary"
        return safe
    }

    /**
     * Whether a picked document is an `.mdd` companion rather than a dictionary.
     *
     * Companions must not be registered as their own entry: `DictRepository`
     * picks a `.mdd` up automatically when the `.mdx` with the same base name is
     * installed, so registering one would show a phantom row.
     */
    fun isResourceCompanion(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").equals(RESOURCE_EXTENSION, ignoreCase = true)

    /**
     * Order a picked batch so every `.mdx` sees its `.mdd` sibling already
     * staged — `installFromFile` copies the companion only when it is on disk.
     */
    fun installOrder(fileNames: List<String>): List<String> =
        fileNames.sortedBy { if (isResourceCompanion(it)) 0 else 1 }

    /** Where a picked document is staged before the repository takes it. */
    fun stagingFile(cacheDir: File, fileName: String): File =
        File(File(cacheDir, STAGING_DIR).apply { mkdirs() }, sanitizeName(fileName))

    const val STAGING_DIR = "dict-import"
}
