package com.koodoreader.engine.annotate

/**
 * Highlight color — aligned with the desktop highlighter palette.
 *
 * Desktop source of truth: `src/utils/common.ts` exports
 * `HIGHLIGHTER_COLORS` (eight hex strings, in this exact order), and the
 * reader menu writes one of those hex strings into the `notes.color`
 * column. The default highlight is the first one (yellow).
 *
 * Two representations are needed because the two consumers differ:
 *  - [hex] — the desktop/cross-platform value (a CSS hex string). This is
 *    what goes into backup-zip records and what the web reader applies
 *    directly as a style, so it MUST stay byte-identical with upstream.
 *  - [code] — a stable 1-based integer code for the Android side. The Room
 *    `color` column is typed INTEGER (`NoteEntity.color: Long?`), so the
 *    persistence adapter binds the code rather than the hex string.
 *
 * The code is deliberately NOT the packed 0xRRGGBB value: an explicit
 * small ordinal survives a future palette reordering (as long as existing
 * entries are never renumbered) and makes an unknown imported code easy to
 * detect. [fromCode] / [fromHex] never throw — an unrecognized value
 * degrades to [YELLOW] (the desktop default) instead of corrupting data.
 *
 * @property code stable integer code persisted by Room (1..8)
 * @property hex desktop CSS color, persisted in backup-zip JSON
 */
enum class HighlightColor(val code: Int, val hex: String) {
    YELLOW(1, "#FFE54C"),
    GREEN(2, "#6FFB6B"),
    PINK(3, "#FF8AD1"),
    ORANGE(4, "#FFB05C"),
    CYAN(5, "#6CF2FF"),
    PURPLE(6, "#C9A3FF"),
    RED(7, "#FF8A8A"),
    BLUE(8, "#7AB5FF"),
    ;

    companion object {
        /** The desktop default (first entry of `HIGHLIGHTER_COLORS`). */
        val DEFAULT: HighlightColor = YELLOW

        /**
         * Resolve a Room integer code. Unknown / non-positive codes fall
         * back to [DEFAULT] so a future- or foreign-written value never
         * breaks rendering.
         */
        fun fromCode(code: Long?): HighlightColor {
            if (code == null) return DEFAULT
            return entries.firstOrNull { it.code.toLong() == code } ?: DEFAULT
        }

        /**
         * Resolve a desktop hex string. Matching is case-insensitive and
         * tolerates a missing leading `#`; anything unrecognized falls
         * back to [DEFAULT].
         */
        fun fromHex(hex: String?): HighlightColor {
            if (hex.isNullOrBlank()) return DEFAULT
            val normalized = normalize(hex)
            return entries.firstOrNull { normalize(it.hex) == normalized } ?: DEFAULT
        }

        private fun normalize(value: String): String {
            val trimmed = value.trim()
            return (if (trimmed.startsWith("#")) trimmed else "#$trimmed").uppercase()
        }
    }
}
