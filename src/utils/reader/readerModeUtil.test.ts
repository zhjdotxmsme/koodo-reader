import { shouldAutoDoublePage } from "./readerModeUtil";

describe("shouldAutoDoublePage (A6)", () => {
  it("switches to double in portrait when enabled and in single mode", () => {
    expect(
      shouldAutoDoublePage({
        enabled: true,
        width: 800,
        height: 1200,
        mode: "single",
      })
    ).toBe(true);
  });

  it("does nothing in landscape", () => {
    expect(
      shouldAutoDoublePage({
        enabled: true,
        width: 1200,
        height: 800,
        mode: "single",
      })
    ).toBe(false);
  });

  it("respects an explicit double/scroll mode", () => {
    const base = { enabled: true, width: 800, height: 1200 };
    expect(shouldAutoDoublePage({ ...base, mode: "double" })).toBe(false);
    expect(shouldAutoDoublePage({ ...base, mode: "scroll" })).toBe(false);
  });

  it("is a no-op when disabled or inputs are invalid", () => {
    const base = { width: 800, height: 1200, mode: "single" };
    expect(shouldAutoDoublePage({ ...base, enabled: false })).toBe(false);
    expect(
      shouldAutoDoublePage({ ...base, enabled: true, width: 0 })
    ).toBe(false);
    expect(
      shouldAutoDoublePage({ ...base, enabled: true, width: NaN })
    ).toBe(false);
    expect(shouldAutoDoublePage(null as any)).toBe(false);
  });
});
