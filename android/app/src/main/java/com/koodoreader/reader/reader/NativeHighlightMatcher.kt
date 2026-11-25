package com.koodoreader.reader.reader

import com.koodoreader.engine.annotate.CfiAnchor

object NativeHighlightMatcher {
    fun matches(selectionCfi: String, storedCfi: String): Boolean {
        if (selectionCfi.isBlank() || storedCfi.isBlank()) return false
        if (selectionCfi == storedCfi) return true
        if (CfiAnchor.isRange(storedCfi)) return false
        return runCatching {
            CfiAnchor.isRange(selectionCfi) && CfiAnchor.startPoint(selectionCfi) == storedCfi
        }.getOrDefault(false)
    }

    fun startPointOrNull(cfi: String?): String? {
        if (cfi.isNullOrBlank()) return null
        return runCatching { CfiAnchor.startPoint(cfi) }.getOrNull()
    }
}
