package com.koodoreader.reader.epubhost

/**
 * Which reflow path NativeEpubScreen takes.
 *
 * Default is the visible foliate-js WebView. `-Preader=canvas` (see
 * [com.koodoreader.reader.BuildConfig.READER_ENGINE]) uses
 * [com.koodoreader.reader.reader.NativeTextReaderScreen] + [ChapterLoader]
 * (legacy [ReaderSessionFactory] Canvas remains for non–chapter-loader rollback builds).
 */
object ReaderEnginePolicy {

    const val FOLIATE = "foliate"
    const val CANVAS = "canvas"

    fun useFoliate(format: String?, engine: String = FOLIATE): Boolean =
        engine != CANVAS && (format == null || FoliateFormats.usesHost(format))

    /** Canvas path: [ChapterLoader] + [com.koodoreader.reader.reader.NativeTextReaderScreen]. */
    fun useChapterLoaderCanvas(format: String?, engine: String = FOLIATE): Boolean {
        if (useFoliate(format, engine)) return false
        if (engine != CANVAS) return false
        val ext = format?.trim()?.lowercase() ?: return false
        return ext in ReaderSessionFactory.formats
    }
}
