package com.koodoreader.engine.text

/**
 * One charset verdict produced by [CharsetDetector].
 *
 * @property charset a `java.nio.charset` name (`UTF-8`, `GBK`, `Big5`,
 *   `Shift_JIS`, `EUC-KR`, `UTF-16LE`, …). It is deliberately the same spelling
 *   the desktop persisted into `Book.charset`, so a library synced from the web
 *   app still resolves.
 * @property confidence 0.0 … 1.0. See [CharsetDetector] for how the score is
 *   built; the values are only meant to be comparable *within one detection
 *   call*, not across files.
 * @property bomLength length in bytes of the byte-order mark that was consumed
 *   (0 when there was none). Callers must skip these bytes before decoding when
 *   they decode manually; [TextDecoder.decode] does it for them.
 * @property reasons short, ordered evidence lines — the equivalent of the
 *   `chardet` debug trail, and what the report/test failures print.
 * @property pairs number of double-byte characters the winning candidate decoded
 *   (0 for BOM/UTF-8/ASCII verdicts). Used by [CharsetDetector]'s acceptance
 *   rule; exposed because it is the single best indicator of how much evidence
 *   the verdict rests on.
 * @property koreanEvidence 0.0…1.0 density of Korean grammatical morphemes in the
 *   text decoded as EUC-KR — the tie-breaker that separates EUC-KR from GBK (see
 *   [CharsetDetector.selectVerdict]).
 */
data class CharsetGuess(
    val charset: String,
    val confidence: Double,
    val bomLength: Int = 0,
    val reasons: List<String> = emptyList(),
    val pairs: Int = 0,
    val koreanEvidence: Double = 0.0,
) {
    /** True when the detection was decided by hard evidence (BOM / strict UTF-8). */
    val isAuthoritative: Boolean
        get() = bomLength > 0 || charset == Charsets.UTF_8 || charset == Charsets.ASCII

    override fun toString(): String =
        "$charset(${"%.3f".format(confidence)})${if (bomLength > 0) " +BOM$bomLength" else ""}"
}

/**
 * Zero-dependency replacement for the desktop's `chardet` dependency.
 *
 * The desktop pipeline is:
 * ```
 * chardet.detect(first4KiB) -> new TextDecoder(charset).decode(allBytes)
 * ```
 * (`kookit/src/renders/TxtRender.ts`, see `docs/android-native-migration.md`
 * P5). This class reproduces the *decision order* that makes chardet good on
 * ebook files, then adds the piece chardet is worst at (legacy CJK), using only
 * structural byte statistics — no statistical model tables, no bundled data, so
 * the module stays at zero runtime dependencies.
 *
 * Decision order:
 *  1. **BOM.** UTF-8 / UTF-16LE / UTF-16BE / UTF-32LE / UTF-32BE. Authoritative.
 *  2. **Strict UTF-8.** If the sample validates under [Utf8.validate] (overlong
 *     forms, surrogates and > U+10FFFF all rejected) the file *is* UTF-8: no
 *     legacy encoding can produce a long, entirely well-formed UTF-8 stream by
 *     accident. Confidence 0.99, or 0.80 when the sample is very short.
 *  3. **Pure ASCII.** Every byte < 0x80 (and no odd NUL pattern) → `US-ASCII`
 *     with high confidence — the file is byte-compatible with UTF-8 either way,
 *     so this is purely informational.
 *  4. **Legacy heuristics** for GBK / Big5 / Shift_JIS / EUC-KR: for each
 *     candidate we walk the sample as that encoding (ASCII single bytes,
 *     `0xA1..0xDF` katakana for Shift_JIS, otherwise a lead+trail pair), and
 *     score
 *       - *structure*: share of bytes consumed inside a legal lead/trail range
 *         (structural violations are punished hard — this is what separates
 *         Big5 from GBK, whose lead bytes are identical),
 *       - *semantics*: share of decoded characters that land in the encoding's
 *         own script block (Han for GBK/Big5, Han+kana for Shift_JIS, Hangul
 *         syllables for EUC-KR),
 *       - *practicality*: how many characters the JDK can actually map
 *         (unmappable pairs decode to U+FFFD).
 *     Highest score wins; below [MIN_LEGACY_CONFIDENCE] we fall back to UTF-8
 *     (mirroring chardet's `detectedCharset || "utf8"` default).
 *
 * Known limits (reported, not hidden): Chinese GBK vs Big5 on a short Han-only
 * sample is genuinely ambiguous — both encodings map most of the frequent
 * characters — so confidence is capped near 0.75 there. Single-byte Western
 * code pages (windows-1252/8859-*) are not modelled; like the desktop, those
 * fall back to UTF-8.
 */
object CharsetDetector {

    /** How much of the file to look at. Matches the desktop's 4 KiB probe. */
    const val DEFAULT_PROBE_BYTES = 4096

    /** A legacy verdict below this score is discarded in favour of UTF-8. */
    const val MIN_LEGACY_CONFIDENCE = 0.45

    /**
     * A legacy verdict needs at least this many decoded double-byte pairs.
     * Frequency statistics over a handful of characters are worthless — ten Han
     * characters decode "successfully" in every candidate.
     */
    const val MIN_LEGACY_PAIRS = 20

    /**
     * Minimum Korean morpheme density required to accept an EUC-KR verdict even
     * though GBK scored as well or better (see [selectVerdict]).
     */
    const val MIN_KOREAN_EVIDENCE = 0.15

    /** Below this many bytes we do not trust the legacy statistics at all. */
    const val MIN_SAMPLE_BYTES = 24

    /**
     * Detects the charset of [bytes], examining at most [probeBytes] from the
     * start (the desktop's `CHUNK_SIZE = 4096`).
     */
    fun detect(bytes: ByteArray, probeBytes: Int = DEFAULT_PROBE_BYTES): CharsetGuess {
        if (bytes.isEmpty()) {
            return CharsetGuess(Charsets.UTF_8, 0.0, 0, listOf("empty input -> UTF-8 default"))
        }
        val sample = if (bytes.size > probeBytes) bytes.copyOf(probeBytes) else bytes

        bomGuess(sample)?.let { return it }

        val validation = Utf8.validate(sample)
        if (validation.valid) {
            if (validation.asciiOnly) {
                return CharsetGuess(
                    Charsets.ASCII, 0.99, 0,
                    listOf("all ${sample.size} probe bytes are ASCII (<0x80)"),
                )
            }
            // Longer samples give the grammar more chances to fail, so the
            // verdict is stronger. A 40-byte non-ASCII sample stays below the
            // legacy threshold guard but is still reported as UTF-8.
            val lowControl = sample.any {
                val b = it.toInt() and 0xFF
                b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D
            }
            val confidence = when {
                lowControl -> 0.80
                sample.size >= 64 -> 0.95
                sample.size >= 32 -> 0.90
                else -> 0.85
            }
            return CharsetGuess(
                Charsets.UTF_8, confidence, 0,
                listOf(
                    "strict UTF-8 validation passed " +
                        "(${validation.codePoints} code points, ${sample.size} bytes)" +
                        if (lowControl) "; contains C0 control bytes" else "",
                ),
            )
        }

        if (sample.size < MIN_SAMPLE_BYTES) {
            return CharsetGuess(
                Charsets.UTF_8, 0.30, 0,
                listOf(
                    "sample too short (${sample.size} bytes) for statistics; " +
                        "UTF-8 validation failed at ${validation.errorOffset}: ${validation.reason}",
                ),
            )
        }

        val scored = Charsets.Legacy.values().map { score(it, sample) }.sortedByDescending { it.confidence }
        val best = selectVerdict(scored)
        // Few pairs means the language prior is noise: 3 decoded characters can
        // "look Korean" by accident. Below the bar we keep the desktop's UTF-8
        // default rather than guessing a code page.
        val bestPairs = best.pairs
        if (best.confidence < MIN_LEGACY_CONFIDENCE || bestPairs < MIN_LEGACY_PAIRS) {
            return CharsetGuess(
                Charsets.UTF_8, 0.35, 0,
                listOf(
                    "no legacy encoding cleared the bar " +
                        "(best ${best.charset}=${"%.3f".format(best.confidence)} over $bestPairs pairs, " +
                        "min ${"%.2f".format(MIN_LEGACY_CONFIDENCE)}/${MIN_LEGACY_PAIRS}); " +
                        "falling back to UTF-8 like chardet's null default",
                ) + scored.map { "candidate ${it.charset}: ${"%.3f".format(it.confidence)}" },
            )
        }

        // Penalise ambiguity when two candidates are structurally similar.
        val runnerUp = scored.firstOrNull { it !== best }
        val margin = if (runnerUp == null) 1.0 else (best.confidence - runnerUp.confidence)
        val adjusted = if (margin < 0.05) best.confidence - 0.10 else best.confidence
        return CharsetGuess(
            best.charset,
            adjusted.coerceIn(0.0, 0.99),
            0,
            best.reasons + scored.map { "candidate ${it.charset}: ${"%.3f".format(it.confidence)}" },
            best.pairs,
        )
    }

    /**
     * Picks the winning candidate from the descending-score list.
     *
     * The only special case is EUC-KR: its trail-byte range is a near-superset of
     * GBK's, so **any** GBK-encoded Chinese text also decodes to structurally
     * perfect, fully-mappable Hangul. Score alone therefore cannot separate the
     * two reliably — a Korean verdict also needs positive linguistic evidence
     * (grammatical morphemes, see [FrequencyPriors.koreanFlavour]). Without it we
     * fall through to the runner-up, which for a Chinese file is GBK.
     */
    private fun selectVerdict(scored: List<CharsetGuess>): CharsetGuess {
        val top = scored.first()
        if (top.charset != Charsets.EUC_KR) return top
        if (top.koreanEvidence >= MIN_KOREAN_EVIDENCE) return top
        return scored.drop(1).firstOrNull { it.confidence >= MIN_LEGACY_CONFIDENCE } ?: top
    }

    /** Convenience overload: detect from the first [probe] bytes of a source. */
    fun detect(source: TextSource, probe: Int = DEFAULT_PROBE_BYTES): CharsetGuess =
        detect(source.read(0, minOf(probe.toLong(), source.length).toInt()), probe)

    /** Short form: just the charset name. */
    fun detectName(bytes: ByteArray, probeBytes: Int = DEFAULT_PROBE_BYTES): String =
        detect(bytes, probeBytes).charset

    // ---------------------------------------------------------------- BOM ----

    private fun bomGuess(sample: ByteArray): CharsetGuess? {
        fun startsWith(bom: ByteArray): Boolean {
            if (sample.size < bom.size) return false
            for (i in bom.indices) if (sample[i] != bom[i]) return false
            return true
        }
        // UTF-32 first: its BOM starts with the UTF-16LE BOM.
        if (startsWith(Charsets.Bom.UTF_32_LE)) {
            return CharsetGuess(
                Charsets.UTF_32_LE, 0.99, 4,
                listOf("BOM FF FE 00 00 -> UTF-32LE"),
            )
        }
        if (startsWith(Charsets.Bom.UTF_32_BE)) {
            return CharsetGuess(
                Charsets.UTF_32_BE, 0.99, 4,
                listOf("BOM 00 00 FE FF -> UTF-32BE"),
            )
        }
        if (startsWith(Charsets.Bom.UTF_8)) {
            return CharsetGuess(
                Charsets.UTF_8, 0.99, 3,
                listOf("BOM EF BB BF -> UTF-8"),
            )
        }
        if (startsWith(Charsets.Bom.UTF_16_LE)) {
            return CharsetGuess(
                Charsets.UTF_16_LE, 0.99, 2,
                listOf("BOM FF FE -> UTF-16LE"),
            )
        }
        if (startsWith(Charsets.Bom.UTF_16_BE)) {
            return CharsetGuess(
                Charsets.UTF_16_BE, 0.99, 2,
                listOf("BOM FE FF -> UTF-16BE"),
            )
        }
        return null
    }

    // ----------------------------------------------------------- heuristics --

    /**
     * Walks [sample] as [legacy] and reports how much of it is structurally and
     * semantically consistent with that encoding.
     *
     * Three independent signals are combined (weights in the code below):
     *  - **structure** — share of bytes consumed inside a legal lead/trail pair.
     *    This is what rules Big5/Shift_JIS/EUC-KR *out* when the text is GBK,
     *    because their trail-byte ranges are much narrower;
     *  - **support** — the language-frequency signal from [FrequencyPriors],
     *    which is what rules GBK *out* when the text is Japanese/Korean:
     *    GBK maps those bytes happily, but to rare Han characters that never
     *    appear in prose, so the prior collapses;
     *  - **mapped** — share of pairs the JDK can actually map (unmappable pairs
     *    decode to U+FFFD).
     */
    fun score(legacy: Charsets.Legacy, sample: ByteArray): CharsetGuess {
        val layout = Charsets.layoutOf(legacy)
        var i = 0
        var pairs = 0          // complete double-byte characters
        var legalBytes = 0     // bytes consumed by legal single/double sequences
        var scannedBytes = 0   // bytes that carry information
        var violations = 0     // lead byte followed by an illegal trail byte
        var oddTrailingByte = false
        val decoded = StringBuilder(sample.size)
        val pairBytes = java.io.ByteArrayOutputStream(sample.size)

        while (i < sample.size) {
            val b = sample[i].toInt() and 0xFF
            if (b < 0x80) {
                // ASCII is legal in every legacy encoding, but a text file full
                // of ASCII would already have been caught by the UTF-8 pass.
                if (Charsets.isInformative(b)) scannedBytes++
                legalBytes++
                decoded.append(b.toChar())
                i++
                continue
            }
            if (Charsets.isInformative(b)) scannedBytes++

            if (legacy == Charsets.Legacy.SHIFT_JIS && b in 0xA1..0xDF) {
                // Half-width katakana: a legal single byte in Shift_JIS.
                legalBytes++
                decoded.append((0xFF61 + (b - 0xA1)).toChar())
                i++
                continue
            }
            if (!layout.isLead(b)) {
                violations++
                decoded.append(Charsets.REPLACEMENT_CHAR)
                i++
                continue
            }
            if (i + 1 >= sample.size) {
                oddTrailingByte = true
                violations++
                decoded.append(Charsets.REPLACEMENT_CHAR)
                i++
                continue
            }
            val b1 = sample[i + 1].toInt() and 0xFF
            if (Charsets.isInformative(b1)) scannedBytes++
            if (layout.isTrail(b1)) {
                pairs++
                legalBytes += 2
                decoded.append(PAIR_PLACEHOLDER)
                pairBytes.write(b)
                pairBytes.write(b1)
                i += 2
            } else {
                violations++
                decoded.append(Charsets.REPLACEMENT_CHAR)
                i++
            }
        }

        // Decode all pairs in ONE JDK call, then splice the characters back into
        // the same positions (the placeholder keeps indexes aligned).
        val pairText = decodePairs(legacy.charsetName, pairBytes.toByteArray())
        var pairIdx = 0
        val text = StringBuilder(decoded.length)
        for (c in decoded) {
            if (c == PAIR_PLACEHOLDER) {
                text.append(if (pairIdx < pairText.length) pairText[pairIdx++] else Charsets.REPLACEMENT_CHAR)
            } else {
                text.append(c)
            }
        }
        val fullText = text.toString()

        val total = sample.size
        val structure = if (total == 0) 0.0 else legalBytes.toDouble() / total
        val charCount = fullText.codePoints().count().coerceAtLeast(1L)
        val unmappable = fullText.count { it == Charsets.REPLACEMENT_CHAR }
        val mappedRatio = (1.0 - unmappable.toDouble() / charCount).coerceIn(0.0, 1.0)
        val support = frequencySupport(legacy, fullText)
        val pairDensity = if (total == 0) 0.0 else (pairs * 2).toDouble() / total

        var confidence = 0.20 * structure +
            0.35 * support +
            0.35 * mappedRatio +
            0.10 * pairDensity.coerceAtMost(1.0)

        val reasons = mutableListOf<String>()
        reasons += "structure=${"%.3f".format(structure)} (${legalBytes}/${total} legal bytes, $pairs pairs)"
        reasons += "language=${"%.3f".format(support)} (frequency prior)"
        reasons += "mapped=${"%.3f".format(mappedRatio)} ($unmappable unmappable of $charCount chars)"
        if (violations > 0) reasons += "$violations structural violation(s)"
        if (oddTrailingByte) reasons += "sample ends mid-pair"

        // Korean is the one candidate whose *language* can be confirmed by
        // grammar rather than by byte ranges: EUC-KR trails are a near-superset
        // of GBK trails, so Chinese bytes decode to plausible-looking Hangul.
        // Genuine Korean text, however, is full of grammatical morphemes
        // (입니다 / 습니다 / 하다 …) that a byte-coincidence decoding almost never
        // produces in bulk.
        var koreanEvidence = 0.0
        if (legacy == Charsets.Legacy.EUC_KR) {
            koreanEvidence = FrequencyPriors.koreanFlavour(fullText)
            if (koreanEvidence > 0) {
                confidence += 0.10 * koreanEvidence
                reasons += "korean-morpheme=${"%.3f".format(koreanEvidence)}"
            }
        }

        // A sample with no double-byte character at all cannot be a legacy CJK
        // file: it would have been ASCII. Cap it below the acceptance bar.
        if (pairs == 0) {
            confidence = minOf(confidence, 0.30)
            reasons += "no double-byte pair in sample -> capped"
        }
        // Structural violations are the strongest disqualifier (Big5 text is
        // full of 0x80..0xA0 trail bytes, which only GBK accepts, and vice
        // versa).
        if (scannedBytes > 0 && violations > scannedBytes / 10) {
            confidence *= 0.55
            reasons += "many violations -> halved"
        }

        return CharsetGuess(
            legacy.charsetName,
            confidence.coerceIn(0.0, 0.99),
            0,
            reasons,
            pairs,
            koreanEvidence,
        )
    }

    /** See [FrequencyPriors]: mean per-character support, in `0.0..2.0`. */
    private fun frequencySupport(legacy: Charsets.Legacy, text: String): Double {
        var sum = 0.0
        var considered = 0
        for (c in text) {
            // Punctuation, digits, whitespace and the replacement char appear in
            // every candidate's decoding, so they are evidence for nobody.
            if (FrequencyPriors.isNeutral(c)) continue
            considered++
            sum += FrequencyPriors.support(legacy, c)
        }
        if (considered == 0) return 0.0
        // Raw support is 0..2; map it onto 0..1 so it can be weighted directly.
        return (sum / considered / 2.0).coerceIn(0.0, 1.0)
    }

    /** Decodes a packed `[b0, b1, b0, b1, …]` array into one char per pair. */
    private fun decodePairs(charsetName: String, packed: ByteArray): String {
        if (packed.isEmpty()) return ""
        val charset = runCatching { java.nio.charset.Charset.forName(charsetName) }.getOrNull()
            ?: return ""
        val decoder = charset.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
        return runCatching {
            decoder.decode(java.nio.ByteBuffer.wrap(packed)).toString()
        }.getOrElse { "" }
    }

    // Script blocks. Surrogates are not needed: all CJK blocks are BMP.
    fun isHan(c: Char): Boolean =
        c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\uF900'..'\uFAFF'

    fun isKana(c: Char): Boolean =
        c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF' || c in '\uFF66'..'\uFF9D'

    fun isHangul(c: Char): Boolean =
        c in '\uAC00'..'\uD7A3' || c in '\u1100'..'\u11FF' || c in '\u3130'..'\u318F'

    fun isFullWidthAscii(c: Char): Boolean =
        c in '\uFF01'..'\uFF5E' || c in '\u3000'..'\u303F'

    /** Placeholder char standing in for one double-byte character. */
    private val PAIR_PLACEHOLDER = '\uF8FF'
}
