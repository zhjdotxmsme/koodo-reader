import {
  addManualStat,
  aggregateByBook,
  aggregateByHour,
  buildBookPosterLines,
  buildFooterCaption,
  buildGoalPosterLines,
  capEntrySeconds,
  DEFAULT_GOALS,
  countDistinctBooks,
  estimateRemainingSeconds,
  goalProgress,
  loadGoals,
  loadManualStats,
  loadMaxPageTimeMinutes,
  ManualEntryError,
  mergeManualIntoDayMap,
  normalizeGoals,
  removeManualStat,
  saveGoals,
  saveMaxPageTimeMinutes,
  validateManualEntry,
  yearlyMonthlySeconds,
  GOALS_STORAGE_KEY,
  MAX_PAGE_TIME_KEY,
} from "./statsUtil";

describe("normalizeGoals (B1)", () => {
  it("keeps valid goals", () => {
    expect(
      normalizeGoals({ dailyWords: 3000, dailyMinutes: 30, yearlyBooks: 12 })
    ).toEqual({ dailyWords: 3000, dailyMinutes: 30, yearlyBooks: 12 });
  });

  it("coerces invalid values to 0", () => {
    expect(
      normalizeGoals({ dailyWords: -5, dailyMinutes: "abc", yearlyBooks: null })
    ).toEqual(DEFAULT_GOALS);
    expect(normalizeGoals(undefined)).toEqual(DEFAULT_GOALS);
    expect(normalizeGoals("nope")).toEqual(DEFAULT_GOALS);
  });

  it("clamps out-of-range values", () => {
    const g = normalizeGoals({
      dailyWords: 99_000_000,
      dailyMinutes: 99_999,
      yearlyBooks: 99_999,
    });
    expect(g.dailyWords).toBe(10_000_000);
    expect(g.dailyMinutes).toBe(1440);
    expect(g.yearlyBooks).toBe(10_000);
  });
});

describe("goals storage (B1)", () => {
  beforeEach(() => localStorage.clear());

  it("round-trips and normalizes on load", () => {
    saveGoals({ dailyWords: 100, dailyMinutes: 10, yearlyBooks: 5 });
    expect(loadGoals()).toEqual({ dailyWords: 100, dailyMinutes: 10, yearlyBooks: 5 });
  });

  it("recovers from corrupt storage", () => {
    localStorage.setItem(GOALS_STORAGE_KEY, "{bad json");
    expect(loadGoals()).toEqual(DEFAULT_GOALS);
  });
});

describe("manual stats entry (B3)", () => {
  beforeEach(() => localStorage.clear());

  it("adds and removes validated entries", () => {
    const list = addManualStat({
      date: "2026-11-20",
      seconds: 1800,
      words: 1500,
    });
    expect(list).toHaveLength(1);
    expect(list[0].seconds).toBe(1800);
    expect(loadManualStats()).toHaveLength(1);
    expect(removeManualStat(list[0].key)).toHaveLength(0);
  });

  it("rejects invalid dates", () => {
    expect(() => addManualStat({ date: "2026/11/20", seconds: 10 })).toThrow(
      "INVALID_DATE"
    );
    expect(() => addManualStat({ date: "", seconds: 10 })).toThrow();
  });

  it("rejects zero or over-long durations", () => {
    expect(() =>
      addManualStat({ date: "2026-11-20", seconds: 0 })
    ).toThrow("INVALID_SECONDS");
    expect(() =>
      addManualStat({ date: "2026-11-20", seconds: 999_999 })
    ).toThrow("SECONDS_TOO_LARGE");
  });

  it("rejects negative or oversized word counts", () => {
    expect(() =>
      addManualStat({ date: "2026-11-20", seconds: 10, words: -1 })
    ).toThrow("INVALID_WORDS");
    expect(() =>
      addManualStat({ date: "2026-11-20", seconds: 10, words: 99_000_000 })
    ).toThrow("WORDS_TOO_LARGE");
  });

  it("throws a machine-readable code so the caller can translate", () => {
    // The util must not hardcode user-visible text: it throws a code and the
    // page turns it into a translated message.
    try {
      addManualStat({ date: "not-a-date", seconds: 10 });
      throw new Error("expected validateManualEntry to throw");
    } catch (error) {
      expect(error).toBeInstanceOf(ManualEntryError);
      expect((error as ManualEntryError).code).toBe("INVALID_DATE");
    }
  });

  it("skips corrupt stored entries when loading", () => {
    localStorage.setItem(
      "koodo:manualStats",
      JSON.stringify([{ date: "bad", seconds: -1 }])
    );
    expect(loadManualStats()).toEqual([]);
  });
});

describe("capEntrySeconds (B4)", () => {
  const entries = [
    { seconds: 120 },
    { seconds: 3600 },
  ];

  it("caps each entry when a cap is set", () => {
    expect(capEntrySeconds(entries, 600)).toEqual([
      { seconds: 120 },
      { seconds: 600 },
    ]);
  });

  it("leaves entries untouched when the cap is 0 or negative", () => {
    expect(capEntrySeconds(entries, 0)).toEqual(entries);
    expect(capEntrySeconds(entries, -5)).toEqual(entries);
  });

  it("handles empty input safely", () => {
    expect(capEntrySeconds([], 600)).toEqual([]);
  });
});

describe("aggregations (B5/B6)", () => {
  const entries = [
    { seconds: 600, words: 100, bookKey: "a", hour: 20 },
    { seconds: 300, words: 50, bookKey: "a", hour: 20 },
    { seconds: 900, words: 80, bookKey: "b", hour: 8 },
    { seconds: 100, bookKey: undefined },
    { seconds: 45, words: 10, bookKey: "b" },
  ];

  it("aggregates per book, desc, top-N", () => {
    const top = aggregateByBook(entries, 2);
    expect(top).toHaveLength(2);
    expect(top[0].bookKey).toBe("b");
    expect(top[0].seconds).toBe(945);
    expect(top[1].bookKey).toBe("a");
  });

  it("keeps unknown books under a stable label", () => {
    const top = aggregateByBook(entries, 5);
    expect(top.some((item) => item.bookKey === "(unknown)")).toBe(true);
  });

  it("buckets hours into 24 slots and ignores missing hours", () => {
    const hourly = aggregateByHour(entries);
    expect(hourly).toHaveLength(24);
    expect(hourly[20]).toBe(900);
    expect(hourly[8]).toBe(900);
    expect(hourly[12]).toBe(0);
  });

  it("counts distinct books", () => {
    expect(countDistinctBooks(entries)).toBe(2);
  });
});

describe("yearlyMonthlySeconds (B7)", () => {
  const entries = [
    { seconds: 600, date: "2026-01-15" },
    { seconds: 1200, date: "2026-02-03" },
    { seconds: 3000, date: "2025-12-31" },
    { seconds: 1, date: "garbage" },
  ] as any[];

  it("splits seconds into the 12 months of the selected year", () => {
    const months = yearlyMonthlySeconds(entries, 2026, (e) => (e as any).date);
    expect(months).toHaveLength(12);
    expect(months[0]).toBe(600);
    expect(months[1]).toBe(1200);
    expect(months[11]).toBe(0);
  });

  it("excludes other years and invalid entries", () => {
    const months = yearlyMonthlySeconds(entries, 2026, (e) => (e as any).date);
    expect(months.reduce((s, v) => s + v, 0)).toBe(1800);
  });
});

describe("goalProgress (B1)", () => {
  it("computes capped percentages", () => {
    const r = goalProgress(
      { seconds: 3600, words: 5000 },
      { dailyWords: 3000, dailyMinutes: 20, yearlyBooks: 1 }
    );
    expect(r.secondsPct).toBe(100);
    expect(r.wordsPct).toBe(100);
    expect(r.secondsReached).toBe(true);
    expect(r.wordsReached).toBe(true);
  });

  it("returns 0 when a goal is not set", () => {
    const r = goalProgress(
      { seconds: 100, words: 100 },
      { dailyWords: 0, dailyMinutes: 0, yearlyBooks: 0 }
    );
    expect(r.secondsPct).toBe(0);
    expect(r.wordsPct).toBe(0);
  });
});

describe("poster lines (B9)", () => {
  it("builds goal poster lines", () => {
    const lines = buildGoalPosterLines(
      { dailyWords: 1000, dailyMinutes: 30, yearlyBooks: 10 },
      { seconds: 1800, words: 500 },
      7
    );
    expect(lines[0]).toBe("Reading goals");
    expect(lines.join("\n")).toContain("50%");
    expect(lines.join("\n")).toContain("7/10");
  });

  it("builds book poster lines", () => {
    const lines = buildBookPosterLines("Book A", { seconds: 7200, words: 900 });
    expect(lines[0]).toBe("Book A");
    expect(lines.join("\n")).toContain("2h 0m");
    expect(lines.join("\n")).toContain("Words: 900");
  });
});

describe("max page time persistence (B4)", () => {
  beforeEach(() => localStorage.clear());

  it("round-trips minutes (0 = off)", () => {
    expect(loadMaxPageTimeMinutes()).toBe(0);
    expect(saveMaxPageTimeMinutes(30)).toBe(30);
    expect(loadMaxPageTimeMinutes()).toBe(30);
    expect(saveMaxPageTimeMinutes(-5)).toBe(0);
    expect(loadMaxPageTimeMinutes()).toBe(0);
  });
});

describe("helpers (A9)", () => {
  it("estimates remaining seconds from a reading rate", () => {
    expect(estimateRemainingSeconds(300, 300)).toBe(60);
    expect(estimateRemainingSeconds(0, 300)).toBe(0);
    expect(estimateRemainingSeconds(100, 0)).toBe(0);
  });

  it("builds the footer caption, omitting missing values", () => {
    expect(
      buildFooterCaption({ batteryPercent: 85, remainingSeconds: 720 })
    ).toBe("85% · 12 min");
    expect(buildFooterCaption({ batteryPercent: null, remainingSeconds: 0 })).toBe("");
    expect(
      buildFooterCaption({
        batteryPercent: 50,
        remainingSeconds: 0,
        showTime: false,
      })
    ).toBe("50%");
    // invalid battery is dropped
    expect(
      buildFooterCaption({ batteryPercent: 250, remainingSeconds: 60 })
    ).toBe("1 min");
  });

  it("merges manual entries into the per-date map", () => {
    const merged = mergeManualIntoDayMap(
      { "2026-11-20": { seconds: 100, words: 10 } },
      [{ key: "k", date: "2026-11-20", seconds: 50, words: 5 }]
    );
    expect(merged["2026-11-20"]).toEqual({ seconds: 150, words: 15 });
  });

  it("validateManualEntry returns a clean entry", () => {
    const entry = validateManualEntry({
      date: "2026-11-20",
      seconds: 12.7,
      words: 100,
    });
    expect(entry.seconds).toBe(13);
    expect(entry.key).toMatch(/^m-\d+$/);
  });

  it("MAX_PAGE_TIME_KEY is exported for storage tests", () => {
    expect(MAX_PAGE_TIME_KEY).toBe("koodo:maxPageTimeMinutes");
  });
});
