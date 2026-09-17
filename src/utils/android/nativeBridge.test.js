/**
 * Tests for nativeBridge.js (Android bridge protocol single source of truth).
 * The Kotlin mirror (NativeEventDispatcher.kt) must keep event names aligned
 * with the constants asserted here.
 */
import {
  EVENTS,
  KNOWN_EVENTS,
  MENU_ACTIONS,
  buildMenuLabels,
  createHostApi,
  getIsMobile,
  isExternalHref,
  isNativeMobile,
  isSelectTextEvent,
  pageTurnHook,
  validateSelectTextPayload,
} from "./nativeBridge";

describe("nativeBridge events", () => {
  it("exposes the full 22-event vocabulary (20 literals + left/right)", () => {
    expect(Object.keys(EVENTS)).toHaveLength(22);
    expect(EVENTS.SELECT_TEXT).toBe("select-text");
    expect(EVENTS.LINK_CLICKED).toBe("link-clicked");
    expect(EVENTS.VIEW_IMAGE).toBe("view-image");
    expect(EVENTS.LEFT).toBe("left");
    expect(EVENTS.RIGHT).toBe("right");
  });

  it("marks exactly the events it knows a disposition for", () => {
    expect(KNOWN_EVENTS.size).toBe(22);
    expect(KNOWN_EVENTS.has("select-text-after-touch")).toBe(true);
    expect(KNOWN_EVENTS.has("pinch-zoom")).toBe(true);
    expect(KNOWN_EVENTS.has("no-such-event")).toBe(false);
  });

  it("maps page-turn events following engine semantics (right=next, left=prev)", () => {
    expect(pageTurnHook("right")).toBe("nextPage");
    expect(pageTurnHook("left")).toBe("prevPage");
    expect(pageTurnHook("scroll-bottom")).toBe("nextPage");
    expect(pageTurnHook("scroll-top")).toBe("prevPage");
    expect(pageTurnHook("swipe")).toBe("nextPage"); // directionless → forward
    expect(pageTurnHook("select-text")).toBeNull();
    expect(pageTurnHook("no-such-event")).toBeNull();
  });

  it("identifies the selection-menu events", () => {
    expect(isSelectTextEvent("select-text")).toBe(true);
    expect(isSelectTextEvent("select-text-after-touch")).toBe(true);
    expect(isSelectTextEvent("selection-change")).toBe(false);
  });
});

describe("nativeBridge platform detection", () => {
  it("is not native mobile without any bridge object", () => {
    expect(isNativeMobile({})).toBe(false);
    expect(isNativeMobile(undefined)).toBe(false);
    expect(getIsMobile({})).toBe("no");
  });

  it("detects the host via AndroidBridge", () => {
    expect(isNativeMobile({ AndroidBridge: {} })).toBe(true);
    expect(getIsMobile({ AndroidBridge: {} })).toBe("yes");
  });

  it("detects the host via ReactNativeWebView", () => {
    expect(isNativeMobile({ ReactNativeWebView: { postMessage: () => {} } })).toBe(true);
    expect(getIsMobile({ ReactNativeWebView: {} })).toBe("yes");
  });
});

describe("nativeBridge payload validation", () => {
  it("accepts a select-text payload with selectedText (object or JSON string)", () => {
    const a = validateSelectTextPayload({ selectedText: "hello", position: { x: 1 } });
    expect(a).toEqual({ ok: true, text: "hello", position: { x: 1 } });
    const b = validateSelectTextPayload('{"selectedText":"  hi  "}');
    expect(b).toEqual({ ok: true, text: "hi", position: null });
  });

  it("rejects garbage payloads", () => {
    expect(validateSelectTextPayload("not json")).toEqual({ ok: false, reason: "invalid-json" });
    expect(validateSelectTextPayload(42)).toEqual({ ok: false, reason: "not-an-object" });
    expect(validateSelectTextPayload(null)).toEqual({ ok: false, reason: "not-an-object" });
  });

  it("classifies external hrefs only", () => {
    expect(isExternalHref("https://example.com")).toBe(true);
    expect(isExternalHref("http://example.com/a")).toBe(true);
    expect(isExternalHref("mailto:x@y.z")).toBe(true);
    expect(isExternalHref("file:///local")).toBe(false);
    expect(isExternalHref("")).toBe(false);
    expect(isExternalHref(undefined)).toBe(false);
  });
});

describe("nativeBridge labels + host api", () => {
  it("builds i18n menu labels, falling back to the key on missing/empty", () => {
    const labels = buildMenuLabels((k) => (k === "Copy" ? "复制" : k));
    expect(labels).toEqual({
      copy: "复制",
      highlight: "Highlight",
      note: "Note",
      share: "Share",
      search: "Search",
    });
    // no translator → keys
    expect(buildMenuLabels(null).copy).toBe("Copy");
  });

  it("lists the five menu actions in display order", () => {
    expect(MENU_ACTIONS).toEqual(["copy", "highlight", "note", "share", "search"]);
  });

  it("wraps host api calls, swallowing throws and absent fns", () => {
    const api = createHostApi({
      nextPage: () => "nexted",
      // prevPage absent
      openSelectionMenu: () => {
        throw new Error("boom");
      },
    });
    expect(api.nextPage()).toBe("nexted");
    expect(api.prevPage()).toBeNull();
    expect(api.openSelectionMenu()).toBeNull();
    // entirely absent api
    const bare = createHostApi({});
    expect([bare.prevPage(), bare.nextPage(), bare.openSelectionMenu()]).toEqual([
      null,
      null,
      null,
    ]);
  });
});
