/**
 * Bookshelf helpers: combined client-side filtering (title/author/intro +
 * status + shelf membership) and list-meta line building. Pure functions —
 * no React, no engine access — so they are unit-testable.
 */

export type SearchFields = ("title" | "author" | "intro")[];

export type ReadingStatusFilter = "" | "unread" | "reading" | "finished";

export interface BookLike {
  key?: string;
  name?: string;
  author?: string;
  description?: string;
  /** last read timestamp (ms epoch) */
  lastReadTime?: number | string;
  /** reading percentage 0-100 when the engine provides it */
  percentage?: number;
}

export interface BookFilters {
  text?: string;
  /** Which fields the text may match; empty/undefined = title+author */
  fields?: SearchFields;
  status?: ReadingStatusFilter;
  /** Book keys belonging to the selected shelf (undefined = any shelf) */
  shelfKeys?: string[];
}

function contains(haystack: string | undefined, needle: string): boolean {
  const h = (haystack || "").toLowerCase();
  const n = needle.trim().toLowerCase();
  return n.length > 0 && h.includes(n);
}

/**
 * Combined filter used by the bookshelf. `text` matches the enabled fields;
 * `status` and `shelfKeys` are ANDed with it. Empty filters match everything.
 */
export function matchBookFilters(book: BookLike, filters: BookFilters): boolean {
  if (!book) {
    return false;
  }
  const text = (filters?.text || "").trim();
  if (text) {
    const fields: SearchFields =
      filters?.fields && filters.fields.length > 0
        ? filters.fields
        : ["title", "author"];
    const hit = fields.some((field) => {
      if (field === "title") return contains(book.name, text);
      if (field === "author") return contains(book.author, text);
      if (field === "intro") return contains(book.description, text);
      return false;
    });
    if (!hit) {
      return false;
    }
  }
  const status = filters?.status;
  if (status) {
    const pct =
      typeof book.percentage === "number" && Number.isFinite(book.percentage)
        ? book.percentage
        : undefined;
    if (pct === undefined) {
      // No progress data: only "unread" can match
      if (status === "reading" || status === "finished") {
        return false;
      }
    } else {
      if (status === "unread" && pct > 0) {
        return false;
      }
      if (status === "reading" && !(pct > 0 && pct < 100)) {
        return false;
      }
      if (status === "finished" && pct < 100) {
        return false;
      }
    }
  }
  if (filters?.shelfKeys && filters.shelfKeys.length > 0) {
    const key = book.key;
    if (key === undefined || !filters.shelfKeys.includes(key)) {
      return false;
    }
  }
  return true;
}

export interface BookMetaOptions {
  showIntro?: boolean;
  showReview?: boolean;
  showLastRead?: boolean;
  introLimit?: number;
  locale?: string;
  /**
   * Localised "Last read" label. The caller passes an already-translated
   * string so this pure module never hardcodes user-visible text.
   */
  lastReadLabel?: string;
}

function truncate(text: string, limit: number): string {
  const trimmed = text.replace(/\s+/g, " ").trim();
  if (trimmed.length <= limit) {
    return trimmed;
  }
  return trimmed.slice(0, Math.max(0, limit - 1)) + "…";
}

export function formatLastReadTime(
  time: number | string | undefined,
  locale?: string
): string {
  const value =
    typeof time === "string"
      ? Date.parse(time)
      : typeof time === "number"
        ? time
        : NaN;
  if (!Number.isFinite(value) || value <= 0) {
    return "";
  }
  try {
    const d = new Date(value);
    return d.toLocaleDateString(locale || "en-US", {
      year: "numeric",
      month: "short",
      day: "numeric",
    });
  } catch {
    return "";
  }
}

/**
 * Build the secondary meta lines for a book row (list / compact layouts):
 * intro truncated, optional review and last-read date. Empty strings are
 * dropped, so a row never renders blank lines.
 */
export function buildBookMetaLines(
  book: BookLike,
  options: BookMetaOptions = {}
): string[] {
  if (!book) {
    return [];
  }
  const lines: string[] = [];
  const limit = Math.max(10, options.introLimit || 40);
  if (options.showIntro && book.description) {
    const intro = truncate(book.description, limit);
    if (intro) {
      lines.push(intro);
    }
  }
  if (options.showLastRead) {
    const last = formatLastReadTime(book.lastReadTime, options.locale);
    if (last) {
      // The label comes from the caller so it can be translated; the default
      // keeps the module usable (and testable) without an i18n dependency.
      const label = options.lastReadLabel || "Last read";
      lines.push(`${label} ${last}`);
    }
  }
  return lines;
}
