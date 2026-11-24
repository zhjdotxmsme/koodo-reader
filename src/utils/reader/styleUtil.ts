import { getIframeDoc } from "./docUtil";
import { ConfigService } from "../../assets/lib/kookit-extra-browser.min";
import { applyThemeColor, removeThemeColor } from "./themeUtil";
import { StyleHelper } from "../../assets/lib/kookit.min";
import FontUtil from "../file/fontUtil";
import {
  buildExtraReaderCss,
  type ExtraReaderCssOptions,
} from "./readingCss";
import {
  loadThemeFonts,
  resolveEffectiveFonts,
} from "./fontScopeUtil";

class styleUtil {
  // add default css for iframe
  static addDefaultCss(bookKey: string) {
    let doc = getIframeDoc("ANY")[0];
    if (!doc) return;
    if (!doc.head) {
      return;
    }
    //get style with id of default-style
    let styleElement = doc.getElementById("default-style");
    if (styleElement) {
      styleElement.textContent = this.getDefaultCss(bookKey);
    } else {
      let css = this.getDefaultCss(bookKey);
      let style = doc.createElement("style");
      style.id = "default-style";
      style.textContent = css;
      doc.head.appendChild(style);
    }
    // inject custom book CSS if enabled
    let customCssElement = doc.getElementById("custom-book-style");
    const isCustomBookCSS =
      ConfigService.getReaderConfig("isCustomBookCSS") === "yes";
    const customBookCSS = ConfigService.getReaderConfig("customBookCSS") || "";
    if (isCustomBookCSS && customBookCSS) {
      if (customCssElement) {
        customCssElement.textContent = customBookCSS;
      } else {
        let customStyle = doc.createElement("style");
        customStyle.id = "custom-book-style";
        customStyle.textContent = customBookCSS;
        doc.head.appendChild(customStyle);
      }
    } else if (customCssElement) {
      customCssElement.textContent = "";
    }
    // reader-extra: image inversion / opacity / centering / typography
    // enhance (rendered outside the kookit StyleKeys — see readingCss.ts)
    let extraElement = doc.getElementById("reader-extra-style");
    let extraCss = "";
    try {
      extraCss = buildExtraReaderCss(this.getExtraOptions());
    } catch (e) {
      extraCss = "";
    }
    if (extraElement) {
      extraElement.textContent = extraCss;
    } else if (extraCss) {
      let extraStyle = doc.createElement("style");
      extraStyle.id = "reader-extra-style";
      extraStyle.textContent = extraCss;
      doc.head.appendChild(extraStyle);
    }
  }

  // Read the extra reader options from the config service with safe defaults
  // (any storage/format error degrades to "no extra CSS", never breaks render)
  static getExtraOptions(): ExtraReaderCssOptions {
    const opts: ExtraReaderCssOptions = {};
    try {
      opts.isInvertImage =
        ConfigService.getReaderConfig("isInvertImage") || "no";
      opts.imageOpacity = ConfigService.getReaderConfig("imageOpacity") || "";
      opts.isCenterImage =
        ConfigService.getReaderConfig("isCenterImage") || "no";
      opts.isTypoEnhance =
        ConfigService.getReaderConfig("isTypoEnhance") || "no";
    } catch {
      return {};
    }
    return opts;
  }
  // get default css for iframe
  static getDefaultCss(bookKey: string) {
    return StyleHelper.getDefaultCss(ConfigService, bookKey);
  }

  static async applyReaderFonts(rendition: any): Promise<void> {
    if (!rendition?.displayFontUrl) return;

    let fontName = ConfigService.getReaderConfig("fontFamily");
    let subFontName = ConfigService.getReaderConfig("subFontFamily");

    // A7: font scope — "theme" applies the per-theme font pair instead of the
    // global one; any resolution error falls back to the global fonts.
    try {
      const scope = ConfigService.getReaderConfig("fontScope") || "global";
      if (scope === "theme") {
        const themeColor = ConfigService.getReaderConfig("themeColor") || "";
        const resolved = resolveEffectiveFonts(
          loadThemeFonts(),
          themeColor,
          { fontFamily: fontName, subFontFamily: subFontName },
          scope
        );
        fontName = resolved.fontFamily;
        subFontName = resolved.subFontFamily;
      }
    } catch {
      // keep the global fonts
    }

    if (fontName && FontUtil.isCustomFont(fontName)) {
      const url = await FontUtil.getFontUrl(fontName);
      if (url) await rendition.displayFontUrl(fontName, url);
    }

    if (subFontName && FontUtil.isCustomFont(subFontName)) {
      const url = await FontUtil.getFontUrl(subFontName);
      if (url) await rendition.displayFontUrl(subFontName, url);
    }
  }

  static applyTheme() {
    const themeColor = ConfigService.getReaderConfig("themeColor");
    if (themeColor && themeColor !== "default") {
      applyThemeColor(themeColor);
    } else {
      removeThemeColor();
    }
  }
}

export default styleUtil;
