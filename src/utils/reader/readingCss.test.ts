import {
  buildExtraReaderCss,
  sanitizeImageOpacity,
  IMAGE_OPACITY_MIN,
  IMAGE_OPACITY_MAX,
} from "./readingCss";

describe("sanitizeImageOpacity (A2)", () => {
  it("clamps below-range values to the minimum", () => {
    expect(sanitizeImageOpacity(-5)).toBe(IMAGE_OPACITY_MIN);
    expect(sanitizeImageOpacity(0.1)).toBe(IMAGE_OPACITY_MIN);
    expect(sanitizeImageOpacity("10")).toBe(IMAGE_OPACITY_MIN); // 10%
  });

  it("clamps above-range values to the maximum", () => {
    expect(sanitizeImageOpacity(200)).toBe(IMAGE_OPACITY_MAX);
    expect(sanitizeImageOpacity(150)).toBe(IMAGE_OPACITY_MAX); // 150% -> 100%
  });

  it("reads the value as a percentage", () => {
    // The unit is ALWAYS a percentage: the slider offers 30..100 and a
    // value of 1 must mean 1%, not "100%". The old dual-scale reading
    // (values <= 1 as fractions) made "1" ambiguous.
    expect(sanitizeImageOpacity("80")).toBe(0.8);
    expect(sanitizeImageOpacity(50)).toBe(0.5);
    expect(sanitizeImageOpacity(100)).toBe(1);
  });

  it("clamps below the floor and above the ceiling", () => {
    expect(sanitizeImageOpacity(5)).toBe(IMAGE_OPACITY_MIN); // 5% -> 30%
    expect(sanitizeImageOpacity(2)).toBe(IMAGE_OPACITY_MIN); // 2% -> 30%
    expect(sanitizeImageOpacity(500)).toBe(IMAGE_OPACITY_MAX); // -> 100%
  });

  it("falls back to fully opaque for non-numeric input", () => {
    expect(sanitizeImageOpacity("abc")).toBe(IMAGE_OPACITY_MAX);
    expect(sanitizeImageOpacity("")).toBe(IMAGE_OPACITY_MAX);
  });
});

describe("buildExtraReaderCss (A1/A2/A3/A4)", () => {
  it("returns an empty string when nothing is enabled", () => {
    expect(buildExtraReaderCss({})).toBe("");
    expect(
      buildExtraReaderCss({
        isInvertImage: "no",
        isCenterImage: "no",
        isTypoEnhance: "no",
        imageOpacity: "100",
      })
    ).not.toContain("filter:invert");
    expect(
      buildExtraReaderCss({
        isInvertImage: "no",
        isCenterImage: "no",
        isTypoEnhance: "no",
        imageOpacity: "100",
      })
    ).not.toContain("display:block");
  });

  it("enables image inversion only when the option is on", () => {
    const on = buildExtraReaderCss({ isInvertImage: "yes" });
    expect(on).toContain("filter:invert(1)");
    const off = buildExtraReaderCss({ isInvertImage: "no" });
    expect(off).not.toContain("filter:invert");
  });

  it("renders a clamped opacity rule", () => {
    const css = buildExtraReaderCss({ imageOpacity: "70" });
    expect(css).toContain("opacity:0.7");
    const low = buildExtraReaderCss({ imageOpacity: "1" });
    // "1" is ONE PERCENT, not 100% — the unit is always a percentage, so a
    // value of 1 must not be read as "fully opaque".
    expect(low).toContain("opacity:0.3");
    expect(low).not.toContain("opacity:1;");
  });

  it("clamps the opacity into the supported 30%..100% range", () => {
    // Below the floor -> 30%
    expect(buildExtraReaderCss({ imageOpacity: "5" })).toContain("opacity:0.3");
    // Above the ceiling -> 100%
    expect(buildExtraReaderCss({ imageOpacity: "500" })).toContain("opacity:1");
    // A non-numeric value degrades to fully opaque (no rule at all)
    expect(buildExtraReaderCss({ imageOpacity: "abc" })).not.toContain(
      "opacity:"
    );
  });

  it("centers single images when enabled", () => {
    const css = buildExtraReaderCss({ isCenterImage: "yes" });
    expect(css).toContain("margin-left:auto");
    expect(css).toContain("max-width:100%");
  });

  it("adds typography enhance rules for tables and lists", () => {
    const css = buildExtraReaderCss({ isTypoEnhance: "yes" });
    expect(css).toContain("border-collapse:collapse");
    expect(css).toContain("ol,ul{");
    const off = buildExtraReaderCss({ isTypoEnhance: "no" });
    expect(off).not.toContain("border-collapse");
  });
});
