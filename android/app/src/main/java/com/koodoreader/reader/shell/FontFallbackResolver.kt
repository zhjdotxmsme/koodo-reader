package com.koodoreader.reader.shell

import android.graphics.Typeface

/**
 * P2 hook (engine/layout, card t-muew0ia6 item ⑥): per-run typeface
 * resolution for mixed-script text (e.g. a Latin custom font + CJK system
 * fallback). Android's renderer already falls back to system fonts for
 * glyphs the primary typeface lacks, so the default implementation is
 * trivial; a real chain (per-glyph ranges, consistent measurement) lands
 * with the layout engine. Resolving measurements through this interface —
 * never through raw Paint.typeface alone — keeps pagination stable once the
 * chain grows.
 */
interface FontFallbackResolver {
    fun resolve(text: CharSequence, primary: Typeface?): Typeface
}

/** Trivial default: primary or system default (Android handles glyph fallback). */
class SystemFallbackResolver : FontFallbackResolver {
    override fun resolve(text: CharSequence, primary: Typeface?): Typeface =
        primary ?: Typeface.DEFAULT
}
