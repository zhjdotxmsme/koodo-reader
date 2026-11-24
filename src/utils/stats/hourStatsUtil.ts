/**
 * Hour-of-day reading-time tracking.
 *
 * The closed-source reading engine stores daily reading time as
 * `readingStats[YYYY-MM-DD] = ["<bookKey>-<seconds>", ...]`. That format
 * carries NO hour and NO word count, so the statistics page could never
 * render an hourly distribution or a word-based goal — the data was simply
 * not there.
 *
 * This module records the hour alongside the seconds in a separate,
 * LOCAL-ONLY store (localStorage). It is deliberately NOT a ConfigService
 * config, because every config in `CommonTool.configList` is uploaded to the
 * cloud, and per-hour reading samples are large, machine-specific noise that
 * has no business in a sync payload.
 *
 * The store is a flat map:
 *
 *   "YYYY-MM-DD" -> { "<bookKey>:<hour>" -> seconds }
 *
 * so a day can hold one bucket per (book, hour) pair. Writes are additive and
 * idempotent per call site: the caller reports the number of seconds read in
 * a given hour, and the bucket grows.
 */

export const HOUR_STATS_STORAGE_KEY = "koodo:readingHourStats";

/** Longest history kept, in days. Older days are dropped on write. */
export const MAX_HOUR_STATS_DAYS = 400;

/**
 * A single day of hourly samples: `"<bookKey>:<hour>" -> seconds`.
 * The hour is 0-23; the book key is the stable md5-derived book key.
 */
export type HourStatsDay = Record<string, number>;

/** All tracked days: `"YYYY-MM-DD" -> HourStatsDay`. */
export type HourStatsMap = Record<string, HourStatsDay>;

export function dateToHourStatsKey(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

/**
 * Build the bucket key for one (book, hour) pair. The hour is clamped to
 * 0-23 so a bad caller cannot corrupt the map shape.
 */
export function hourBucketKey(bookKey: string, hour: number): string {
  const safeHour = Math.min(Math.max(Math.floor(hour), 0), 23);
  return `${bookKey}:${safeHour}`;
}

function isFiniteNonNegative(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0;
}

/** Parse the persisted blob, dropping anything that is not a usable map. */
export function normalizeHourStats(input: unknown): HourStatsMap {
  const result: HourStatsMap = {};
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    return result;
  }
  for (const [date, day] of Object.entries(input as Record<string, unknown>)) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) continue;
    if (!day || typeof day !== "object" || Array.isArray(day)) continue;
    const buckets: HourStatsDay = {};
    for (const [bucket, seconds] of Object.entries(day as Record<string, unknown>)) {
      if (!isFiniteNonNegative(seconds)) continue;
      const sep = bucket.lastIndexOf(":");
      if (sep <= 0) continue;
      const hour = Number(bucket.slice(sep + 1));
      if (!Number.isInteger(hour) || hour < 0 || hour > 23) continue;
      buckets[bucket] = Math.round(seconds);
    }
    result[date] = buckets;
  }
  return result;
}

/** Load the tracked days. Corrupt/missing data returns an empty map. */
export function loadHourStats(): HourStatsMap {
  if (typeof localStorage === "undefined") {
    return {};
  }
  try {
    const raw = localStorage.getItem(HOUR_STATS_STORAGE_KEY);
    if (!raw) {
      return {};
    }
    return normalizeHourStats(JSON.parse(raw));
  } catch {
    return {};
  }
}

function persistHourStats(map: HourStatsMap): void {
  try {
    if (typeof localStorage === "undefined") {
      return;
    }
    localStorage.setItem(HOUR_STATS_STORAGE_KEY, JSON.stringify(map));
  } catch {
    // storage failure is non-fatal: the in-session value still applies
  }
}

/**
 * Record `seconds` read by `bookKey` during `hour` of `date`.
 *
 * Returns the updated map. Non-finite or negative inputs are ignored, so a
 * misbehaving caller can never corrupt the stored history.
 */
export function recordHourSeconds(
  date: Date,
  hour: number,
  bookKey: string,
  seconds: number
): HourStatsMap {
  const key = (bookKey || "").trim();
  if (!key || !isFiniteNonNegative(seconds) || seconds <= 0) {
    return loadHourStats();
  }
  const dateKey = dateToHourStatsKey(date);
  const bucket = hourBucketKey(key, hour);
  const map = loadHourStats();
  const day = { ...(map[dateKey] || {}) };
  day[bucket] = (day[bucket] || 0) + Math.round(seconds);
  map[dateKey] = day;
  pruneHourStats(map);
  persistHourStats(map);
  return map;
}

/**
 * Drop the oldest days once the history exceeds [MAX_HOUR_STATS_DAYS].
 * Mutates and returns the map for convenience.
 */
export function pruneHourStats(map: HourStatsMap): HourStatsMap {
  const dates = Object.keys(map).sort();
  if (dates.length <= MAX_HOUR_STATS_DAYS) {
    return map;
  }
  const excess = dates.length - MAX_HOUR_STATS_DAYS;
  for (let i = 0; i < excess; i++) {
    delete map[dates[i]];
  }
  return map;
}

/**
 * Hour-of-day (0-23) seconds distribution across the given entries.
 * Entries without a usable hour are skipped, so an engine that does not
 * provide one yields all-zero buckets (and the caller can say so).
 */
export function aggregateHourStats(map: HourStatsMap): number[] {
  const buckets = new Array(24).fill(0) as number[];
  for (const day of Object.values(map)) {
    for (const [bucket, seconds] of Object.entries(day || {})) {
      const sep = bucket.lastIndexOf(":");
      if (sep <= 0) continue;
      const hour = Number(bucket.slice(sep + 1));
      if (!Number.isInteger(hour) || hour < 0 || hour > 23) continue;
      if (!isFiniteNonNegative(seconds)) continue;
      buckets[hour] += seconds;
    }
  }
  return buckets;
}

/** Total tracked seconds across all days. */
export function totalHourStatsSeconds(map: HourStatsMap): number {
  let total = 0;
  for (const day of Object.values(map)) {
    for (const seconds of Object.values(day || {})) {
      if (isFiniteNonNegative(seconds)) {
        total += seconds;
      }
    }
  }
  return total;
}
