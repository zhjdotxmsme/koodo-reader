package com.koodoreader.core.designsystem

/**
 * Parsing for the colour strings the desktop stores in its reader config.
 *
 * The desktop writes colours in **two** forms (see `themeList.tsx`
 * `backgroundList` / `textList` and `themeUtil.ts`):
 *   - `rgba(r,g,b,a)` — with optional spaces, e.g. `rgba(197, 231, 207,1)`
 *   - `#RRGGBB` — used by the custom-theme path
 *
 * [ThemeSpec] stores whichever form the desktop wrote, so anything that renders
 * it (the Compose theme in `:core:ui`) first has to normalise it. That
 * normalisation lives here, in the pure JVM module, for two reasons:
 *   1. it is a property of the WIRE FORMAT, and this module owns the wire
 *      format; and
 *   2. it can then be tested and asserted in `selfCheck` on any JVM, instead of
 *      needing an Android instrumented test to cover a string parser.
 *
 * `:core:ui`'s `ThemeSpecBridge` is a thin adapter that calls these and wraps
 * the result in a Compose `Color`.
 */
object CssColor {

    /** Fallback used when a value cannot be parsed. Opaque black. */
    const val FALLBACK: Long = 0xFF000000

    private val RGBA = Regex(
        """^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*(?:,\s*([0-9]*\.?[0-9]+)\s*)?\)$""",
        RegexOption.IGNORE_CASE,
    )

    private val HEX6 = Regex("""^#([0-9a-fA-F]{6})$""")

    private val HEX3 = Regex("""^#([0-9a-fA-F]{3})$""")

    /**
     * Parse a desktop colour string into `0xAARRGGBB`.
     *
     * Accepted: `rgba(r,g,b,a)`, `rgb(r,g,b)`, `#RRGGBB`, `#RGB`, with
     * surrounding whitespace. Everything else (including null/blank/`undefined`)
     * returns [FALLBACK] — the caller has no better value, and the reader config
     * tolerates missing colours by falling back to the default theme.
     *
     * `a` is a 0..1 fraction, clamped. `r`/`g`/`b` outside 0..255 are clamped
     * rather than rejected: the desktop config is user-editable JSON and a
     * clamped colour is strictly more useful than a crash mid-render.
     */
    fun parse(value: String?): Long {
        val s = value?.trim().orEmpty()
        if (s.isEmpty() || s.equals("null", true) || s.equals("undefined", true)) return FALLBACK

        HEX6.matchEntire(s)?.let { m ->
            return 0xFF000000L or m.groupValues[1].toLong(16)
        }

        HEX3.matchEntire(s)?.let { m ->
            val d = m.groupValues[1]
            // Each of the three digits is doubled: #abc -> #AABBCC.
            val r = "${d[0]}${d[0]}".toInt(16)
            val g = "${d[1]}${d[1]}".toInt(16)
            val b = "${d[2]}${d[2]}".toInt(16)
            return argb(255, r, g, b)
        }

        RGBA.matchEntire(s)?.let { m ->
            val r = m.groupValues[1].toIntOrNull() ?: return FALLBACK
            val g = m.groupValues[2].toIntOrNull() ?: return FALLBACK
            val b = m.groupValues[3].toIntOrNull() ?: return FALLBACK
            val a = m.groupValues[4].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 1.0
            return argb(
                alpha = (a.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt(),
                r = r,
                g = g,
                b = b,
            )
        }

        return FALLBACK
    }

    private fun argb(alpha: Int, r: Int, g: Int, b: Int): Long =
        (alpha.coerceIn(0, 255).toLong() shl 24) or
            (r.coerceIn(0, 255).toLong() shl 16) or
            (g.coerceIn(0, 255).toLong() shl 8) or
            b.coerceIn(0, 255).toLong()

    /** Render `0xAARRGGBB` back to `#RRGGBB`, for messages and round-trips. */
    fun toHex6(argb: Long): String = Contrast.hex(argb)
}
