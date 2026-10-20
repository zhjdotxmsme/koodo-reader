/*
readest style.ts (AGPL-3.0) bundled for Android reflow
source: https://github.com/readest/readest
foliate-js MIT: see LICENSE in this folder
*/

// scripts/readest-style/stubs/constants.js
var MONOSPACE_FONTS = ["Consolas"];
var SANS_SERIF_FONTS = ["Arial"];
var SERIF_FONTS = ["Georgia"];
var FALLBACK_FONTS = ["serif"];
var CJK_SANS_SERIF_FONTS = ["Noto Sans CJK"];
var CJK_SERIF_FONTS = ["Noto Serif CJK"];

// scripts/readest-style/stubs/book-types.js
var FIXED_LAYOUT_FORMATS = ["PDF", "CBZ"];

// scripts/readest-style/stubs/themes.js
var themes = [];
function generateLightPalette() {
  return {};
}
function generateDarkPalette() {
  return {};
}

// scripts/readest-style/stubs/fonts.js
function createFontCSS() {
  return "";
}

// scripts/readest-style/stubs/dialogueHighlight.js
function isDialogueHighlightActive() {
  return false;
}

// scripts/readest-style/stubs/ambientLight.js
function readStoredAmbientIsDarkMode() {
  return false;
}

// scripts/readest-style/stubs/inlineTags.js
var INLINE_FORMATTING_SELECTOR = "em, i, strong, b, code";

// scripts/readest-style/stubs/misc.js
function getOSPlatform() {
  return "android";
}

// scripts/readest-style/stubs/scrollable.js
var LINK_HIT_AREA_CLASS = "koodo-link-hit";
var LINK_HIT_AREA_PX = 44;
var SCROLL_WRAPPER_CLASS = "koodo-scroll";
var SCROLL_WRAPPER_FIT_CLASS = "koodo-scroll-fit";

// .tools/readest-src/utils/style.ts
var buildFontFamilyLists = (serif, sansSerif, monospace, defaultCJKFont) => {
  const lastSerifFonts = ["Georgia", "Times New Roman"];
  const serifFonts = [
    serif,
    ...defaultCJKFont !== serif ? [defaultCJKFont] : [],
    ...SERIF_FONTS.filter(
      (font) => font !== serif && font !== defaultCJKFont && !lastSerifFonts.includes(font)
    ),
    ...CJK_SERIF_FONTS.filter((font) => font !== serif && font !== defaultCJKFont),
    ...lastSerifFonts.filter(
      (font) => SERIF_FONTS.includes(font) && !lastSerifFonts.includes(defaultCJKFont)
    ),
    ...FALLBACK_FONTS
  ];
  const sansSerifFonts = [
    sansSerif,
    ...defaultCJKFont !== sansSerif ? [defaultCJKFont] : [],
    ...SANS_SERIF_FONTS.filter((font) => font !== sansSerif && font !== defaultCJKFont),
    ...CJK_SANS_SERIF_FONTS.filter((font) => font !== sansSerif && font !== defaultCJKFont),
    ...FALLBACK_FONTS
  ];
  const monospaceFonts = [monospace, ...MONOSPACE_FONTS.filter((font) => font !== monospace)];
  const quote = (fonts) => fonts.map((font) => `"${font}"`).join(", ");
  return {
    serif: `${quote(serifFonts)}, serif`,
    sansSerif: `${quote(sansSerifFonts)}, sans-serif`,
    monospace: `${quote(monospaceFonts)}, monospace`
  };
};
var getBaseFontFamily = (viewSettings) => {
  const families = buildFontFamilyLists(
    viewSettings.serifFont,
    viewSettings.sansSerifFont,
    viewSettings.monospaceFont,
    viewSettings.defaultCJKFont
  );
  return viewSettings.defaultFont.toLowerCase() === "serif" ? families.serif : families.sansSerif;
};
var getFontFamilies = (viewSettings) => {
  const families = buildFontFamilyLists(
    viewSettings.serifFont,
    viewSettings.sansSerifFont,
    viewSettings.monospaceFont,
    viewSettings.defaultCJKFont
  );
  return { serif: families.serif, sans: families.sansSerif };
};
var getBaseFontSize = (viewSettings) => {
  const isMobile = ["ios", "android"].includes(getOSPlatform());
  const fontScale = isMobile ? 1.25 : 1;
  const zoomScale = (viewSettings.zoomLevel || 100) / 100;
  return viewSettings.defaultFontSize * fontScale * zoomScale;
};
var getFontStyles = (serif, sansSerif, monospace, defaultFont, defaultCJKFont, fontSize, minFontSize, fontWeight, overrideFont) => {
  const families = buildFontFamilyLists(serif, sansSerif, monospace, defaultCJKFont);
  const defaultFontFamily = defaultFont.toLowerCase() === "serif" ? "--serif" : "--sans-serif";
  const bodyFontSizeOverride = `
    p, li, div, pre, dd {
      font-size: max(1rem, var(--min-font-size, 8px)) !important;
    }
  `;
  const fontStyles = `
    html {
      --serif: ${families.serif};
      --sans-serif: ${families.sansSerif};
      --monospace: ${families.monospace};
      --font-size: ${fontSize}px;
      --min-font-size: ${minFontSize}px;
      --font-weight: ${fontWeight};
    }
    html, body {
      font-size: ${fontSize}px !important;
      font-weight: ${fontWeight};
      -webkit-text-size-adjust: none;
      text-size-adjust: none;
    }
    /* Zero specificity (:where) so ANY ebook font-family declaration \u2014 even
       one on the html element itself, at equal (0,0,1) specificity \u2014 wins the
       cascade regardless of injection order. Books like Pandoc-generated
       EPUBs declare their font on the html element; a plain html rule here
       ties on specificity and wins only by being appended later, silently
       overriding the book's embedded font with the app's default font
       (mobile especially, whose default font is sans-serif). */
    :where(html) {
      font-family: var(${defaultFontFamily}) ${overrideFont ? "!important" : ""};
    }
    /* higher specificity than ebook built-in font styles */
    html body {
      ${overrideFont ? `font-family: var(${defaultFontFamily}) !important;` : ""}
    }
    font[size="1"] {
      font-size: ${minFontSize}px;
    }
    font[size="2"] {
      font-size: ${minFontSize * 1.5}px;
    }
    font[size="3"] {
      font-size: ${fontSize}px;
    }
    font[size="4"] {
      font-size: ${fontSize * 1.2}px;
    }
    font[size="5"] {
      font-size: ${fontSize * 1.5}px;
    }
    font[size="6"] {
      font-size: ${fontSize * 2}px;
    }
    font[size="7"] {
      font-size: ${fontSize * 3}px;
    }
    /* hardcoded inline font size */
    [style*="font-size: 16px"], [style*="font-size:16px"] {
      font-size: 1rem !important;
    }
    /* The revert pass below deliberately excludes pre/code/kbd, so this is the
       only rule that applies the app's code font and it has to swap sides with
       the toggle. Off: zero specificity, so a book's own code font wins. On:
       above the book, and !important is not enough on its own because
       specificity still breaks ties between important author declarations. */
    ${overrideFont ? "html body :is(pre, code, kbd)" : ":where(pre, code, kbd)"} {
      font-family: var(--monospace) ${overrideFont ? "!important" : ""};
      font-variant-ligatures: none;
    }
    body *:not(pre, code, kbd, .code):not(pre *, code *, kbd *, .code *) {
      ${overrideFont ? "font-family: revert !important;" : ""}
    }
    ${overrideFont ? bodyFontSizeOverride : ""}
  `;
  return fontStyles;
};
var isLightCssColor = (value) => {
  const v = value.trim().toLowerCase();
  if (v === "white") return true;
  const hex = v.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i);
  if (hex) {
    const h = hex[1];
    const expand = h.length === 3 ? h.split("").map((c) => c + c).join("") : h;
    const r = parseInt(expand.slice(0, 2), 16);
    const g = parseInt(expand.slice(2, 4), 16);
    const b = parseInt(expand.slice(4, 6), 16);
    const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return luminance > 0.85;
  }
  const rgb = v.match(/^rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)$/);
  if (rgb?.[1] != null && rgb[2] != null && rgb[3] != null) {
    const r = parseInt(rgb[1], 10);
    const g = parseInt(rgb[2], 10);
    const b = parseInt(rgb[3], 10);
    const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return luminance > 0.85;
  }
  return false;
};
var getDarkModeLightBackgroundOverrides = (bg) => `
    /* Callout boxes often use inline white/light backgrounds while html/body set dark fg. */
    *[style*="background-color: #fff"], *[style*="background-color:#fff"],
    *[style*="background-color: #ffffff"], *[style*="background-color:#ffffff"],
    *[style*="background-color: white"], *[style*="background-color:white"],
    *[style*="background: #fff"], *[style*="background:#fff"],
    *[style*="background: #ffffff"], *[style*="background:#ffffff"],
    *[style*="background: white"], *[style*="background:white"],
    *[style*="background-color: rgb(255"], *[style*="background-color:rgb(255"],
    *[style*="background: rgb(255"], *[style*="background:rgb(255"] {
      background-color: ${bg} !important;
    }
    /* Force transparent, not the theme bg: the dark page fill already comes from
       the paginator container / reader grid cell, while an opaque body paints over
       the host background texture (#4446) \u2014 and foliate captures docBackground once
       per section load, so the body must stay transparent regardless of texture
       state. Book-forced light page backgrounds still get neutralized (#4392) since
       the theme-dark fill shows through. */
    body.theme-dark {
      background-color: transparent !important;
    }
`;
var getEinkSelectionStyles = () => {
  return `
    ::selection {
      color: var(--theme-bg-color);
      background: var(--theme-fg-color);
    }
    ::-moz-selection {
      color: var(--theme-bg-color);
      background: var(--theme-fg-color);
    }
  `;
};
var getDarkSelectionStyles = (primary) => `
    ::selection {
      background: color-mix(in srgb, ${primary} 40%, transparent);
    }
  `;
var getDialogueHighlightStyles = (viewSettings, themeCode) => {
  const bgBase = viewSettings.dialogueHighlight ? viewSettings.dialogueHighlightCustomColor && viewSettings.dialogueHighlightColor ? viewSettings.dialogueHighlightColor : themeCode.primary : null;
  const bgDecl = (percent) => bgBase ? `
    background-color: color-mix(in srgb, ${bgBase} ${percent}%, transparent) !important;` : "";
  const text = viewSettings.dialogueHighlightCustomTextColor && viewSettings.dialogueHighlightTextColor ? `
    color: ${viewSettings.dialogueHighlightTextColor} !important;` : "";
  return `
  /* Dialogue lines tinted with the custom color or, by default, the theme's
     primary color. !important so the tint survives "Override Book Color",
     which repaints spans/paragraphs with the theme background at the same
     importance level but lower specificity. */
  .readest-dialogue {${bgDecl(22)}${text}
    border-radius: 0.2em;
    box-decoration-break: clone;
    -webkit-box-decoration-break: clone;
  }
  .readest-dialogue-block {${bgDecl(12)}${text}
    border-radius: 0.3em;
  }${viewSettings.dialogueHighlightItalic ? `
  /* Italic runs marked like quoted dialogue; nested marks drop their own
     tint so overlapping translucent backgrounds don't stack. */
  :is(i, em) {${bgDecl(22)}${text}
    border-radius: 0.2em;
    box-decoration-break: clone;
    -webkit-box-decoration-break: clone;
  }
  :is(i, em) :is(i, em, .readest-dialogue), .readest-dialogue :is(i, em) {
    background-color: transparent !important;
  }` : ""}
`;
};
var getColorStyles = (overrideColor, invertImgColorInDark, themeCode, backgroundTextureId, isEink) => {
  const { bg, fg, primary, isDarkMode } = themeCode;
  const hasBackgroundTexture = !!backgroundTextureId && backgroundTextureId !== "none";
  const overrideBg = hasBackgroundTexture ? "transparent" : bg;
  const colorStyles = `
    html {
      --bg-texture-id: ${backgroundTextureId};
      --theme-bg-color: ${bg};
      --theme-fg-color: ${fg};
      --theme-primary-color: ${primary};
      --override-color: ${overrideColor};
      color-scheme: ${isDarkMode ? "dark" : "light"};
    }
    html, body {
      color: ${fg};
    }
    ${isEink ? getEinkSelectionStyles() : isDarkMode ? getDarkSelectionStyles(primary) : ""}
    html[has-background], body[has-background] {
      --background-set: var(--theme-bg-color);
    }
    html {
      background-color: var(--theme-bg-color, transparent);
      background: var(--background-set, none);
    }
    body {
      ${isEink ? `background-color: ${bg} !important;` : ""}
    }
    section, aside, blockquote, article, nav, header, footer, main, figure,
    div, p, font, h1, h2, h3, h4, h5, h6, li, span {
      ${overrideColor ? `background-color: ${overrideBg} !important;` : ""}
      ${overrideColor ? `color: ${fg} !important;` : ""}
      ${overrideColor ? `border-color: ${fg} !important;` : ""}
    }
    pre, span { /* inline code blocks */
      ${overrideColor ? `background-color: ${overrideBg} !important;` : ""}
    }
    a:any-link {
      ${overrideColor ? `color: ${primary} !important;` : isDarkMode ? `color: lightblue;` : ""}
    }
    /* left off hovered links, so a book's own a:hover style applies (#6859) */
    a:any-link:where(:not(:hover)) {
      text-decoration: ${isEink ? "underline" : "none"};
    }
    body.pbg {
      ${isDarkMode ? `background-color: ${bg} !important;` : ""}
    }
    /* When inverting in dark mode, invert(100%) must stay the effective filter
       (a later filter declaration would discard it), and multiply must not
       apply: multiply with a dark page background erases the image (#5250). */
    img {
      ${isDarkMode && invertImgColorInDark ? "filter: invert(100%);" : ""}
      ${isDarkMode && !invertImgColorInDark && overrideColor ? "filter: grayscale(100%) contrast(1.2) brightness(1.2);" : ""}
      ${overrideColor && !(isDarkMode && invertImgColorInDark) ? "mix-blend-mode: multiply;" : ""}
    }
    svg, img {
      ${overrideColor ? `background-color: transparent !important;` : ""};
    }
    /* horizontal rule #1649 */
    *:has(> hr.background-img):not(body) {
      background-color: ${bg};
    }
    hr.background-img {
      mix-blend-mode: multiply;
    }
    p[width][height] > img:only-child {
      mix-blend-mode: multiply;
    }
    /* inline images */
    *:has(> img.has-text-siblings):not(body) {
      ${overrideColor ? `background-color: ${bg};` : ""}
    }
    p img.has-text-siblings, span img.has-text-siblings, sup img.has-text-siblings {
      mix-blend-mode: ${isDarkMode ? "screen" : "multiply"};
    }
    table:has(> colgroup) {
      table-layout: fixed;
    }
    /* break-word, never anywhere: overflow-wrap:anywhere (and its legacy alias
       word-break:break-word) count mid-word break opportunities in min-content
       sizing, which drops every cell's minimum to a single character. Auto table
       layout then hands the whole width to the widest column and shreds a short
       label column into a stack of letters (#5681). break-word leaves intrinsic
       sizes alone and still breaks a long token that overflows its cell; a table
       too wide for its column scrolls in its wrapper instead (#4029, #4391). */
    td, th {
      overflow-wrap: break-word;
    }
    /* code */
    body.theme-dark code {
      ${isDarkMode ? `color: ${fg}cc;` : ""}
      ${isDarkMode ? `background: color-mix(in srgb, ${bg} 90%, #000);` : ""}
      ${isDarkMode ? `background-color: color-mix(in srgb, ${bg} 90%, #000);` : ""}
    }
    blockquote {
      ${isDarkMode ? `background: color-mix(in srgb, ${bg} 80%, #000);` : ""}
    }
    /* Only tint table descendants when the user has opted into color override.
       By default, leave them transparent so a plain table (and the invisible
       spacer cells some books use for vertical layout) keeps the page
       background instead of a different shade. Illegible light/zebra table
       backgrounds are handled separately by the dark-mode light-background
       rewriters (getDarkModeLightBackgroundOverrides / transformStylesheet).
       See #4419 (and #2377, which this gate originally fixed). */
    blockquote, table * {
      ${isDarkMode && overrideColor ? `background: color-mix(in srgb, ${bg} 80%, #000);` : ""}
      ${isDarkMode && overrideColor ? `background-color: color-mix(in srgb, ${bg} 80%, #000);` : ""}
    }
    /* override inline hardcoded text color */
    font[color="#000000"], font[color="#000"], font[color="black"],
    font[color="rgb(0,0,0)"], font[color="rgb(0, 0, 0)"],
    *[style*="color: rgb(0,0,0)"], *[style*="color: rgb(0, 0, 0)"],
    *[style*="color: #000"], *[style*="color: #000000"], *[style*="color: black"],
    *[style*="color:rgb(0,0,0)"], *[style*="color:rgb(0, 0, 0)"],
    *[style*="color:#000"], *[style*="color:#000000"], *[style*="color:black"] {
      color: ${fg} !important;
    }
    ${isDarkMode && !overrideColor ? getDarkModeLightBackgroundOverrides(bg) : ""}
    /* for the Gutenberg eBooks */
    #pg-header * {
      color: inherit !important;
    }
    .x-ebookmaker, .x-ebookmaker-cover, .x-ebookmaker-coverpage {
      background-color: unset !important;
    }
    /* for the Feedbooks eBooks */
    .chapterHeader, .chapterHeader * {
      border-color: unset;
      background-color: ${bg} !important;
    }
    .calibre {
      color: unset;
      background-color: unset;
    }
  `;
  return colorStyles;
};
var LINK_TOUCH_HOLD_CLASS = "link-touch-hold";
var TEXT_SELECTED_CLASS = "text-selected";
var MAX_ENLARGED_LINKS = 1e3;
var getPageLayoutStyles = (marginTop, marginRight, marginBottom, marginLeft, zoomLevel, writingMode, vertical) => `
  html {
    --margin-top: ${marginTop}px;
    --margin-right: ${marginRight}px;
    --margin-bottom: ${marginBottom}px;
    --margin-left: ${marginLeft}px;
  }
  html, body {
    ${writingMode === "auto" ? "" : `writing-mode: ${writingMode} !important;`}
    max-height: unset;
    -webkit-touch-callout: none;
    -webkit-user-select: text;
  }
  body {
    overflow: unset;
    zoom: ${zoomLevel};
    padding: unset;
    margin: unset;
  }
  /* -webkit-touch-callout on html/body does not reach descendant images in
     Android WebView, and the native image callout collides with the reader's
     touch handlers and can freeze the app */
  img {
    -webkit-touch-callout: none;
    -webkit-user-drag: none;
  }
  /* Chromium snaps a long press onto any link in reach of the finger, and a
     link long press starts no selection (#6242); set while a touch is held */
  html.${LINK_TOUCH_HOLD_CLASS} a[href] {
    pointer-events: none !important;
  }
  svg:where(:not([width])), img:where(:not([width])) {
    width: auto;
  }
  svg:where(:not([height])), img:where(:not([height])) {
    height: auto;
  }
  figure > div:has(img) {
    height: auto !important;
  }
  /* enlarge the clickable area of links, in documents opted in by applyLinkHitArea */
  html.${LINK_HIT_AREA_CLASS} a {
    position: relative !important;
  }
  html.${LINK_HIT_AREA_CLASS} a::before {
    content: '';
    position: absolute;
    inset: -${LINK_HIT_AREA_PX}px;
  }
  /* the enlarged area swallows the text around a link, so a selection dragged
     next to a footnote marker snaps away (#6566); set while text is selected */
  html.${TEXT_SELECTED_CLASS} a::before {
    pointer-events: none;
  }

  .${SCROLL_WRAPPER_CLASS} {
    display: block;
    overflow: auto;
    max-width: 100%;
    touch-action: pan-x pan-y;
    scrollbar-width: thin;
    -webkit-overflow-scrolling: touch;
  }
  .${SCROLL_WRAPPER_FIT_CLASS} {
    overflow: visible;
  }
  .${SCROLL_WRAPPER_CLASS} > table {
    display: table !important;
    max-width: 100%;
  }
  /* A scroll container is monolithic in CSS fragmentation, so a scrolling
     wrapper can't break across columns: taller than the page, it overflows the
     column and every row past the first page is clipped and unreachable
     (#6129). Clamp the wrapper \u2014 not the table, whose max-height Chromium and
     WebKit treat as a minimum \u2014 to one page so it scrolls vertically in place.
     A fit wrapper is overflow:visible and its rows paginate normally. */
  body.paginated-mode .${SCROLL_WRAPPER_CLASS}:not(.${SCROLL_WRAPPER_FIT_CLASS}) {
    max-height: calc(var(--available-height) * 1px);
  }
  pre, code {
    white-space: pre-wrap !important;
    scrollbar-width: none;
  }
  math {
    overflow: auto;
    scrollbar-width: none;
    max-height: calc(var(--available-height) * 1px);
  }
  table, math {
    max-width: calc(var(--available-width) * 1px);
  }

  .epubtype-footnote,
  aside[epub|type~="endnote"],
  aside[epub|type~="footnote"],
  aside[epub|type~="note"],
  aside[epub|type~="rearnote"] {
    display: none;
  }

  /* Now begins really dirty hacks to fix some badly designed epubs */
  .duokan-footnote-content,
  .duokan-footnote-item {
    display: none;
  }

  .duokan-image-gallery-cell {
    height: calc(var(--available-height) * 1px);
  }

  .duokan-image-gallery-cell img {
    height: 90%;
  }

  div:has(> img, > svg) {
    max-width: 100% !important;
  }

  body.paginated-mode td:has(img), body.paginated-mode td :has(img) {
    max-height: calc(var(--available-height) * 0.8 * 1px);
  }

  figure.code {
    overflow: unset !important;
  }

  /* some epubs set insane inline-block for p */
  p {
    display: block;
  }

  /* inline images without dimension */
  .ie6 img {
    width: unset;
    height: unset;
  }
  sup img {
    height: 1em;
  }
  img.has-text-siblings {
    ${vertical ? "width: 1em;" : "height: 1em;"}
  }
  /* Baseline is only Readest's default: applyImageStyle adds this class when the
     book leaves vertical-align at its initial value, so an author-set value
     (e.g. a CJK glyph-substitution image nudged with vertical-align: -0.15em)
     keeps winning. See #4866. */
  img.has-text-siblings-baseline {
    vertical-align: baseline;
  }
  :is(div) > img.has-text-siblings[style*="object-fit"] {
    display: block;
    height: auto;
    vertical-align: unset;
  }
  .duokan-footnote img:not([class]) {
    width: 0.8em;
    height: 0.8em;
  }
  div:has(img.singlepage) {
    position: relative;
    width: auto;
    height: auto;
  }
  /* some mobi */
  p[width][height] > img:only-child { 
    width: unset !important;
    height: unset !important;
  }

  /* page break */
  body.paginated-mode div[style*="page-break-after: always"],
  body.paginated-mode div[style*="page-break-after:always"],
  body.paginated-mode p[style*="page-break-after: always"],
  body.paginated-mode p[style*="page-break-after:always"] {
    margin-bottom: calc(var(--available-height) * 1px);
  }

  .br {
    display: flow-root;
  }

  .h5_mainbody {
    overflow: unset !important;
  }
`;
var CJK_LANGS = ["zh", "ja", "ko"];
var CJK_LANG_SELECTOR = CJK_LANGS.map((lang) => `:lang(${lang})`).join(", ");
var getParagraphLayoutStyles = (overrideLayout, paragraphMargin, lineSpacing, wordSpacing, letterSpacing, textIndent, justify, hyphenate, vertical) => `
  html {
    --default-text-align: ${justify ? "justify" : "start"};
    hanging-punctuation: allow-end last;
    orphans: 2;
    widows: 2;
  }
  html, body {
    text-align: var(--default-text-align);
  }
  /* Authored text-wrap: pretty (e.g. Standard Ebooks' core.css) makes engines
     that justify with it (Safari 26+, recent Chromium) overshoot inter-word
     gaps and blocks the Word Spacing setting (#5582). Only the style longhand
     is reset so an authored nowrap mode survives, and only on justified text
     containers so balanced headings keep their rag. */
  ${justify ? "html, body, p, li, blockquote, dd { text-wrap-style: auto !important; }" : ""}
  [align="left"] { text-align: left; }
  [align="right"] { text-align: right; }
  [align="center"] { text-align: center; }
  [align="justify"] { text-align: justify; }
  /* Some badly designed EPUBs put their line-height on body; drop it so the
     Line Spacing setting below, not an inherited value, spaces the text. It
     lives in this chunk so Use Book Layout lets the book's body value
     inherit (#6088). */
  body {
    line-height: unset;
  }
  :is(hgroup, header) p {
      text-align: unset;
      hyphens: unset;
  }
  /* The div clause treats a div as paragraph-like only when every descendant is
     inline formatting (INLINE_FORMATTING_TAGS). Anything injected into a book
     paragraph at runtime \u2014 the translation target, its preserved markup, the
     a11y skip link \u2014 must use a tag from that list, or the enclosing div drops
     this whole rule and reverts to the book's default line spacing, indent and
     hyphenation. */
  p, blockquote, dd, div:not(:has(*:not(${INLINE_FORMATTING_SELECTOR}))) {
    line-height: ${lineSpacing} ${overrideLayout ? "!important" : ""};
    word-spacing: ${wordSpacing}px ${overrideLayout ? "!important" : ""};
    letter-spacing: ${letterSpacing}px ${overrideLayout ? "!important" : ""};
    text-indent: ${textIndent}em ${overrideLayout ? "!important" : ""};
    -webkit-hyphens: ${hyphenate ? "auto" : "manual"} ${overrideLayout ? "!important" : ""};
    hyphens: ${hyphenate ? "auto" : "manual"} ${overrideLayout ? "!important" : ""};
    -webkit-hyphenate-limit-before: 3;
    -webkit-hyphenate-limit-after: 2;
    -webkit-hyphenate-limit-lines: 2;
    hanging-punctuation: allow-end last;
    widows: 2;
  }
  li {
    line-height: ${lineSpacing} ${overrideLayout ? "!important" : ""};
    -webkit-hyphens: ${hyphenate ? "auto" : "manual"} ${overrideLayout ? "!important" : ""};
    hyphens: ${hyphenate ? "auto" : "manual"} ${overrideLayout ? "!important" : ""};
  }
  p.aligned-center, blockquote.aligned-center,
  dd.aligned-center, div.aligned-center {
    text-align: center ${overrideLayout ? "!important" : ""};
  }
  p.aligned-left, blockquote.aligned-left,
  dd.aligned-left, div.aligned-left {
    ${justify && overrideLayout ? "text-align: justify !important;" : ""}
  }
  p.aligned-right, blockquote.aligned-right,
  dd.aligned-right, div.aligned-right {
    text-align: right ${overrideLayout ? "!important" : ""};
  }
  p.aligned-justify, blockquote.aligned-justify,
  dd.aligned-justify, div.aligned-justify {
    ${!justify && overrideLayout ? "text-align: initial !important;" : ""};
  }
  /* An image that starts a paragraph must not take the indent: sized to the
     column, it would overhang by the indent and paint a strip on the next page
     (#6198). applyImageStyle marks such paragraphs; selectors can't see text,
     so :only-child also matched a footnote icon inside text. */
  p.leading-image {
    text-indent: initial !important;
  }
  blockquote[align="center"], div[align="center"],
  p[align="center"], dd[align="center"],
  p.aligned-center, blockquote.aligned-center,
  dd.aligned-center, div.aligned-center,
  li p, ol p, ul p, td p {
    text-indent: initial !important;
  }
  /* Some converted books use an inline-only <blockquote> for every paragraph
     (#6656); under override it is spaced like a <p>, while a quote wrapping
     block content keeps its indent. */
  p${overrideLayout ? `, blockquote:not(:has(*:not(${INLINE_FORMATTING_SELECTOR})))` : ""} {
    ${vertical ? `margin-left: ${paragraphMargin}em ${overrideLayout ? "!important" : ""};` : ""}
    ${vertical ? `margin-right: ${paragraphMargin}em ${overrideLayout ? "!important" : ""};` : ""}
    ${vertical ? `margin-top: unset ${overrideLayout ? "!important" : ""};` : ""}
    ${vertical ? `margin-bottom: unset ${overrideLayout ? "!important" : ""};` : ""}
    ${!vertical ? `margin-top: ${paragraphMargin}em ${overrideLayout ? "!important" : ""};` : ""}
    ${!vertical ? `margin-bottom: ${paragraphMargin}em ${overrideLayout ? "!important" : ""};` : ""}
    ${!vertical ? `margin-left: unset ${overrideLayout ? "!important" : ""};` : ""}
    ${!vertical ? `margin-right: unset ${overrideLayout ? "!important" : ""};` : ""}
  }
  div {
    ${vertical && overrideLayout ? `margin-left: ${paragraphMargin}em !important;` : ""}
    ${vertical && overrideLayout ? `margin-right: ${paragraphMargin}em !important;` : ""}
    ${!vertical && overrideLayout ? `margin-top: ${paragraphMargin}em !important;` : ""}
    ${!vertical && overrideLayout ? `margin-bottom: ${paragraphMargin}em !important;` : ""}
  }
  p > font:only-child { 
    display: flow-root; 
  }

  ${CJK_LANG_SELECTOR} {
    widows: 1;
    orphans: 1;
  }

  /* workaround for some badly designed epubs */
  div.left *, p.left * { text-align: left; }
  div.right *, p.right * { text-align: right; }
  div.center *, p.center * { text-align: center; }
  div.justify *, p.justify * { text-align: justify; }

  img.pi {
    ${vertical ? "transform: rotate(90deg);" : ""}
    ${vertical ? "transform-origin: center;" : ""}
    ${vertical ? "height: 2em;" : ""}
    ${vertical ? `width: ${lineSpacing}em;` : ""}
    ${vertical ? `vertical-align: unset;` : ""}
  }

  .nonindent, .noindent {
    text-indent: unset !important;
  }
`;
var getCJKLineFlowStyles = () => `
  :where(${CJK_LANGS.map((lang) => `html:lang(${lang})`).join(", ")}) {
    widows: 1;
    orphans: 1;
  }
`;
var getFootnoteStyles = () => `
  .duokan-footnote-content,
  .duokan-footnote-item {
    display: block !important;
  }

  body {
    padding: 1em !important;
    overflow-wrap: break-word;
  }

  a:any-link {
    text-decoration: none;
    padding: unset;
    margin: unset;
  }

  ol {
    margin: 0;
    padding: 0;
  }

  p, li, blockquote, dd {
    margin: unset !important;
    text-indent: unset !important;
  }

  div {
    margin: unset !important;
    padding: unset !important;
  }

  dt {
    font-weight: bold;
    line-height: 1.6;
  }

  .epubtype-footnote,
  aside[epub|type~="endnote"],
  aside[epub|type~="footnote"],
  aside[epub|type~="note"],
  aside[epub|type~="rearnote"] {
    display: block;
  }
`;
var getDictStyles = (bg, fg, isDarkMode) => {
  return `
    a:empty {
      background-color: transparent;
      mix-blend-mode: multiply;
    }
    a img {
      mix-blend-mode: multiply;
    }
    div[data-dict-kind="mdict"] .entry_name {
      font-size: 1.2em;
      margin-block-start: 0.5em;
      margin-block-end: 0.5em;
    }
    div[data-dict-kind="mdict"] .juan_drop {
      ${isDarkMode ? `background-color: color-mix(in srgb, ${bg} 80%, #000);` : ""}
    }
  `;
};
var parseComputedRgb = (value) => {
  const m = value.match(/^rgba?\(([\d.]+), ([\d.]+), ([\d.]+)(?:, ([\d.]+))?\)$/);
  if (!m) return null;
  const [r, g, b] = [Number(m[1]), Number(m[2]), Number(m[3])];
  return {
    r,
    g,
    b,
    a: m[4] === void 0 ? 1 : Number(m[4]),
    brightness: 0.299 * r + 0.587 * g + 0.114 * b
  };
};
var liftDarkTextColors = (root) => {
  const parentOf = (el) => {
    const rootNode = el.getRootNode();
    return el.parentElement ?? (rootNode instanceof ShadowRoot ? rootNode.host : null);
  };
  let outerOnLight = false;
  for (let el = parentOf(root); el; el = parentOf(el)) {
    const bg = parseComputedRgb(getComputedStyle(el).backgroundColor);
    if (bg && bg.a > 0.5) {
      outerOnLight = bg.brightness >= 128;
      break;
    }
  }
  const onLightBox = /* @__PURE__ */ new Map();
  const lifts = [];
  for (const el of [root, ...root.querySelectorAll("*")]) {
    const style = getComputedStyle(el);
    const bg = parseComputedRgb(style.backgroundColor);
    const parentOnLight = el === root ? outerOnLight : !!onLightBox.get(el.parentElement);
    const onLight = bg && bg.a > 0.5 ? bg.brightness >= 128 : parentOnLight;
    onLightBox.set(el, onLight);
    const fg = parseComputedRgb(style.color);
    if (!fg || onLight || fg.brightness >= 128) continue;
    const shift = Math.round(255 - 2 * fg.brightness);
    const [r, g, b] = [fg.r, fg.g, fg.b].map((c) => Math.min(255, c + shift));
    lifts.push([el, `rgba(${r}, ${g}, ${b}, ${fg.a})`]);
  }
  for (const [el, color] of lifts) el.style.setProperty("color", color, "important");
};
var getTranslatedTextStyles = (viewSettings) => {
  const { translationFont, translationFontStyle, translationFontSize, translationColor } = viewSettings;
  return [
    translationFont && `font-family: var(--${translationFont}) !important;`,
    translationFontStyle?.includes("italic") && "font-style: italic !important;",
    translationFontStyle?.includes("bold") && "font-weight: bold !important;",
    translationFontSize && translationFontSize !== 1 && `font-size: ${translationFontSize}em !important;`,
    translationColor && `color: ${translationColor} !important;`
  ].filter(Boolean).join("\n    ");
};
var getTranslationStyles = (viewSettings) => `
  .translation-source {
  }
  .translation-target {
  }
  /* The original is wrapped rather than erased so its CFIs stay resolvable;
     cfi-skip keeps the wrapper invisible to CFI indexing. */
  .translation-source-hidden {
    display: none !important;
  }
  .translation-target.hidden {
    display: none !important;
  }
  .translation-target-block {
    display: block !important;
    ${viewSettings.showTranslateSource ? "margin: 0.5em 0 !important;" : ""}
    ${getTranslatedTextStyles(viewSettings)}
  }
  .translation-target-toc {
    display: block !important;
    overflow: hidden;
    text-overflow: ellipsis;
  }
`;
var getWarichuStyles = () => `
  /* Warichu (\u5272\u6CE8/\u5939\u6CE8) \u2014 double-line inline annotation */
  .warichu-pending {
    display: inline;
    font-size: 0.5em;
    line-height: 1.1;
  }
  .warichu-chunk {
    display: inline-block;
    line-height: 1.1;
    font-size: 0.5em;
    text-indent: 0;
    vertical-align: middle !important;
    width: 1lh !important;
    text-align: center !important;
  }
  .warichu-chunk .warichu-line {
    display: inline;
  }
  .warichu-open,
  .warichu-close {
    display: inline;
    font-size: 0.5em;
    vertical-align: middle;
    line-height: 1.1;
  }
`;
var getRubyStyles = (viewSettings) => {
  const fontSize = viewSettings.wordLensGlossFontSize || 0.5;
  const color = viewSettings.wordLensGlossColor || "";
  return `
  rt {
    user-select: none;
    -webkit-user-select: none;
  }
  rp {
    display: none !important;
  }
  ruby.wl-gloss {
    cursor: help;
  }
  ruby.wl-gloss > rt {
    font-size: ${fontSize}em;
    line-height: 1.1;
    ${color ? `color: ${color};
    opacity: 1;` : "opacity: 0.7;"}
    font-weight: normal;
    text-align: center;
  }
`;
};
var getThemeCode = () => {
  let themeMode = "auto";
  let themeColor = "default";
  let systemIsDarkMode = false;
  let ambientIsDarkMode = false;
  let customThemes = [];
  if (typeof window !== "undefined") {
    themeColor = localStorage.getItem("themeColor") || "default";
    themeMode = localStorage.getItem("themeMode") || "auto";
    systemIsDarkMode = localStorage.getItem("systemIsDarkMode") === "true";
    ambientIsDarkMode = readStoredAmbientIsDarkMode(
      localStorage.getItem("ambientIsDarkMode"),
      systemIsDarkMode
    );
    customThemes = JSON.parse(localStorage.getItem("customThemes") || "[]");
  }
  const isDarkMode = themeMode === "dark" || themeMode === "auto" && systemIsDarkMode || themeMode === "ambient" && ambientIsDarkMode;
  let currentTheme = themes.find((theme) => theme.name === themeColor);
  if (!currentTheme) {
    const customTheme = customThemes.find((theme) => theme.name === themeColor);
    if (customTheme) {
      currentTheme = {
        name: customTheme.name,
        label: customTheme.label,
        colors: {
          light: generateLightPalette(customTheme.colors.light),
          dark: generateDarkPalette(customTheme.colors.dark)
        }
      };
    }
  }
  if (!currentTheme) currentTheme = themes[0];
  const defaultPalette = isDarkMode ? currentTheme.colors.dark : currentTheme.colors.light;
  return {
    bg: defaultPalette["base-100"],
    fg: defaultPalette["base-content"],
    primary: defaultPalette.primary,
    palette: defaultPalette,
    isDarkMode
  };
};
var getStyles = (viewSettings, themeCode, customFonts = []) => {
  if (!themeCode) {
    themeCode = getThemeCode();
  }
  const pageLayoutStyles = getPageLayoutStyles(
    viewSettings.marginTopPx,
    viewSettings.marginRightPx,
    viewSettings.marginBottomPx,
    viewSettings.marginLeftPx,
    1,
    viewSettings.writingMode,
    viewSettings.vertical
  );
  const paragraphLayoutStyles = viewSettings.useBookLayout ? "" : getParagraphLayoutStyles(
    viewSettings.overrideLayout,
    viewSettings.paragraphMargin,
    viewSettings.lineHeight,
    viewSettings.wordSpacing,
    viewSettings.letterSpacing,
    viewSettings.textIndent,
    viewSettings.fullJustification,
    viewSettings.hyphenation,
    viewSettings.vertical
  );
  const fontStyles = getFontStyles(
    viewSettings.serifFont,
    viewSettings.sansSerifFont,
    viewSettings.monospaceFont,
    viewSettings.defaultFont,
    viewSettings.defaultCJKFont,
    getBaseFontSize(viewSettings),
    viewSettings.minimumFontSize,
    viewSettings.fontWeight,
    viewSettings.overrideFont
  );
  const customFontFaces = getCustomFontFaces(customFonts);
  const colorStyles = getColorStyles(
    viewSettings.overrideColor,
    viewSettings.invertImgColorInDark,
    themeCode,
    viewSettings.backgroundTextureId,
    viewSettings.isEink
  );
  const translationStyles = getTranslationStyles(viewSettings);
  const warichuStyles = getWarichuStyles();
  const rubyStyles = getRubyStyles(viewSettings);
  const cjkLineFlowStyles = getCJKLineFlowStyles();
  const dialogueStyles = isDialogueHighlightActive(viewSettings) ? getDialogueHighlightStyles(viewSettings, themeCode) : "";
  const userStylesheet = viewSettings.userStylesheet;
  const epubNamespace = `@namespace epub "http://www.idpf.org/2007/ops";`;
  return `${epubNamespace}
${customFontFaces}
${pageLayoutStyles}
${paragraphLayoutStyles}
${cjkLineFlowStyles}
${fontStyles}
${colorStyles}
${dialogueStyles}
${translationStyles}
${warichuStyles}
${rubyStyles}
${userStylesheet}`;
};
var getCustomFontFaces = (fonts) => fonts.filter((font) => !!font.blobUrl).map((font) => {
  try {
    return createFontCSS(font);
  } catch {
    return "";
  }
}).join("\n");
var applyTranslationStyle = (viewSettings) => {
  const styleId = "translation-style";
  const existingStyle = document.getElementById(styleId);
  if (existingStyle) {
    existingStyle.remove();
  }
  const styleElement = document.createElement("style");
  styleElement.id = styleId;
  styleElement.textContent = getTranslationStyles(viewSettings);
  document.head.appendChild(styleElement);
};
var transformStylesheet = (css, vw, vh, vertical, isFixedLayout = false) => {
  if (isFixedLayout) return css;
  const isMobile = ["ios", "android"].includes(getOSPlatform());
  const fontScale = isMobile ? 1.25 : 1;
  const isInlineStyle = !css.includes("{");
  const ruleRegex = /([^{]+)({[^}]+})/g;
  css = css.replace(ruleRegex, (match, selector, block) => {
    const hasTextAlignCenter = /text-align\s*:\s*center\s*[;$]/.test(block);
    const hasTextIndentZero = /text-indent\s*:\s*0(?:\.0+)?(?:px|em|rem|%)?\s*[;$]/.test(block);
    if (hasTextAlignCenter && hasTextIndentZero) {
      block = block.replace(
        /(text-indent\s*:\s*0(?:\.0+)?(?:px|em|rem|%)?)(\s*;|\s*$)/g,
        "$1 !important$2"
      );
      return selector + block;
    }
    return match;
  });
  const lineClip = vertical ? "overflow-y: clip !important;" : "overflow-x: clip !important;";
  css = css.replace(ruleRegex, (match, selector, block) => {
    const hasWhiteSpaceNowrap = /white-space\s*:\s*nowrap\s*[;$]/.test(block);
    if (hasWhiteSpaceNowrap) {
      if (!/overflow\s*:/.test(block)) {
        const sizesBox = /(?:^|[;{\s])(?:max-)?height\s*:|line-clamp\s*:/.test(block);
        const clip = sizesBox ? "overflow: clip !important;" : lineClip;
        block = block.replace(/}$/, ` ${clip} }`);
      }
      return selector + block;
    }
    return match;
  });
  if (isInlineStyle) {
    const hasPageBreakAfterAlways = /page-break-after\s*:\s*always\s*[;]?/.test(css);
    if (hasPageBreakAfterAlways && !/margin-bottom\s*:/.test(css)) {
      css = css.replace(/;?\s*$/, "") + "; margin-bottom: calc(var(--available-height) * 1px)";
    }
  } else {
    css = css.replace(ruleRegex, (match, selector, block) => {
      const hasPageBreakAfterAlways = /page-break-after\s*:\s*always\s*[;$]/.test(block);
      if (hasPageBreakAfterAlways) {
        if (!/margin-bottom\s*:/.test(block)) {
          block = block.replace(/}$/, " margin-bottom: calc(var(--available-height) * 1px); }");
        }
        return selector + block;
      }
      return match;
    });
  }
  css = css.replace(ruleRegex, (_, selector, block) => {
    if (vertical) return selector + block;
    const directions = [];
    let hasBleed = false;
    for (const dir of ["top", "bottom", "left", "right"]) {
      const bleedRegex = new RegExp(`duokan-bleed\\s*:\\s*[^;]*${dir}[^;]*;`);
      const marginRegex = new RegExp(`margin-${dir}\\s*:`);
      if (bleedRegex.test(block) && !marginRegex.test(block)) {
        hasBleed = true;
        directions.push(dir);
        block = block.replace(
          /}$/,
          ` margin-${dir}: calc(-1 * var(--page-margin-${dir})) !important; }`
        );
      }
    }
    if (hasBleed) {
      if (!/position\s*:/.test(block)) {
        block = block.replace(/}$/, " position: relative !important; }");
      }
      if (!/overflow\s*:/.test(block)) {
        block = block.replace(/}$/, " overflow: hidden !important; }");
      }
      if (!/display\s*:/.test(block)) {
        block = block.replace(/}$/, " display: flow-root !important; }");
      }
      if (directions.includes("left") && directions.includes("right")) {
        block = block.replace(/}$/, " width: calc(var(--full-width) * 1px) !important; }").replace(/}$/, " min-width: calc(var(--full-width) * 1px) !important; }").replace(/}$/, " max-width: calc(var(--full-width) * 1px) !important; }");
      }
      if (directions.includes("top") && directions.includes("bottom")) {
        block = block.replace(/}$/, " height: calc(var(--full-height) * 1px) !important; }").replace(/}$/, " min-height: calc(var(--full-height) * 1px) !important; }").replace(/}$/, " max-height: calc(var(--full-height) * 1px) !important; }");
      }
    }
    return selector + block;
  });
  const urlTokens = [];
  css = css.replace(/url\(\s*(?:"[^"]*"|'[^']*'|[^)]*)\s*\)/gi, (url) => {
    urlTokens.push(url);
    return `READEST_URL_${urlTokens.length - 1}_PLACEHOLDER`;
  });
  css = css.replace(
    /((?:^|[{;\s])background(?:-attachment)?\s*:)([^;{}]*)/gi,
    (match, prop, value) => {
      if (!/\bfixed\b/i.test(value)) return match;
      const fns = [];
      const masked = value.replace(/[\w-]*\([^)]*\)/g, (fn) => {
        fns.push(fn);
        return `READEST_FN_${fns.length - 1}_PLACEHOLDER`;
      });
      const rewritten = masked.replace(/\bfixed\b/gi, "scroll");
      return prop + rewritten.replace(/READEST_FN_(\d+)_PLACEHOLDER/g, (_, i) => fns[+i]);
    }
  );
  css = css.replace(/READEST_URL_(\d+)_PLACEHOLDER/g, (_, i) => urlTokens[+i]);
  if (!vertical) {
    css = css.replace(ruleRegex, (match, selector, block) => {
      const bgValues = [
        ...block.matchAll(/(?:^|[^-a-z])background(?:-color|-image)?\s*:\s*([^;!}]+)/gi)
      ].map((m) => m[1].trim());
      const paints = bgValues.some(
        (v) => !/^(?:none|transparent)$/i.test(v) && !/^(?:rgba|hsla)\([^)]*[,\s]0(?:\.0+)?\s*\)$/i.test(v)
      );
      if (!paints) return match;
      let left = "";
      let right = "";
      for (const decl of block.matchAll(/(?:^|[^-a-z])margin(-left|-right)?\s*:\s*([^;!}]+)/gi)) {
        const side = decl[1];
        const value = decl[2].trim();
        if (side === "-left") {
          left = value;
        } else if (side === "-right") {
          right = value;
        } else {
          if (value.includes("(")) continue;
          const parts = value.split(/\s+/);
          if (parts.length < 1 || parts.length > 4) continue;
          const [top, r = top, , l = r] = parts;
          right = r;
          left = l;
        }
      }
      const overrides = (left.startsWith("-") ? " margin-left: 0 !important;" : "") + (right.startsWith("-") ? " margin-right: 0 !important;" : "");
      if (!overrides) return match;
      return selector + block.replace(/}$/, `${overrides} }`);
    });
  }
  css = css.replace(ruleRegex, (_, selector, block) => {
    if (/\bbody\b/i.test(selector)) {
      const hasSerifFont = /font-family\s*:\s*serif\s*(?:;|\}|$)/.test(block);
      const hasSansSerifFont = /font-family\s*:\s*sans-serif\s*(?:;|\}|$)/.test(block);
      if (hasSerifFont) {
        block = block.replace(/font-family\s*:\s*serif\s*(;|\}|$)/gi, "font-family: unset$1");
      }
      if (hasSansSerifFont) {
        block = block.replace(/font-family\s*:\s*sans-serif\s*(;|\}|$)/gi, "font-family: unset$1");
      }
    }
    return selector + block;
  });
  css = css.replace(ruleRegex, (match, selector, block) => {
    const widthMatch = /(?:^|[^a-z-])width\s*:\s*(\d+(?:\.\d+)?)px/.exec(block);
    const pxWidth = widthMatch ? parseFloat(widthMatch[1] ?? "0") : 0;
    if (pxWidth > vw && !/max-width\s*:/.test(block)) {
      block = block.replace(
        /}$/,
        " width: 100%; max-width: calc(var(--available-width) * 1px); box-sizing: border-box; }"
      );
      return selector + block;
    }
    return match;
  });
  const isLandscape = vw > vh;
  const always = "(min-width: 0px)";
  const never = "(min-width: 999999px)";
  const quoted = [];
  css = css.replace(/"[^"\n]*"|'[^'\n]*'/g, (value) => {
    quoted.push(value);
    return `READEST_STR_${quoted.length - 1}_PLACEHOLDER`;
  });
  css = css.replace(
    /@media[^{]*/gi,
    (prelude) => prelude.replace(
      /\(\s*orientation\s*:\s*(landscape|portrait)\s*\)/gi,
      (_, mode) => mode.toLowerCase() === "landscape" === isLandscape ? always : never
    ).replace(
      /\(\s*(min|max)-(width|height)\s*:\s*([^)]+?)\s*\)/gi,
      (feature, bound, axis, length) => {
        const px = /^(\d*\.?\d+)(?:px)?$/.exec(length);
        if (!px) return feature;
        const viewport = axis.toLowerCase() === "width" ? vw : vh;
        const bounds = parseFloat(px[1]);
        return (bound.toLowerCase() === "min" ? viewport >= bounds : viewport <= bounds) ? always : never;
      }
    )
  );
  css = css.replace(/READEST_STR_(\d+)_PLACEHOLDER/g, (_, i) => quoted[+i]);
  css = css.replace(/font-size\s*:\s*xx-small/gi, "font-size: 0.6rem").replace(/font-size\s*:\s*x-small/gi, "font-size: 0.75rem").replace(/font-size\s*:\s*small/gi, "font-size: 0.875rem").replace(/font-size\s*:\s*medium/gi, "font-size: 1rem").replace(/font-size\s*:\s*large/gi, "font-size: 1.2rem").replace(/font-size\s*:\s*x-large/gi, "font-size: 1.5rem").replace(/font-size\s*:\s*xx-large/gi, "font-size: 2rem").replace(/font-size\s*:\s*xxx-large/gi, "font-size: 3rem").replace(/font-size\s*:\s*(\d+(?:\.\d+)?)px/gi, (_, px) => {
    const rem = parseFloat(px) / fontScale / 16;
    return `font-size: ${rem}rem`;
  }).replace(/font-size\s*:\s*(\d+(?:\.\d+)?)pt/gi, (_, pt) => {
    const rem = parseFloat(pt) / fontScale / 12;
    return `font-size: ${rem}rem`;
  }).replace(/font-size\s*:\s*(\d*\.?\d+)(px|rem|em|%)?/gi, (_, size, unit = "px") => {
    return `font-size: max(${size}${unit}, var(--min-font-size, 8px))`;
  }).replace(/backdrop-filter\s*:\s*brightness\(100%\)\s*[;]?/gi, "").replace(/(\d*\.?\d+)vw/gi, (_, d) => parseFloat(d) * vw / 100 + "px").replace(/(\d*\.?\d+)vh/gi, (_, d) => parseFloat(d) * vh / 100 + "px").replace(/([\s;])-webkit-user-select\s*:\s*none/gi, "$1-webkit-user-select: unset").replace(/([\s;])-moz-user-select\s*:\s*none/gi, "$1-moz-user-select: unset").replace(/([\s;])-ms-user-select\s*:\s*none/gi, "$1-ms-user-select: unset").replace(/([\s;])-o-user-select\s*:\s*none/gi, "$1-o-user-select: unset").replace(/([\s;])user-select\s*:\s*none/gi, "$1user-select: unset").replace(/(font-family\s*:\s*)([^;{}]*)/gi, (_match, prefix, value) => {
    const important = /\s*!\s*important\s*$/i.exec(value);
    const families = important ? value.slice(0, important.index) : value;
    const rewritten = families.split(",").map((family) => {
      const generic = /^(serif|sans-serif|monospace)$/i.exec(family.trim());
      if (!generic) return family;
      const name = generic[1].toLowerCase();
      return family.replace(generic[1], `var(--${name}, ${name})`);
    }).join(",");
    return prefix + rewritten + (important ? important[0] : "");
  }).replace(/([\s;])font-weight\s*:\s*normal/gi, "$1font-weight: var(--font-weight)").replace(/([\s;])color\s*:\s*black/gi, "$1color: var(--theme-fg-color)").replace(
    /([\s;])color\s*:\s*#(?:000f?|000000(?:ff)?)(?![0-9a-f])/gi,
    "$1color: var(--theme-fg-color)"
  ).replace(/([\s;])color\s*:\s*rgb\(0,\s*0,\s*0\)/gi, "$1color: var(--theme-fg-color)");
  const { isDarkMode, bg } = getThemeCode();
  if (isDarkMode) {
    css = css.replace(ruleRegex, (match, selector, block) => {
      const rewritten = block.replace(
        /background(-color)?\s*:\s*([^;!}]+)(\s*!important)?(?=\s*[;!}])/gi,
        (decl, _prop, value, important) => {
          const raw = value.trim().split(/\s+/)[0] ?? "";
          if (!isLightCssColor(raw)) return decl;
          return `background-color: ${bg}${important ?? ""}`;
        }
      );
      return rewritten === block ? match : selector + rewritten;
    });
  }
  return css;
};
var applyThemeModeClass = (document2, isDarkMode) => {
  document2.body.classList.remove("theme-light", "theme-dark");
  document2.body.classList.add(isDarkMode ? "theme-dark" : "theme-light");
};
var applyScrollModeClass = (document2, isScrollMode) => {
  document2.body.classList.remove("scroll-mode", "paginated-mode");
  document2.body.classList.add(isScrollMode ? "scroll-mode" : "paginated-mode");
};
var PREFIXED_ATTR_REGEX = /^([A-Za-z_][\w.-]*):([A-Za-z_][\w.-]*)$/;
var EPUB_OPS_NAMESPACE = "http://www.idpf.org/2007/ops";
var XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace";
var applyNamespacedAttributes = (document2) => {
  const lookupNamespace = (element, prefix) => {
    for (let node = element; node; node = node.parentElement) {
      const uri = node.getAttribute(`xmlns:${prefix}`);
      if (uri) return uri;
    }
    return null;
  };
  for (const element of document2.querySelectorAll("*")) {
    for (const { name, value } of Array.from(element.attributes)) {
      const [, prefix, localName] = PREFIXED_ATTR_REGEX.exec(name) ?? [];
      if (!prefix) continue;
      const uri = prefix === "xml" ? XML_NAMESPACE : lookupNamespace(element, prefix) ?? (prefix === "epub" ? EPUB_OPS_NAMESPACE : null);
      if (uri && !element.hasAttributeNS(uri, localName)) {
        element.setAttributeNS(uri, name, value);
      }
    }
  }
};
var applyEinkModeAttribute = (document2, isEink) => {
  document2.documentElement.setAttribute("data-eink", isEink ? "true" : "false");
};
var applyScrollbarStyle = (document2, hideScrollbar) => {
  const styleId = "scrollbar-hide-style";
  let styleEl = document2.getElementById(styleId);
  if (hideScrollbar) {
    if (!styleEl) {
      styleEl = document2.createElement("style");
      styleEl.id = styleId;
      document2.head.appendChild(styleEl);
    }
    styleEl.textContent = "foliate-view::part(container) { scrollbar-width: none; }";
  } else {
    if (styleEl) {
      styleEl.textContent = "foliate-view::part(container) { scrollbar-width: thin; }";
    }
  }
};
var applyImageStyle = (document2) => {
  const win = document2.defaultView ?? window;
  const plans = Array.from(document2.querySelectorAll("img")).map((img) => {
    const widthAttr = img.getAttribute("width");
    const percentWidth = widthAttr && (widthAttr.endsWith("%") || widthAttr.endsWith("vw")) ? parseFloat(widthAttr) : NaN;
    const heightAttr = img.getAttribute("height");
    const percentHeight = heightAttr && (heightAttr.endsWith("%") || heightAttr.endsWith("vh")) ? parseFloat(heightAttr) : NaN;
    const noEnlarge = img.getAttribute("zy-enlarge-src") === "none";
    const paragraph = img.closest("p");
    let leadingImageParagraph = null;
    if (paragraph) {
      const before = document2.createRange();
      before.setStart(paragraph, 0);
      before.setEndBefore(img);
      if (!before.toString().trim()) leadingImageParagraph = paragraph;
    }
    let inlineWithText = false;
    let keepBaseline = false;
    const parent = img.parentNode;
    if (parent && parent.nodeType === Node.ELEMENT_NODE) {
      const childNodes = Array.from(parent.childNodes);
      const hasTextSiblings = childNodes.some(
        (node) => node.nodeType === Node.TEXT_NODE && node.textContent?.trim()
      );
      const isInline = childNodes.every(
        (node) => node.nodeType !== Node.ELEMENT_NODE || node.tagName !== "BR"
      );
      inlineWithText = hasTextSiblings && isInline;
      if (inlineWithText) {
        const valign = win.getComputedStyle(img).verticalAlign;
        keepBaseline = valign === "" || valign === "baseline";
      }
    }
    return {
      img,
      percentWidth,
      percentHeight,
      inlineWithText,
      keepBaseline,
      noEnlarge,
      leadingImageParagraph
    };
  });
  for (const {
    img,
    percentWidth,
    percentHeight,
    inlineWithText,
    keepBaseline,
    noEnlarge,
    leadingImageParagraph
  } of plans) {
    leadingImageParagraph?.classList.add("leading-image");
    if (!isNaN(percentWidth)) {
      img.style.width = `${percentWidth / 100 * window.innerWidth}px`;
      img.removeAttribute("width");
    }
    if (!isNaN(percentHeight)) {
      img.style.height = `${percentHeight / 100 * window.innerHeight}px`;
      img.removeAttribute("height");
    }
    if (inlineWithText) {
      img.classList.add("has-text-siblings");
      if (keepBaseline) img.classList.add("has-text-siblings-baseline");
    }
    if (noEnlarge) {
      img.style.setProperty("pointer-events", "none");
    }
  }
  document2.querySelectorAll("hr").forEach((hr) => {
    const computedStyle = window.getComputedStyle(hr);
    if (computedStyle.backgroundImage && computedStyle.backgroundImage !== "none") {
      hr.classList.add("background-img");
    }
  });
};
var applyLinkHitArea = (document2) => {
  if (document2.links.length <= MAX_ENLARGED_LINKS) {
    document2.documentElement.classList.add(LINK_HIT_AREA_CLASS);
  }
};
var keepTextAlignment = (document2) => {
  const win = document2.defaultView ?? window;
  const els = document2.querySelectorAll(":is(div, p, blockquote, dd):not([data-foliate-chunk])");
  const alignClasses = new Array(els.length);
  for (let i = 0; i < els.length; i++) {
    const align = win.getComputedStyle(els[i]).textAlign;
    if (align === "center") alignClasses[i] = "aligned-center";
    else if (align === "left") alignClasses[i] = "aligned-left";
    else if (align === "right") alignClasses[i] = "aligned-right";
    else if (align === "justify") alignClasses[i] = "aligned-justify";
    else alignClasses[i] = null;
  }
  for (let i = 0; i < els.length; i++) {
    const cls = alignClasses[i];
    if (cls) els[i].classList.add(cls);
  }
};
var getOverlayerBlendMode = ({
  isDarkMode,
  isBwEink,
  isFixedLayout = false,
  invertImgColorInDark = false,
  applyThemeToPDF = false,
  format
}) => {
  if (isBwEink) return "difference";
  if (!isDarkMode) return "multiply";
  const isDarkPage = !isFixedLayout || invertImgColorInDark || format === "PDF" && applyThemeToPDF;
  return isDarkPage ? "screen" : "multiply";
};
var getPDFPageColors = (viewSettings, themeCode) => viewSettings.applyThemeToPDF ? {
  background: themeCode.bg,
  foreground: themeCode.fg,
  keepImages: !(themeCode.isDarkMode && viewSettings.invertImgColorInDark)
} : void 0;
var applyFixedlayoutStyles = (document2, viewSettings, themeCode, format) => {
  if (!themeCode) {
    themeCode = getThemeCode();
  }
  const { bg, fg, primary, isDarkMode } = themeCode;
  const appRendered = !format || FIXED_LAYOUT_FORMATS.has(format);
  const isEink = viewSettings.isEink;
  const overrideColor = viewSettings.overrideColor;
  const invertImgColorInDark = viewSettings.invertImgColorInDark;
  const contrast = viewSettings.contrast ?? 100;
  const imgFilters = [];
  const isThemedPDF = format === "PDF" && viewSettings.applyThemeToPDF;
  if (isDarkMode && invertImgColorInDark && !isThemedPDF) {
    imgFilters.push("invert(100%) hue-rotate(180deg)");
  }
  if (contrast !== 100) imgFilters.push(`contrast(${contrast}%)`);
  const imgFilter = imgFilters.length ? `filter: ${imgFilters.join(" ")};` : "";
  const darkMixBlendMode = bg === "#000000" ? "luminosity" : "overlay";
  const existingStyleId = "fixed-layout-styles";
  let style = document2.getElementById(existingStyleId);
  if (style) {
    style.remove();
  }
  style = document2.createElement("style");
  style.id = existingStyleId;
  style.textContent = `
    html {
      --theme-bg-color: ${bg};
      --theme-fg-color: ${fg};
      --theme-primary-color: ${primary};
      color-scheme: ${appRendered && isDarkMode ? "dark" : "light"};
      /* Chrome for Android's text autosizing rescales line metrics and shifts
         absolutely positioned per-letter text in fixed-layout books (#5641). */
      -webkit-text-size-adjust: none;
      text-size-adjust: none;
    }
    body {
      position: relative;
      ${appRendered ? "background-color: var(--theme-bg-color);" : ""}
    }
    ${isEink ? getEinkSelectionStyles() : ""}
    #canvas {
      display: inline-block;
      width: fit-content;
      height: fit-content;
      background-color: var(--theme-bg-color);
    }
    img, canvas {
      ${imgFilter}
      ${overrideColor && !isThemedPDF ? `mix-blend-mode: ${isDarkMode ? darkMixBlendMode : "multiply"};` : ""}
    }
    img.singlePage {
      position: relative;
    }
    /* An unsized <image> draws at its natural size, which is the page size,
       but a percentage-height svg in an auto-height block is only 150px tall
       and would clip it to a strip (#6530). */
    svg:not([viewBox]):has(> image:not([width])) {
      overflow: visible;
    }
  `;
  document2.head.appendChild(style);
};
export {
  LINK_HIT_AREA_CLASS,
  LINK_TOUCH_HOLD_CLASS,
  MAX_ENLARGED_LINKS,
  TEXT_SELECTED_CLASS,
  applyEinkModeAttribute,
  applyFixedlayoutStyles,
  applyImageStyle,
  applyLinkHitArea,
  applyNamespacedAttributes,
  applyScrollModeClass,
  applyScrollbarStyle,
  applyThemeModeClass,
  applyTranslationStyle,
  getBaseFontFamily,
  getBaseFontSize,
  getDictStyles,
  getFontFamilies,
  getFootnoteStyles,
  getOverlayerBlendMode,
  getPDFPageColors,
  getStyles,
  getThemeCode,
  keepTextAlignment,
  liftDarkTextColors,
  transformStylesheet
};
