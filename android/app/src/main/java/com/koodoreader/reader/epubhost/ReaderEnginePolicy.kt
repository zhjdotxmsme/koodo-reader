package com.koodoreader.reader.epubhost

/**
 * Which reflow path NativeEpubScreen takes.
 *
 * Default is the visible foliate-js WebView. `-Preader=canvas` (see
 * [com.koodoreader.reader.BuildConfig.READER_ENGINE]) keeps the Canvas
 * [ReaderSessionFactory] path for tests and rollback.
 */
object ReaderEnginePolicy {

    const val FOLIATE = "foliate"
    const val CANVAS = "canvas"

    fun useFoliate(format: String?, engine: String = FOLIATE): Boolean =
        FoliateFormats.usesHost(format) && engine != CANVAS
}
