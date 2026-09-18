import { MOBILE_BREAKPOINT, isMobileViewport } from "./viewport";

describe("isMobileViewport", () => {
  it("is true below the breakpoint", () => {
    expect(isMobileViewport(390)).toBe(true);
    expect(isMobileViewport(699)).toBe(true);
  });

  it("is false at and above the breakpoint", () => {
    expect(isMobileViewport(700)).toBe(false);
    expect(isMobileViewport(950)).toBe(false);
    expect(isMobileViewport(1250)).toBe(false);
  });

  it("treats degenerate widths as mobile", () => {
    expect(isMobileViewport(0)).toBe(true);
    expect(isMobileViewport(-1)).toBe(true);
  });

  it("keeps the documented breakpoint value", () => {
    expect(MOBILE_BREAKPOINT).toBe(700);
  });
});
