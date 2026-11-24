/**
 * Extra reader CSS builder (pure, engine-agnostic).
 *
 * Generates CSS for reader options that live outside the kookit StyleKeys
 * (image inversion, image opacity, single-image centering, typography
 * enhance). The result is injected by styleUtil into the book iframe, so the
 * builder itself must stay free of DOM/engine access and be unit-testable.
 */

export interface ExtraReaderCssOptions {
  /** "yes" | "no" — invert images on dark/night themes */
  isInvertImage?: string;
  /** "30".."100" — image opacity percentage */
  imageOpacity?: string;
  /** "yes" | "no" — center single images (display:block + auto margins) */
  isCenterImage?: string;
  /** "yes" | "no" — typography enhance: tables, lists, ruby */
  isTypoEnhance?: string;
}

export const IMAGE_OPACITY_MIN = 0.3;
export const IMAGE_OPACITY_MAX = 1.0;

/**
 * Clamp an image opacity value to the supported range [0.3, 1.0].
 *
 * The unit is ALWAYS a percentage (30-100 in the UI), so "1" means 1% and
 * must not be read as "100%". A number in 0..1 is interpreted as a
 * percentage too (1 -> 1%), which keeps the slider's integer values
 * unambiguous. Non-numeric input falls back to 1.0 (fully opaque) rather
 * than breaking rendering.
 */
export function sanitizeImageOpacity(value: number | string): number {
  let n: number;
  if (typeof value === "number") {
    n = value;
  } else {
    n = parseFloat(value);
  }
  if (Number.isNaN(n)) {
    return IMAGE_OPACITY_MAX;
  }
  // The value is a percentage: 0..100 maps onto 0..1.
  const pct = n / 100;
  return Math.min(Math.max(pct, IMAGE_OPACITY_MIN), IMAGE_OPACITY_MAX);
}

const TYPO_ENHANCE_CSS = [
  "table{border-collapse:collapse;max-width:100%;}",
  "th,td{border:1px solid rgba(128,128,128,0.5);padding:4px 8px;}",
  "ol,ul{padding-left:1.5em;line-height:1.8;}",
  "li>ul,li>ol{margin:0.25em 0;}",
  "ruby,rt,rp{ruby-align:center;}",
  "rt{font-size:0.6em;}",
  "code,pre{line-height:1.6;}",
].join("");

/**
 * Build the CSS string for the given reader options. Returns "" when no
 * option is enabled so callers can skip injection entirely.
 */
export function buildExtraReaderCss(opts: ExtraReaderCssOptions): string {
  const rules: string[] = [];
  const isYes = (v?: string) => v === "yes";

  if (isYes(opts.isInvertImage)) {
    rules.push(
      "body img{-webkit-filter:invert(1) hue-rotate(180deg);filter:invert(1) hue-rotate(180deg);}"
    );
  }

  const opacity = sanitizeImageOpacity(opts.imageOpacity || "");
  if (opacity < IMAGE_OPACITY_MAX) {
    rules.push(`body img{opacity:${Math.round(opacity * 100) / 100};}`);
  } else if (
    typeof opts.imageOpacity === "string" &&
    opts.imageOpacity.trim() !== "" &&
    Number.isFinite(parseFloat(opts.imageOpacity))
  ) {
    // Explicitly set to 100%: emit anyway so previous inline overrides are
    // neutralized when the user slides back to full opacity. A value that is
    // not a number at all emits nothing — there is no override to undo.
    rules.push("body img{opacity:1;}");
  }

  if (isYes(opts.isCenterImage)) {
    rules.push(
      "body img{display:block;margin-left:auto;margin-right:auto;max-width:100%;}"
    );
  }

  if (isYes(opts.isTypoEnhance)) {
    rules.push(TYPO_ENHANCE_CSS);
  }

  return rules.join("");
}
