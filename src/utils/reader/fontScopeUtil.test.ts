import {
  loadThemeFonts,
  resolveEffectiveFonts,
  saveThemeFonts,
  THEME_FONTS_STORAGE_KEY,
} from "./fontScopeUtil";

describe("resolveEffectiveFonts (A7)", () => {
  const base = {
    fontFamily: "NotoSerif",
    subFontFamily: "NotoSansCJK",
  };

  it("returns the global fonts for global scope", () => {
    const map = { night: { fontFamily: "X", subFontFamily: "Y" } };
    const resolved = resolveEffectiveFonts(map, "night", base, "global");
    expect(resolved).toEqual(base);
  });

  it("resolves a theme-scoped font pair when the theme is known", () => {
    const map = {
      night: { fontFamily: "ThemeFont", subFontFamily: "ThemeSub" },
    };
    const resolved = resolveEffectiveFonts(map, "night", base, "theme");
    expect(resolved.fontFamily).toBe("ThemeFont");
    expect(resolved.subFontFamily).toBe("ThemeSub");
  });

  it("falls back to base fonts when the theme has no entry", () => {
    const map = { day: { fontFamily: "Other", subFontFamily: "OtherSub" } };
    const resolved = resolveEffectiveFonts(map, "night", base, "theme");
    expect(resolved).toEqual(base);
  });

  it("fills only the missing half of a partial entry", () => {
    const map = { night: { fontFamily: "ThemeFont" } };
    const resolved = resolveEffectiveFonts(map, "night", base, "theme");
    expect(resolved.fontFamily).toBe("ThemeFont");
    expect(resolved.subFontFamily).toBe("NotoSansCJK");
  });

  it("never throws on corrupt data", () => {
    expect(
      resolveEffectiveFonts(null, "night", base, "theme")
    ).toEqual(base);
    expect(
      resolveEffectiveFonts({}, "", base, "theme")
    ).toEqual(base);
  });
});

describe("theme font storage (A7)", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("round-trips a theme font pair", () => {
    saveThemeFonts("night", {
      fontFamily: "A",
      subFontFamily: "B",
    });
    expect(loadThemeFonts()).toEqual({
      night: { fontFamily: "A", subFontFamily: "B" },
    });
  });

  it("ignores malformed stored entries", () => {
    localStorage.setItem(
      THEME_FONTS_STORAGE_KEY,
      JSON.stringify({ bad: 42, night: { fontFamily: "A" } })
    );
    expect(loadThemeFonts()).toEqual({});
  });

  it("recovers from corrupt JSON", () => {
    localStorage.setItem(THEME_FONTS_STORAGE_KEY, "{oops");
    expect(loadThemeFonts()).toEqual({});
  });

  it("rejects an empty theme color", () => {
    expect(() => saveThemeFonts("  ", { fontFamily: "A" })).toThrow();
  });
});
