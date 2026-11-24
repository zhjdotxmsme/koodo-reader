import {
  buildBookMetaLines,
  formatLastReadTime,
  matchBookFilters,
  type BookLike,
} from "./bookShelf";

const book: BookLike = {
  key: "k1",
  name: "The Great Novel",
  author: "Jane Doe",
  description:
    "A long story about adventures across the sea. Second line. Third line.",
  lastReadTime: new Date("2026-11-01T08:00:00Z").getTime(),
};

describe("matchBookFilters (C3/B8)", () => {
  it("matches title, author and intro by default/title", () => {
    expect(matchBookFilters(book, { text: "great" })).toBe(true);
    expect(matchBookFilters(book, { text: "jane" })).toBe(true);
    expect(
      matchBookFilters(book, { text: "adventures", fields: ["intro"] })
    ).toBe(true);
    // intro-only search must NOT match the title
    expect(
      matchBookFilters(book, { text: "great novel", fields: ["intro"] })
    ).toBe(false);
  });

  it("combines text and status filters (AND)", () => {
    const reading: BookLike = { ...book, percentage: 42 };
    const finished: BookLike = { ...book, percentage: 100 };
    expect(
      matchBookFilters(reading, { text: "great", status: "reading" })
    ).toBe(true);
    expect(
      matchBookFilters(finished, { text: "great", status: "reading" })
    ).toBe(false);
    expect(
      matchBookFilters(finished, { text: "great", status: "finished" })
    ).toBe(true);
    expect(matchBookFilters(book, { text: "", status: "finished" })).toBe(
      false
    );
  });

  it("filters by shelf key membership", () => {
    expect(
      matchBookFilters(book, { shelfKeys: ["k1", "k2"] })
    ).toBe(true);
    expect(matchBookFilters(book, { shelfKeys: ["other"] })).toBe(false);
  });

  it("rejects null books and empty filters", () => {
    expect(matchBookFilters(null as any, {})).toBe(false);
    expect(matchBookFilters(book, {})).toBe(true);
  });
});

describe("buildBookMetaLines (C2)", () => {
  it("shows a truncated intro when requested", () => {
    const lines = buildBookMetaLines(book, { showIntro: true });
    expect(lines).toHaveLength(1);
    expect(lines[0].length).toBeLessThanOrEqual(41);
    expect(lines[0]).toContain("…");
  });

  it("adds the last-read line when present", () => {
    const lines = buildBookMetaLines(book, {
      showIntro: false,
      showLastRead: true,
    });
    expect(lines[0]).toMatch(/^Last read /);
  });

  it("returns [] when book or options are empty", () => {
    expect(buildBookMetaLines(null as any, {})).toEqual([]);
    expect(buildBookMetaLines(book, {})).toEqual([]);
    expect(
      buildBookMetaLines({ key: "x" }, { showIntro: true, showLastRead: true })
    ).toEqual([]);
  });
});

describe("formatLastReadTime (C2)", () => {
  it("formats valid timestamps and drops invalid ones", () => {
    const ts = new Date("2026-11-01T08:00:00Z").getTime();
    expect(formatLastReadTime(ts)).toMatch(/2026/);
    expect(formatLastReadTime(undefined)).toBe("");
    expect(formatLastReadTime("not-a-date")).toBe("");
    expect(formatLastReadTime(-5)).toBe("");
  });
});
