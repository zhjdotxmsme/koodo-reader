/**
 * Font scope: apply a custom font globally or only to the current theme.
 *
 * "theme" scoping stores a per-theme-color font map in localStorage
 * (koodo:themeFonts). The pure resolver here decides which font pair wins;
 * styleUtil.applyReaderFonts consumes it. Corrupt storage always falls back
 * to the global font (never breaks reading).
 */

export type FontScope = "global" | "theme";

export interface FontPair {
  fontFamily?: string;
  subFontFamily?: string;
}

export type ThemeFontMap = Record<string, FontPair>;

export const THEME_FONTS_STORAGE_KEY = "koodo:themeFonts";

/**
 * Resolve the effective font pair:
 *  - scope "global" (or no theme key) -> base fonts;
 *  - scope "theme" -> the map entry for themeColor merged over base fonts;
 *    a missing/empty entry falls back to the global fonts.
 */
export function resolveEffectiveFonts(
  themeFonts: ThemeFontMap | null | undefined,
  themeColor: string | null | undefined,
  baseFonts: FontPair,
  scope: FontScope | string = "global"
): FontPair {
  const base: FontPair = {
    fontFamily: baseFonts?.fontFamily || "Built-in font",
    subFontFamily: baseFonts?.subFontFamily || "Built-in font",
  };
  if (scope !== "theme") {
    return base;
  }
  const key = (themeColor || "").trim();
  if (!key || !themeFonts || typeof themeFonts !== "object") {
    return base;
  }
  const entry = (themeFonts as ThemeFontMap)[key];
  if (!entry || typeof entry !== "object") {
    return base;
  }
  return {
    fontFamily: entry.fontFamily || base.fontFamily,
    subFontFamily: entry.subFontFamily || base.subFontFamily,
  };
}

/** Load the theme font map; corrupt/missing data returns {}. */
export function loadThemeFonts(): ThemeFontMap {
  if (typeof localStorage === "undefined") {
    return {};
  }
  try {
    const raw = localStorage.getItem(THEME_FONTS_STORAGE_KEY);
    if (!raw) {
      return {};
    }
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return {};
    }
    const result: ThemeFontMap = {};
    for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
      if (
        value &&
        typeof value === "object" &&
        typeof (value as FontPair).fontFamily === "string" &&
        typeof (value as FontPair).subFontFamily === "string"
      ) {
        result[key] = {
          fontFamily: (value as FontPair).fontFamily,
          subFontFamily: (value as FontPair).subFontFamily,
        };
      }
    }
    return result;
  } catch {
    return {};
  }
}

/** Save a font pair for the given theme color. Returns the new map. */
export function saveThemeFonts(
  themeColor: string,
  fonts: FontPair
): ThemeFontMap {
  const key = (themeColor || "").trim();
  if (!key) {
    throw new Error("themeColor is required for theme-scoped fonts");
  }
  const map = loadThemeFonts();
  map[key] = {
    fontFamily: fonts?.fontFamily || "Built-in font",
    subFontFamily: fonts?.subFontFamily || "Built-in font",
  };
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(THEME_FONTS_STORAGE_KEY, JSON.stringify(map));
    }
  } catch {
    // storage failure is non-fatal; in-session state still applies
  }
  return map;
}
