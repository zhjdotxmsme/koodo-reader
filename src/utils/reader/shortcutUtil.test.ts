/**
 * In-memory reader-config storage for the mocked ConfigService.
 * Name must start with "mock" (jest-hoist out-of-scope allowlist).
 */
const mockStore: Record<string, string> = {};

jest.mock("../../assets/lib/kookit-extra-browser.min", () => ({
  ConfigService: {
    getReaderConfig: (key: string) => mockStore[key] || "",
    setReaderConfig: (key: string, value: string) => {
      mockStore[key] = value;
    },
  },
}));

import {
  DEFAULT_SHORTCUT_CONFIG,
  ensureVolumeBindings,
  matchShortcut,
  getShortcutConfig,
} from "./shortcutUtil";

describe("volume-key paging (A5)", () => {
  beforeEach(() => {
    mockStore["shortcutConfig"] = "";
  });

  it("includes VolumeUp/VolumeDown in the default page bindings", () => {
    const up = DEFAULT_SHORTCUT_CONFIG.nextPage.some((b) => b.keyCode === 175);
    const down =
      DEFAULT_SHORTCUT_CONFIG.prevPage.some((b) => b.keyCode === 174);
    expect(up).toBe(true);
    expect(down).toBe(true);
  });

  it("matches a bare VolumeUp key event to nextPage", () => {
    const event = {
      keyCode: 175,
      ctrlKey: false,
      altKey: false,
      shiftKey: false,
    };
    expect(matchShortcut(event, DEFAULT_SHORTCUT_CONFIG.nextPage)).toBe(true);
    expect(matchShortcut(event, DEFAULT_SHORTCUT_CONFIG.prevPage)).toBe(false);
  });

  it("adds volume bindings to legacy stored configs without duplicates", () => {
    // Legacy config: saved lists replace defaults, volume keys are missing
    mockStore["shortcutConfig"] = JSON.stringify({
      nextPage: [{ keyCode: 32 }],
      prevPage: [{ keyCode: 33 }],
    });
    const config = getShortcutConfig();
    expect(config.nextPage.some((b) => b.keyCode === 175)).toBe(true);
    expect(config.nextPage.some((b) => b.keyCode === 32)).toBe(true);
    expect(config.prevPage.some((b) => b.keyCode === 174)).toBe(true);
    expect(config.prevPage.some((b) => b.keyCode === 33)).toBe(true);
    // idempotent: running the ensure again adds nothing
    ensureVolumeBindings(config);
    expect(
      config.nextPage.filter((b) => b.keyCode === 175).length
    ).toBe(1);
    expect(config.prevPage.filter((b) => b.keyCode === 174).length).toBe(
      1
    );
  });

  it("respects a user mapping where the volume key is taken", () => {
    // Legacy config: user mapped VolumeUp to openToc -> ensure must skip it
    const legacy = {
      nextPage: [{ keyCode: 32 }],
      prevPage: [{ keyCode: 33 }],
      openToc: [{ keyCode: 175 }],
    };
    const merged = ensureVolumeBindings(legacy as any);
    expect(
      merged.nextPage.some((b: any) => b.keyCode === 175)
    ).toBe(false);
    expect(merged.prevPage.some((b: any) => b.keyCode === 174)).toBe(true);
  });

  it("does not share a physical key with another action, modifier or not", () => {
    // The user mapped "Ctrl+VolumeUp" to openToc. The plain conflict check
    // compares the modifiers too, so it would report "no conflict" and add a
    // bare VolumeUp binding — two actions on one physical key. A physical key
    // belongs to one action only.
    const legacy = {
      nextPage: [{ keyCode: 32 }],
      prevPage: [{ keyCode: 33 }],
      openToc: [{ keyCode: 175, ctrl: true }],
    };
    const merged = ensureVolumeBindings(legacy as any);
    expect(
      merged.nextPage.some((b: any) => b.keyCode === 175)
    ).toBe(false);
  });
});
