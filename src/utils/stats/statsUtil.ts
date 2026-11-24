/**
 * Reading goals, manual stats entry and aggregation helpers.
 *
 * All functions are pure except the localStorage-backed load/save pairs,
 * which swallow storage/parse errors and fall back to defaults (per the
 * design doc's error-handling contract).
 */

export interface DayStatEntry {
  seconds: number;
  words?: number;
  bookKey?: string;
  /** Hour of day 0-23; optional — engine data may not carry it. */
  hour?: number;
}

export interface ReadingGoals {
  /** Daily word-count goal (0 = no goal) */
  dailyWords: number;
  /** Daily reading-time goal in minutes (0 = no goal) */
  dailyMinutes: number;
  /** Yearly finished-books goal (0 = no goal) */
  yearlyBooks: number;
}

export interface ManualStatEntry {
  key: string;
  /** YYYY-MM-DD */
  date: string;
  seconds: number;
  words: number;
  bookKey?: string;
  note?: string;
}

export interface BookRankEntry {
  bookKey: string;
  seconds: number;
  words: number;
}

export const GOALS_STORAGE_KEY = "koodo:readingGoals";
export const MANUAL_STATS_STORAGE_KEY = "koodo:manualStats";
export const MAX_PAGE_TIME_KEY = "koodo:maxPageTimeMinutes";
export const MAX_PAGE_TIME_MINUTES = 60 * 48; // hard cap: 48h per single page record
const MAX_MANUAL_ENTRIES = 2000;

export const DEFAULT_GOALS: ReadingGoals = {
  dailyWords: 0,
  dailyMinutes: 0,
  yearlyBooks: 0,
};

function toFiniteNumber(value: unknown): number {
  const n =
    typeof value === "string" ? parseFloat(value) : (value as number);
  return typeof n === "number" && Number.isFinite(n) ? n : NaN;
}

/**
 * Normalize arbitrary input into valid goals. Negative/NaN values become 0;
 * values above the caps are clamped. Always returns a fresh object.
 */
export function normalizeGoals(input: unknown): ReadingGoals {
  const src = (input && typeof input === "object" ? input : {}) as Record<
    string,
    unknown
  >;
  const dailyWords = Math.min(Math.max(toFiniteNumber(src.dailyWords) || 0, 0), 10_000_000);
  const dailyMinutes = Math.min(
    Math.max(toFiniteNumber(src.dailyMinutes) || 0, 0),
    1440
  );
  const yearlyBooks = Math.min(
    Math.max(toFiniteNumber(src.yearlyBooks) || 0, 0),
    10_000
  );
  return { dailyWords, dailyMinutes, yearlyBooks };
}

export function loadGoals(): ReadingGoals {
  if (typeof localStorage === "undefined") {
    return { ...DEFAULT_GOALS };
  }
  try {
    const raw = localStorage.getItem(GOALS_STORAGE_KEY);
    if (!raw) {
      return { ...DEFAULT_GOALS };
    }
    return normalizeGoals(JSON.parse(raw));
  } catch {
    return { ...DEFAULT_GOALS };
  }
}

export function saveGoals(goals: ReadingGoals): ReadingGoals {
  const normalized = normalizeGoals(goals);
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(GOALS_STORAGE_KEY, JSON.stringify(normalized));
    }
  } catch {
    // Storage unavailable/full — goals still work for the current session.
  }
  return normalized;
}

/**
 * Load the "max recorded reading time per page" cap in minutes.
 * 0/invalid means "no cap".
 */
export function loadMaxPageTimeMinutes(): number {
  if (typeof localStorage === "undefined") {
    return 0;
  }
  try {
    const raw = parseInt(localStorage.getItem(MAX_PAGE_TIME_KEY) || "", 10);
    if (!Number.isFinite(raw) || raw < 0) {
      return 0;
    }
    return Math.min(raw, MAX_PAGE_TIME_MINUTES);
  } catch {
    return 0;
  }
}

export function saveMaxPageTimeMinutes(minutes: number): number {
  const value =
    typeof minutes === "number" && Number.isFinite(minutes) && minutes > 0
      ? Math.round(Math.min(minutes, MAX_PAGE_TIME_MINUTES))
      : 0;
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(MAX_PAGE_TIME_KEY, String(value));
    }
  } catch {
    // non-fatal
  }
  return value;
}

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Why a manual stats entry was rejected. The caller maps the code to a
 * translated message — this module must not hardcode user-visible text.
 */
export type ManualEntryErrorCode =
  | "EMPTY"
  | "INVALID_DATE"
  | "INVALID_SECONDS"
  | "SECONDS_TOO_LARGE"
  | "INVALID_WORDS"
  | "WORDS_TOO_LARGE";

/** An error carrying a machine-readable [code] for the caller to translate. */
export class ManualEntryError extends Error {
  readonly code: ManualEntryErrorCode;
  constructor(code: ManualEntryErrorCode) {
    super(code);
    this.name = "ManualEntryError";
    this.code = code;
  }
}

/**
 * Validate a manual stats entry. Throws a [ManualEntryError] describing the
 * first violated rule (order: date, seconds, words, size).
 */
export function validateManualEntry(
  entry: Partial<ManualStatEntry>
): ManualStatEntry {
  if (!entry || typeof entry !== "object") {
    throw new ManualEntryError("EMPTY");
  }
  const date = String(entry.date || "");
  if (!DATE_RE.test(date)) {
    throw new ManualEntryError("INVALID_DATE");
  }
  const seconds = toFiniteNumber(entry.seconds);
  if (!Number.isFinite(seconds) || seconds < 1) {
    throw new ManualEntryError("INVALID_SECONDS");
  }
  if (seconds > 86400) {
    throw new ManualEntryError("SECONDS_TOO_LARGE");
  }
  const words = toFiniteNumber(entry.words);
  if (!Number.isFinite(words) || words < 0) {
    throw new ManualEntryError("INVALID_WORDS");
  }
  if (words > 10_000_000) {
    throw new ManualEntryError("WORDS_TOO_LARGE");
  }
  const note = entry.note ? String(entry.note).slice(0, 500) : undefined;
  return {
    key:
      entry.key && String(entry.key).length > 0
        ? String(entry.key)
        : `m-${Date.now()}`,
    date,
    seconds: Math.round(seconds),
    words: Math.round(words),
    bookKey: entry.bookKey ? String(entry.bookKey) : undefined,
    note,
  };
}

export function loadManualStats(): ManualStatEntry[] {
  if (typeof localStorage === "undefined") {
    return [];
  }
  try {
    const raw = localStorage.getItem(MANUAL_STATS_STORAGE_KEY);
    if (!raw) {
      return [];
    }
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed
      .map((item) => {
        try {
          return validateManualEntry(item);
        } catch {
          return null;
        }
      })
      .filter((item): item is ManualStatEntry => item !== null);
  } catch {
    return [];
  }
}

/**
 * Add a manual entry; returns the saved list (trimmed to MAX_MANUAL_ENTRIES
 * keeping the most recent). Throws on invalid input.
 */
export function addManualStat(
  input: Partial<ManualStatEntry>
): ManualStatEntry[] {
  const entry = validateManualEntry(input);
  const list = [...loadManualStats(), entry];
  const trimmed = list.slice(-MAX_MANUAL_ENTRIES);
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(MANUAL_STATS_STORAGE_KEY, JSON.stringify(trimmed));
    }
  } catch {
    // keep in-memory copy; caller still gets the list back
  }
  return trimmed;
}

export function removeManualStat(key: string): ManualStatEntry[] {
  const list = loadManualStats().filter((item) => item.key !== key);
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(MANUAL_STATS_STORAGE_KEY, JSON.stringify(list));
    }
  } catch {
    // ignore
  }
  return list;
}

/**
 * Merge manual entries into a per-date seconds/words map (the shape the
 * stats page builds from ReadingTimeUtil).
 */
export function mergeManualIntoDayMap(
  dateMap: Record<string, { seconds: number; words: number }>,
  entries: ManualStatEntry[]
): Record<string, { seconds: number; words: number }> {
  const out: Record<string, { seconds: number; words: number }> = {};
  for (const [date, value] of Object.entries(dateMap)) {
    out[date] = {
      seconds: finite(Math.max(0, value?.seconds || 0)),
      words: finite(Math.max(0, value?.words || 0)),
    };
  }
  for (const entry of entries) {
    const cell =
      out[entry.date] || (out[entry.date] = { seconds: 0, words: 0 });
    cell.seconds += entry.seconds;
    cell.words += entry.words;
  }
  return out;
}

function finite(n: number): number {
  return Number.isFinite(n) ? Math.round(n) : 0;
}

/**
 * Cap each recorded entry to a maximum (the "单页最大记录阅读时长" option).
 * maxSeconds <= 0 means "no cap".
 */
export function capEntrySeconds(
  entries: DayStatEntry[],
  maxSeconds: number
): DayStatEntry[] {
  const cap = Number.isFinite(maxSeconds) ? maxSeconds : 0;
  if (entries.length === 0) {
    return [];
  }
  if (cap <= 0) {
    return entries.map((e) => ({ ...e }));
  }
  return entries.map((e) => ({
    ...e,
    seconds: Math.min(Math.max(0, finite(e.seconds)), finite(cap)),
  }));
}

const UNKNOWN_BOOK_KEY = "(unknown)";

/** Aggregate per-book seconds/words, sorted desc, limited to topN. */
export function aggregateByBook(
  entries: DayStatEntry[],
  topN = 10
): BookRankEntry[] {
  const map = new Map<string, BookRankEntry>();
  for (const entry of entries) {
    const key =
      entry.bookKey && String(entry.bookKey).trim() !== ""
        ? String(entry.bookKey)
        : UNKNOWN_BOOK_KEY;
    const seconds = entry.seconds > 0 ? finite(entry.seconds) : 0;
    if (seconds <= 0 && (entry.words || 0) <= 0) {
      continue;
    }
    let cell = map.get(key);
    if (!cell) {
      cell = { bookKey: key, seconds: 0, words: 0 };
      map.set(key, cell);
    }
    cell.seconds += seconds;
    cell.words += finite(entry.words || 0);
  }
  const list = Array.from(map.values());
  list.sort((a, b) => b.seconds - a.seconds || b.words - a.words);
  const n = Math.max(1, Math.floor(topN));
  return list.slice(0, n).map((item) => ({ ...item }));
}

/**
 * Hour-of-day (0-23) seconds distribution over entries that carry an `hour`.
 *
 * NOTE: the reading engine's own `readingStats` map records
 * "<bookKey>-<seconds>" per day and carries NO hour, so engine data never
 * fills these buckets. The statistics page therefore builds its hourly
 * distribution from the LOCAL-ONLY hour store (see
 * `src/utils/stats/hourStatsUtil.ts`), which the reader fills in while
 * reading. This helper remains for entries that do carry an hour (e.g.
 * manual entries).
 */
export function aggregateByHour(entries: DayStatEntry[]): number[] {
  const buckets = new Array(24).fill(0) as number[];
  for (const entry of entries) {
    const hour = entry.hour;
    if (typeof hour !== "number" || !Number.isFinite(hour)) {
      continue;
    }
    const idx = Math.floor(hour) % 24;
    buckets[((idx % 24) + 24) % 24] += finite(entry.seconds);
  }
  return buckets;
}

/** Seconds per month (Jan..Dec) for dates in the given year. */
export function yearlyMonthlySeconds(
  entries: DayStatEntry[],
  year: number,
  hasDate?: (e: DayStatEntry) => string | undefined
): number[] {
  const months = new Array(12).fill(0) as number[];
  const target = Math.floor(year);
  if (!Number.isFinite(target)) {
    return months;
  }
  for (const entry of entries) {
    const date = hasDate ? hasDate(entry) : undefined;
    if (!date) {
      continue;
    }
    const match = /^(\d{4})-(\d{2})-/.exec(date);
    if (!match) {
      continue;
    }
    if (parseInt(match[1], 10) !== target) {
      continue;
    }
    const month = parseInt(match[2], 10) - 1;
    if (month >= 0 && month < 12) {
      months[month] += finite(entry.seconds);
    }
  }
  return months;
}

export interface GoalProgress {
  secondsPct: number;
  wordsPct: number;
  secondsReached: boolean;
  wordsReached: boolean;
}

function clampPct(n: number): number {
  if (!Number.isFinite(n) || n < 0) {
    return 0;
  }
  return Math.min(n, 100);
}

/** Progress 0-100 (%) toward a daily goal; goal <= 0 means "not set" -> 0. */
export function goalProgress(
  actual: { seconds: number; words: number },
  goals: ReadingGoals
): GoalProgress {
  const actualSeconds = Math.max(0, finite(actual?.seconds || 0));
  const actualWords = Math.max(0, finite(actual?.words || 0));
  const goalSeconds = (goals?.dailyMinutes || 0) * 60;
  const secondsPct =
    goalSeconds > 0 ? clampPct((actualSeconds / goalSeconds) * 100) : 0;
  const wordsPct =
    goals?.dailyWords > 0
      ? clampPct((actualWords / goals.dailyWords) * 100)
      : 0;
  return {
    secondsPct,
    wordsPct,
    secondsReached: goalSeconds > 0 && actualSeconds >= goalSeconds,
    wordsReached: (goals?.dailyWords || 0) > 0 && actualWords >= goals.dailyWords,
  };
}

/** Distinct book count across entries (used for the yearly books goal). */
export function countDistinctBooks(entries: DayStatEntry[]): number {
  const set = new Set<string>();
  for (const entry of entries) {
    if (entry.bookKey && String(entry.bookKey).trim() !== "") {
      set.add(String(entry.bookKey));
    }
  }
  return set.size;
}

/**
 * Estimate remaining reading seconds for the given number of unread words.
 * ratePerMin defaults to 300 words/min (~5 min per 100 words).
 */
export function estimateRemainingSeconds(
  remainingWords: number,
  ratePerMin = 300
): number {
  const words = finite(remainingWords || 0);
  const rate = finite(ratePerMin || 0);
  if (words <= 0 || rate <= 0) {
    return 0;
  }
  return Math.round((words / rate) * 60);
}

/**
 * Build a footer/header caption line: battery percent (optional) and
 * estimated remaining time (optional). Missing values are omitted, not
 * rendered as "0".
 */
export function buildFooterCaption(options: {
  batteryPercent?: number | null;
  remainingSeconds?: number | null;
  showBattery?: boolean;
  showTime?: boolean;
  template?: string;
}): string {
  const parts: string[] = [];
  if (
    options.showBattery !== false &&
    typeof options.batteryPercent === "number" &&
    Number.isFinite(options.batteryPercent) &&
    options.batteryPercent >= 0 &&
    options.batteryPercent <= 100
  ) {
    parts.push(`${Math.round(options.batteryPercent)}%`);
  }
  const remaining =
    typeof options.remainingSeconds === "number"
      ? Math.max(0, finite(options.remainingSeconds))
      : 0;
  if (options.showTime !== false && remaining > 0) {
    parts.push(`${Math.max(1, Math.round(remaining / 60))} min`);
  }
  if (parts.length === 0) {
    return options.template ? options.template : "";
  }
  return parts.join(" · ");
}

/** Poster data lines for the reading-goal share card (pure data build). */
export function buildGoalPosterLines(
  goals: ReadingGoals,
  today: { seconds: number; words: number },
  booksThisYear: number
): string[] {
  const lines: string[] = [];
  const g = normalizeGoals(goals);
  lines.push("Reading goals");
  if (g.dailyWords > 0) {
    lines.push(
      `Daily words: ${Math.round((today.words || 0) / g.dailyWords * 100)}% (${today.words || 0}/${g.dailyWords})`
    );
  }
  if (g.dailyMinutes > 0) {
    lines.push(
      `Daily time: ${Math.round(((today.seconds || 0) / (g.dailyMinutes * 60)) * 100)}% (${Math.round((today.seconds || 0) / 60)}min/${g.dailyMinutes}min)`
    );
  }
  if (g.yearlyBooks > 0) {
    lines.push(`Yearly books: ${booksThisYear}/${g.yearlyBooks}`);
  }
  return lines;
}

/** Poster data lines for a single book's stats share card. */
export function buildBookPosterLines(
  bookName: string,
  stats: { seconds?: number; words?: number; pages?: number }
): string[] {
  const lines: string[] = [bookName || "Book", "Reading stats"];
  if (typeof stats?.seconds === "number" && stats.seconds > 0) {
    lines.push(`Time: ${Math.round(stats.seconds / 3600)}h ${Math.round((stats.seconds % 3600) / 60)}m`);
  }
  if (typeof stats?.words === "number" && stats.words > 0) {
    lines.push(`Words: ${stats.words}`);
  }
  if (typeof stats?.pages === "number" && stats.pages > 0) {
    lines.push(`Pages: ${stats.pages}`);
  }
  return lines;
}
