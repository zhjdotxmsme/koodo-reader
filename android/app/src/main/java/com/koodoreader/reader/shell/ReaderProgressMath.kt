package com.koodoreader.reader.shell

/**
 * Progress arithmetic shared by EPUB/PDF/comic readers and the /stats screen.
 *
 * Desktop stores a CFI plus a 0..1 fraction (`book.percentage`). Native
 * [ReaderProgressPrefs] keeps the same pair; this object is the pure half.
 */
object ReaderProgressMath {

    /** 0-based [pageIndex0] of a [pageCount]-page book → 0..1. */
    fun fraction(pageIndex0: Int, pageCount: Int): Double {
        if (pageCount <= 0) return 0.0
        val page = (pageIndex0 + 1).coerceIn(1, pageCount)
        return page.toDouble() / pageCount
    }

    fun pdfCfi(page1: Int): String = "pdf:$page1"

    fun pageFromPdfCfi(cfi: String): Int? {
        val raw = cfi.trim()
        if (!raw.startsWith("pdf:", ignoreCase = true)) return null
        return raw.substring(4).toIntOrNull()?.takeIf { it > 0 }
    }
}
