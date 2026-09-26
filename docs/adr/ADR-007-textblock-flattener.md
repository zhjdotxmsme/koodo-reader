# ADR-007: XHTML/HTML → TextBlock flattener (D0)

- **Status**: accepted (2026-09-24)
- **Card**: D0 — 「[P2 排版后置] XHTML/HTML→TextBlock 扁平化器」(t-mufbaotg-g1abhm), P5.5 hard prerequisite
- **Depends on**: ADR-002 (CFI compat), engine/layout BlockModel

## Context

The native engine paginates a **flattened block model** ([BlockModel]/
`TextBlock`), not a DOM — the browser-side DOM walk of the desktop engine
(kookit `layoutUtil.ts`) is impossible in Kotlin. `BlockModel.kt`'s KDoc
reserved the conversion step for "the EPUB parse layer (P2, a later card)".
Without it, every HTML-bearing format (EPUB spine items, and the P5.5
converters FB2 body / DOCX WordprocessingML / HTML / MHTML) would have to
hand-roll its own markup-to-blocks mapping — ADR-006 estimated that gap at
5–8 additional person-days of duplicated work.

## Decision

A single flattener lives in `:engine:layout`:

- `HtmlFlattener` — interface (`flatten(html, spineIndex, href, title): SpineItem`)
- `DefaultHtmlFlattener` — the stock implementation, on a tolerant tokenizer
  (`HtmlTokenizer`)

### Semantics

1. **Block elements** (`p, h1..h6, blockquote, pre, li, dt, dd, td, th,
   figcaption, div, section, article, aside, header, footer, main, caption`)
   start a new `TextBlock`; empty/whitespace-only blocks are dropped.
2. **Inline elements** (`b strong i em u s span a sup sub small code mark
   cite q abbr kbd samp var del ins …`) keep their text inside the block;
   markup is dropped (the flattened model is deliberately markup-free, the
   same approximation the desktop paginated view makes).
3. **Hidden subtrees** (`script style head title template`, comments,
   doctype, CDATA boundaries) produce nothing — except CDATA, which is
   character data by definition and degrades to text.
4. **`<br>` = block boundary** (preserves verse/poetry lines); **`<hr>` =
   separator** (flush, no output). Void elements (`img link meta …`) consume
   a CFI sibling slot but produce no block.
5. **CFI fidelity (ADR-002)**: `elementIndex` = the source element's 1-based
   index among its siblings — *every* element consumes a slot, including
   ones that produce no text, so a CFI element step computed on the source
   document addresses the same block; `elementId` is the `id` attribute;
   `TextBlock.of`'s `sourceOffsets` keep collapsed characters resolvable to
   the block's raw source text.

### Tolerance contract

The tokenizer never throws and always terminates: `<` followed by a
non-letter is literal text; comments/doctype/CDATA are bounded scans;
`script`/`style` consume raw text up to a case-insensitive close tag
(unterminated → to EOF); an unterminated tag at EOF is dropped (HTML5
behaviour); attribute values may contain quoted `>`/`<`. Entities decode in
text and attribute values only (an escaped `&lt;p&gt;` never becomes
markup); unknown/broken entities stay literal. 25+ corrupt-input tests
pin this down (`HtmlFlattenerMalformedTest`).

### Style approximation

Headings, `blockquote`, `pre`, `li`, `dd`, `figcaption`, `th/td` map to
`ParagraphStyle` presets (font scale / indent / spacing) — the same
coarse approximation the desktop stylesheet applies for a paginated view.
No CSS cascade is honoured; the P5.5 converters may pre-resolve critical
book CSS before flattening if a real book demands it (extension point:
`HtmlFlattener` is an interface).

## Alternatives considered

- **Jsoup / kotlinx-html parser** — would add the first third-party runtime
  dependency to the pure-JVM engine modules; the tokenizer we need is ~250
  lines and tolerance rules are app-specific. Rejected (dependency budget).
- **Regex line-splitting** (like the WebView island does for TXT) — loses
  CFI element steps and block semantics entirely. Rejected.
- **Full DOM + CSS cascade** — required for a rendering-perfect engine, but
  the flattened model's contract (BlockModel KDoc) explicitly scopes it out;
  same trade-off the desktop already makes for pagination.

## Consequences

- P5.5 (FB2/DOCX/HTML/MHTML) convert their intermediate markup through this
  one entry point — no per-format flattening code.
- Block-level visual fidelity is approximate (no CSS, no float/table
  layout) — consistent with the desktop paginated view; reflow-perfect
  fidelity remains the WebView island's job until P8 retires it.
- `engine/image`'s comic path and `core/archive` (R1) are unaffected.
