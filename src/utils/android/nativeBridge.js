/**
 * Android native bridge protocol — single source of truth.
 *
 * The reading engine (`kookit`) emits events via
 * `window.ReactNativeWebView.postMessage(json)`; the Kotlin host parses the
 * SAME json (`android/app/src/main/java/com/koodoreader/reader/
 * NativeEventDispatcher.kt`). Keep the two in sync — every name below appears
 * in the Kotlin file with a cross-reference comment.
 *
 * Pure CJS, zero imports: loadable directly by Jest (react-scripts roots=src)
 * and by the local Node harness. No React, no i18n, no DOM access in here —
 * `t`-style translators are injected by the caller.
 *
 * Decoded engine semantics (kookit.min.js):
 *   - "right" → next page, "left" → prev page (LTR reading order, verified in
 *     engine source: `if("right"===M)return void l.next(); if("left"===M)return void l.prev()`).
 *   - In "sliding" mode the engine turns the page itself and does NOT emit
 *     these events; the events arrive only in modes where the shell must act.
 *   - "swipe" is directionless (large horizontal gesture).
 */

"use strict";

/** All event names the engine can emit (19 literals + 2 dynamic values). */
const EVENTS = {
  BOOK_INITED: "book-inited",
  CACHE: "cache",
  CHAPTER_PAGES: "chapter-pages",
  CONTENT_LOADED: "content-loaded",
  ERROR: "error",
  FINISH_DOWNLOAD: "finish-download",
  GET_OCR_RESULT: "get-ocr-result",
  LEFT: "left",
  LINK_CLICKED: "link-clicked",
  METADATA: "metadata",
  PAGE_CHANGED: "page-changed",
  PINCH_ZOOM: "pinch-zoom",
  RIGHT: "right",
  SCROLL_BOTTOM: "scroll-bottom",
  SCROLL_TEXT: "scroll-text",
  SCROLL_TOP: "scroll-top",
  SELECT_TEXT: "select-text",
  SELECT_TEXT_AFTER_TOUCH: "select-text-after-touch",
  SELECTION_CHANGE: "selection-change",
  SWIPE: "swipe",
  USER_AGENT: "user-agent",
  VIEW_IMAGE: "view-image",
};

/** Events with a known disposition in NativeEventDispatcher (mirrors Kotlin KNOWN_EVENTS). */
const KNOWN_EVENTS = new Set([
  EVENTS.BOOK_INITED,
  EVENTS.CACHE,
  EVENTS.CHAPTER_PAGES,
  EVENTS.CONTENT_LOADED,
  EVENTS.ERROR,
  EVENTS.FINISH_DOWNLOAD,
  EVENTS.GET_OCR_RESULT,
  EVENTS.LEFT,
  EVENTS.LINK_CLICKED,
  EVENTS.METADATA,
  EVENTS.PAGE_CHANGED,
  EVENTS.PINCH_ZOOM,
  EVENTS.RIGHT,
  EVENTS.SCROLL_BOTTOM,
  EVENTS.SCROLL_TEXT,
  EVENTS.SCROLL_TOP,
  EVENTS.SELECT_TEXT,
  EVENTS.SELECT_TEXT_AFTER_TOUCH,
  EVENTS.SELECTION_CHANGE,
  EVENTS.SWIPE,
  EVENTS.USER_AGENT,
  EVENTS.VIEW_IMAGE,
]);

/**
 * Page-turn events → `__koodoNative` hook name.
 * null = not a page-turn event (shell must not turn pages for it).
 */
const PAGE_TURN_HOOKS = {
  [EVENTS.RIGHT]: "nextPage",
  [EVENTS.LEFT]: "prevPage",
  [EVENTS.SWIPE]: "nextPage", // directionless gesture → default forward
  [EVENTS.SCROLL_BOTTOM]: "nextPage",
  [EVENTS.SCROLL_TOP]: "prevPage",
};

/** Events that open the native selection menu. */
const SELECT_TEXT_EVENTS = new Set([
  EVENTS.SELECT_TEXT,
  EVENTS.SELECT_TEXT_AFTER_TOUCH,
]);

/** Native menu action ids (mirrors Kotlin menu item order). */
const MENU_ACTIONS = ["copy", "highlight", "note", "share", "search"];

/** i18n key per menu action (all exist in en.json; "Share" was added). */
const MENU_LABEL_KEYS = {
  copy: "Copy",
  highlight: "Highlight",
  note: "Note",
  share: "Share",
  search: "Search",
};

/**
 * True inside the Android host (any bridge surface present).
 * `win` defaults to the real `window` when running in a browser/webview.
 */
function isNativeMobile(win) {
  const w = win === undefined ? (typeof window !== "undefined" ? window : undefined) : win;
  return !!(w && (w.ReactNativeWebView || w.AndroidBridge));
}

/** Engine-facing mobile flag value ("yes" | "no"). */
function getIsMobile(win) {
  return isNativeMobile(win) ? "yes" : "no";
}

/** `__koodoNative` hook for a page-turn event, or null. */
function pageTurnHook(eventName) {
  return PAGE_TURN_HOOKS[eventName] || null;
}

/** @returns {boolean} true if the event should open the native selection menu. */
function isSelectTextEvent(eventName) {
  return SELECT_TEXT_EVENTS.has(eventName);
}

/**
 * Validate a `select-text` payload. Accepts a parsed object or a JSON string.
 * A payload is sufficient for the native menu if it carries non-empty
 * `selectedText` (for copy/share/search); `position`/`range` are optional
 * (the menu falls back to screen-centre anchoring).
 */
function validateSelectTextPayload(payload) {
  let obj = payload;
  if (typeof obj === "string") {
    try {
      obj = JSON.parse(obj);
    } catch (e) {
      return { ok: false, reason: "invalid-json" };
    }
  }
  if (!obj || typeof obj !== "object") {
    return { ok: false, reason: "not-an-object" };
  }
  const text = typeof obj.selectedText === "string" ? obj.selectedText : "";
  return { ok: true, text: text.trim(), position: obj.position || null };
}

/**
 * True if a `link-clicked` href should be opened in the system browser.
 */
function isExternalHref(href) {
  return (
    typeof href === "string" &&
    (href.startsWith("http://") ||
      href.startsWith("https://") ||
      href.startsWith("mailto:"))
  );
}

/**
 * Build the label object pushed to native via `AndroidBridge.setMenuLabels`.
 * @param {(key: string) => string} t i18n translator (falls back to the key).
 */
function buildMenuLabels(t) {
  const tr = typeof t === "function" ? t : (k) => k;
  const out = {};
  for (const action of MENU_ACTIONS) {
    const key = MENU_LABEL_KEYS[action];
    const value = tr(key);
    out[action] = value === key || !value ? key : value;
  }
  return out;
}

/**
 * Wrap host callbacks in the `__koodoNative` contract.
 * Every function swallows errors (the engine must never see a thrown bridge
 * call) and returns null on failure/absence.
 *
 * @param {{prevPage?: Function, nextPage?: Function, openSelectionMenu?: Function}} api
 */
function createHostApi(api) {
  const guard = (fn) => {
    if (typeof fn !== "function") return () => null;
    return () => {
      try {
        return fn();
      } catch (e) {
        return null;
      }
    };
  };
  return {
    prevPage: guard(api && api.prevPage),
    nextPage: guard(api && api.nextPage),
    openSelectionMenu: guard(api && api.openSelectionMenu),
  };
}

module.exports = {
  EVENTS,
  KNOWN_EVENTS,
  PAGE_TURN_HOOKS,
  SELECT_TEXT_EVENTS,
  MENU_ACTIONS,
  MENU_LABEL_KEYS,
  isNativeMobile,
  getIsMobile,
  pageTurnHook,
  isSelectTextEvent,
  validateSelectTextPayload,
  isExternalHref,
  buildMenuLabels,
  createHostApi,
};
