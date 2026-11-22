package com.koodoreader.core.designsystem

/**
 * The reader stylesheet injected into the WebView (Foliate) track, built here
 * so it is unit-testable on a plain JVM and so the native and WebView tracks
 * share one definition of "what a paragraph is".
 *
 * ## Design notes
 *
 * **Paragraphs are identified structurally, not by tag name.** Publishers wrap
 * body text in `<div>` as often as in `<p>`, and layout wrappers are `<div>`
 * too. Readest's rule — `div:not(:has(*:not(<inline>)))`, i.e. "a div whose
 * descendants are all inline content" — is the cheapest reliable way to tell a
 * paragraph from a wrapper. It is emitted as a **separate rule** because
 * `:has()` is unsupported on old WebViews: an invalid selector drops only that
 * rule, and `<p>` paragraphs keep working.
 *
 * **`!important` is deliberate.** The reader's indentation, paragraph gap and
 * tracking are user-visible settings; a publisher's `.content p { margin: 0 }`
 * must not silently win. `text-align` is *not* forced anywhere except `body`,
 * so author-centred titles stay centred.
 *
 * **Widows/orphans are language-dependent.** Chinese paragraphs run 15–30
 * lines; `orphans: 2` then pushes up to two lines to the next page and leaves
 * a visibly empty band at the foot of the page on almost every spread. Readest
 * explicitly relaxes CJK to `1/1` while keeping `2/2` for Latin text. We do the
 * same through `:lang()`, which matches `zh`, `zh-CN`, `zh-Hant`, …
 */
object CjkStyleSheet {

    /**
     * Everything that can appear *inside* a paragraph without making its
     * parent a layout wrapper. Mirrors Readest's `INLINE_FORMATTING_SELECTOR`.
     */
    private const val INLINE =
        "a,abbr,b,bdi,bdo,br,cite,code,data,dfn,em,i,kbd,mark,q,rp,rt,ruby,s,samp,small,span,strong,sub,sup,time,u,var"

    /** Blocks that must not receive body-text indentation or gap. */
    private const val STRUCTURAL_EXCEPTIONS =
        "hgroup p,header p,li p,ol p,ul p,td p,th p,p.leading-image"

    data class Rule(
        /** Gap between paragraphs, in CSS px. 0 = indent only (classic CJK). */
        val paragraphGapPx: Float = 10f,
        /** First-line indent in em. 2em = two full-width characters. */
        val firstLineIndentEm: Float = 2f,
        /** Tracking, in CSS px. 0 for CJK — see [CjkColumn]. */
        val letterSpacingPx: Float = 0f,
        /** Justify the column (flush right edge). */
        val justify: Boolean = true,
        /** Apply the first-line indent. */
        val indentFirstLine: Boolean = true,
    )

    fun build(rule: Rule): String {
        val gap = cssPx(rule.paragraphGapPx.coerceAtLeast(0f))
        val out = StringBuilder(512)

        out.append("body{")
        // Negative tracking is a real setting (tighten a column that reads
        // loose), so only an exact zero is skipped.
        if (rule.letterSpacingPx != 0f && rule.letterSpacingPx.isFinite()) {
            out.append("letter-spacing:").append(cssPx(rule.letterSpacingPx)).append("px;")
        }
        if (rule.justify) out.append("text-align:justify;")
        out.append("}\n")

        val indent = if (rule.indentFirstLine) {
            "text-indent:" + cssNumber(rule.firstLineIndentEm) + "em !important;"
        } else {
            ""
        }
        // The top margin is always zeroed: the reader owns the paragraph gap,
        // and a publisher's `p { margin: .5em 0 }` would otherwise stack on top
        // of it. Left/right margins are left alone so blockquotes keep their
        // visual inset.
        val gapRule = "margin-top:0 !important;margin-bottom:${gap}px !important;"

        out.append("p,blockquote,dd{")
            .append(gapRule)
            .append(indent)
            .append("widows:2;orphans:2;")
            .append("}\n")

        // Structural rule, isolated: dropped wholesale on WebViews without :has().
        out.append("div:not(:has(*:not(").append(INLINE).append("))){")
            .append(gapRule)
            .append(indent)
            .append("}\n")

        out.append(STRUCTURAL_EXCEPTIONS)
            .append("{text-indent:0 !important;margin:0 !important;")
            .append("widows:1;orphans:1;")
            .append("}\n")

        // CJK: no hyphenation, and no orphan/widow control.
        out.append(":lang(zh),:lang(ja),:lang(ko){widows:1;orphans:1;hyphens:none;}\n")

        return out.toString()
    }

    /** `10` / `10.5` — no trailing zeros, and Locale-proof. */
    private fun cssNumber(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString()
        else String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

    private fun cssPx(value: Float): String = cssNumber(value)
}
