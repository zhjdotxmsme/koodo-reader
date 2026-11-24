import { viewMode } from "./viewMode";

describe("viewMode (C1)", () => {
  it("provides card, list, cover and compact layouts", () => {
    const modes = viewMode.map((item) => item.mode);
    expect(modes).toContain("card");
    expect(modes).toContain("list");
    expect(modes).toContain("cover");
    expect(modes).toContain("compact");
  });

  it("has unique modes and names", () => {
    const modes = viewMode.map((item) => item.mode);
    expect(new Set(modes).size).toBe(modes.length);
    const names = viewMode.map((item) => item.name);
    expect(new Set(names).size).toBe(names.length);
  });
});
