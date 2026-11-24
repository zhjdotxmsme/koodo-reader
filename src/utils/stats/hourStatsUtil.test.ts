import {
  aggregateHourStats,
  dateToHourStatsKey,
  HOUR_STATS_STORAGE_KEY,
  hourBucketKey,
  loadHourStats,
  MAX_HOUR_STATS_DAYS,
  normalizeHourStats,
  pruneHourStats,
  recordHourSeconds,
  totalHourStatsSeconds,
} from "./hourStatsUtil";

describe("hourStatsUtil", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  describe("dateToHourStatsKey", () => {
    it("formats a date as YYYY-MM-DD", () => {
      expect(dateToHourStatsKey(new Date(2026, 10, 24))).toBe("2026-11-24");
    });

    it("zero-pads single-digit months and days", () => {
      expect(dateToHourStatsKey(new Date(2026, 0, 5))).toBe("2026-01-05");
    });
  });

  describe("hourBucketKey", () => {
    it("combines the book key and the hour", () => {
      expect(hourBucketKey("abc", 9)).toBe("abc:9");
    });

    it("clamps the hour into 0..23", () => {
      expect(hourBucketKey("abc", 99)).toBe("abc:23");
      expect(hourBucketKey("abc", -5)).toBe("abc:0");
    });
  });

  describe("normalizeHourStats", () => {
    it("drops days whose key is not a date", () => {
      expect(normalizeHourStats({ nope: { "a:1": 10 } })).toEqual({});
    });

    it("drops buckets with an invalid hour", () => {
      const result = normalizeHourStats({ "2026-11-24": { "a:99": 10, "a:1": 5 } });
      expect(result["2026-11-24"]).toEqual({ "a:1": 5 });
    });

    it("drops non-numeric and negative seconds", () => {
      const result = normalizeHourStats({
        "2026-11-24": { "a:1": "x", "b:2": -5, "c:3": 7 },
      });
      expect(result["2026-11-24"]).toEqual({ "c:3": 7 });
    });

    it("rejects a non-object payload", () => {
      expect(normalizeHourStats(null)).toEqual({});
      expect(normalizeHourStats([])).toEqual({});
      expect(normalizeHourStats("nope")).toEqual({});
    });
  });

  describe("recordHourSeconds", () => {
    it("stores the seconds under the day and hour bucket", () => {
      const date = new Date(2026, 10, 24, 14, 30);
      recordHourSeconds(date, 14, "book1", 60);

      const stored = loadHourStats();
      expect(stored["2026-11-24"]).toEqual({ "book1:14": 60 });
    });

    it("accumulates repeated samples in the same bucket", () => {
      const date = new Date(2026, 10, 24, 14, 30);
      recordHourSeconds(date, 14, "book1", 60);
      recordHourSeconds(date, 14, "book1", 30);

      expect(loadHourStats()["2026-11-24"]["book1:14"]).toBe(90);
    });

    it("keeps different books and hours apart", () => {
      const date = new Date(2026, 10, 24, 14, 30);
      recordHourSeconds(date, 14, "book1", 60);
      recordHourSeconds(date, 15, "book1", 60);
      recordHourSeconds(date, 14, "book2", 60);

      const day = loadHourStats()["2026-11-24"];
      expect(day).toEqual({ "book1:14": 60, "book1:15": 60, "book2:14": 60 });
    });

    it("ignores an empty book key", () => {
      recordHourSeconds(new Date(2026, 10, 24), 14, "", 60);
      expect(loadHourStats()).toEqual({});
    });

    it("ignores non-finite or negative seconds", () => {
      const date = new Date(2026, 10, 24);
      recordHourSeconds(date, 14, "book1", NaN);
      recordHourSeconds(date, 14, "book1", -10);
      expect(loadHourStats()).toEqual({});
    });

    it("ignores a zero sample (nothing to record)", () => {
      recordHourSeconds(new Date(2026, 10, 24), 14, "book1", 0);
      expect(loadHourStats()).toEqual({});
    });
  });

  describe("pruneHourStats", () => {
    it("keeps the newest days and drops the oldest", () => {
      const map: Record<string, Record<string, number>> = {};
      const dates: string[] = [];
      for (let i = 0; i < MAX_HOUR_STATS_DAYS + 5; i++) {
        // Walk the calendar properly so the dates stay in one year.
        const date = new Date(2026, 0, 1 + i);
        const key = dateToHourStatsKey(date);
        dates.push(key);
        map[key] = { "book1:9": 60 };
      }
      const pruned = pruneHourStats(map);
      const kept = Object.keys(pruned).sort();

      expect(kept.length).toBe(MAX_HOUR_STATS_DAYS);
      // The oldest day is gone, the newest is kept.
      expect(kept[0]).not.toBe(dates[0]);
      expect(kept[kept.length - 1]).toBe(dates[dates.length - 1]);
    });

    it("leaves a short history untouched", () => {
      const map = { "2026-11-24": { "a:1": 60 } };
      expect(pruneHourStats({ ...map })).toEqual(map);
    });
  });

  describe("aggregateHourStats", () => {
    it("returns 24 zero buckets for an empty history", () => {
      const buckets = aggregateHourStats({});
      expect(buckets.length).toBe(24);
      expect(buckets.every((v) => v === 0)).toBe(true);
    });

    it("sums the seconds into the matching hour bucket", () => {
      const buckets = aggregateHourStats({
        "2026-11-24": { "a:9": 60, "b:9": 30, "a:21": 120 },
      });
      expect(buckets[9]).toBe(90);
      expect(buckets[21]).toBe(120);
      expect(buckets[0]).toBe(0);
    });

    it("sums across days", () => {
      const buckets = aggregateHourStats({
        "2026-11-23": { "a:9": 60 },
        "2026-11-24": { "a:9": 60 },
      });
      expect(buckets[9]).toBe(120);
    });
  });

  describe("totalHourStatsSeconds", () => {
    it("adds up every bucket", () => {
      expect(
        totalHourStatsSeconds({
          "2026-11-23": { "a:9": 60 },
          "2026-11-24": { "a:9": 30, "b:10": 10 },
        })
      ).toBe(100);
    });
  });

  describe("loadHourStats", () => {
    it("returns an empty map when nothing is stored", () => {
      expect(loadHourStats()).toEqual({});
    });

    it("returns an empty map for corrupt JSON", () => {
      localStorage.setItem(HOUR_STATS_STORAGE_KEY, "{not json");
      expect(loadHourStats()).toEqual({});
    });
  });
});
