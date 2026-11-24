import React from "react";
import "./stats.css";
import { StatsProps, StatsState } from "./interface";
import { withRouter } from "react-router-dom";
import { Trans } from "react-i18next";
import toast from "react-hot-toast";
import { saveAs } from "file-saver";
import {
  ConfigService,
  ReadingTimeUtil,
} from "../../assets/lib/kookit-extra-browser.min";
import DatabaseService from "../../utils/storage/databaseService";
import {
  addManualStat,
  aggregateByBook,
  buildBookPosterLines,
  buildGoalPosterLines,
  capEntrySeconds,
  countDistinctBooks,
  DEFAULT_GOALS,
  goalProgress,
  loadGoals,
  loadManualStats,
  loadMaxPageTimeMinutes,
  ManualEntryError,
  normalizeGoals,
  removeManualStat,
  saveGoals,
  saveMaxPageTimeMinutes,
  yearlyMonthlySeconds,
  type DayStatEntry,
  type ManualEntryErrorCode,
} from "../../utils/stats/statsUtil";
import { getBatteryLevel } from "../../utils/reader/batteryUtil";
import { aggregateHourStats, loadHourStats } from "../../utils/stats/hourStatsUtil";
import {
  Line,
  AreaChart,
  Area,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
} from "recharts";

/**
 * The value type recharts passes to a tooltip formatter. It is not exported
 * by the library, so it is restated here: a single value, or an array of
 * values for a multi-series tooltip.
 */
type TooltipValue =
  | number
  | string
  | ReadonlyArray<number | string>
  | undefined;

/**
 * Validation failure codes -> i18n keys. The util throws machine-readable
 * codes; the page turns them into translated text.
 */
const MANUAL_ENTRY_ERROR_KEYS: Record<ManualEntryErrorCode, string> = {
  EMPTY: "Invalid input",
  INVALID_DATE: "Date must be YYYY-MM-DD",
  INVALID_SECONDS: "Reading time must be greater than 0 seconds",
  SECONDS_TOO_LARGE: "A single entry cannot exceed 24 hours",
  INVALID_WORDS: "Word count cannot be negative",
  WORDS_TOO_LARGE: "Word count is out of range",
};

class Stats extends React.Component<StatsProps, StatsState> {
  private readingTimeUtil = new ReadingTimeUtil(ConfigService, {
    registerUnloadHandler: () => () => {},
  });
  constructor(props: StatsProps) {
    super(props);
    const todayKey = this.dateToKey(new Date());
    this.state = {
      totalBooks: 0,
      finishedBooks: 0,
      totalSeconds: 0,
      longestStreak: 0,
      avgDailySeconds: 0,
      last30Days: [],
      heatmapData: [],
      chartTab: "bar",
      isLoading: true,
      goals: { ...DEFAULT_GOALS },
      goalsForm: {
        dailyWords: "",
        dailyMinutes: "",
        yearlyBooks: "",
      },
      manualStats: loadManualStats(),
      manualForm: { date: todayKey, seconds: "", words: "" },
      maxPageMinutes: String(loadMaxPageTimeMinutes() || ""),
      viewTab: "trend",
      year: new Date().getFullYear(),
      years: [new Date().getFullYear()],
      filterText: "",
      ranking: [],
      hourly: new Array(24).fill(0),
      hasHourData: false,
      yearlyMonths: [],
      today: { seconds: 0, words: 0 },
      bookNameMap: {},
      allDayEntries: {},
      battery: null,
    };
  }

  componentDidMount() {
    this.loadStats();
    // A9: battery percent (Web Battery API where supported, null otherwise)
    getBatteryLevel(1500).then((level) => {
      this.setState({ battery: level });
    });
  }

  loadStats = async () => {
    // ── 1. Books read (has a recordLocation) ──────────────────────────────
    let allBookKeys: string[] = [];
    try {
      allBookKeys = await DatabaseService.getAllRecordKeys("books");
    } catch (e) {
      allBookKeys = [];
    }
    let totalBooks = 0;
    // "Finished" is a stronger claim than "opened": the reader marks a book
    // finished by setting recordLocation.percentage to "1" (see the
    // markAction dialog), and 100% is what the book list renders as "Done".
    // The yearly-books goal counts THESE, not every book that was opened.
    let finishedBooks = 0;
    for (const key of allBookKeys) {
      const loc = ConfigService.getObjectConfig(key, "recordLocation", null);
      if (loc && Object.keys(loc).length > 0) {
        totalBooks++;
        const percentage = loc.percentage;
        if (percentage !== undefined && parseFloat(String(percentage)) >= 1) {
          finishedBooks++;
        }
      }
    }

    // ── 2. Total reading time from readingStats ────────────────────────────
    const allDates = this.readingTimeUtil.getAllDates();
    let totalSeconds = 0;
    const dateSecondsMap: Record<string, number> = {};
    const allDayEntries: Record<string, DayStatEntry[]> = {};
    const maxPageSec =
      parseInt(this.state.maxPageMinutes || "0", 10) * 60;
    for (const dateKey of allDates) {
      let dayStats: DayStatEntry[] = [];
      try {
        const raw = this.readingTimeUtil.getDayStats(dateKey);
        dayStats = Array.isArray(raw)
          ? raw.filter((s) => s && typeof s.seconds === "number")
          : [];
      } catch {
        dayStats = [];
      }
      // B4: apply the per-page cap (0 = no cap)
      const capped = capEntrySeconds(dayStats, maxPageSec);
      allDayEntries[dateKey] = capped;
      const dayTotal = capped.reduce((sum, s) => sum + s.seconds, 0);
      totalSeconds += dayTotal;
      dateSecondsMap[dateKey] = dayTotal;
    }
    this.setState({ allDayEntries });

    // ── 3. Longest streak (consecutive reading days up to today) ──────────
    const datesWithReading = new Set(
      allDates.filter((d) => (dateSecondsMap[d] || 0) > 0)
    );
    const today = new Date();
    let longestStreak = 0;
    let currentStreak = 0;
    for (let i = 0; i < 365; i++) {
      const d = new Date(today);
      d.setDate(today.getDate() - i);
      const key = this.dateToKey(d);
      if (datesWithReading.has(key)) {
        currentStreak++;
        longestStreak = Math.max(longestStreak, currentStreak);
      } else {
        currentStreak = 0;
      }
    }

    // ── 4. Daily average ──────────────────────────────────────────────────
    const activeDays = Object.values(dateSecondsMap).filter(
      (s) => s > 0
    ).length;
    const avgDailySeconds =
      activeDays > 0 ? Math.round(totalSeconds / activeDays) : 0;

    // ── 5. Last 30 days data ──────────────────────────────────────────────
    const last30Days: { date: string; seconds: number }[] = [];
    for (let i = 29; i >= 0; i--) {
      const d = new Date(today);
      d.setDate(today.getDate() - i);
      const key = this.dateToKey(d);
      last30Days.push({
        date: `${d.getMonth() + 1}/${d.getDate()}`,
        seconds: dateSecondsMap[key] || 0,
      });
    }

    // ── 6. Heatmap: last 52 weeks ─────────────────────────────────────────
    const heatmapData: { date: string; seconds: number }[] = [];
    const heatmapStart = new Date(today);
    heatmapStart.setDate(today.getDate() - 51 * 7);
    heatmapStart.setDate(heatmapStart.getDate() - heatmapStart.getDay());
    for (
      const d = new Date(heatmapStart);
      d <= today;
      d.setDate(d.getDate() + 1)
    ) {
      const key = this.dateToKey(d);
      heatmapData.push({ date: key, seconds: dateSecondsMap[key] || 0 });
    }

    // ── goals + derived views (B1-B9) ──────────────────────────────────────
    const goals = loadGoals();
    this.setState(
      {
        totalBooks,
        finishedBooks,
        totalSeconds,
        longestStreak,
        avgDailySeconds,
        last30Days,
        heatmapData,
        goals,
        goalsForm: {
          dailyWords: goals.dailyWords ? String(goals.dailyWords) : "",
          dailyMinutes: goals.dailyMinutes ? String(goals.dailyMinutes) : "",
          yearlyBooks: goals.yearlyBooks ? String(goals.yearlyBooks) : "",
        },
        manualStats: loadManualStats(),
        isLoading: false,
      },
      () => {
        this.refreshDerived();
        this.loadBookNames();
      }
    );
  };

  /**
   * Recompute the derived views: ranking (B6), hour distribution (B5),
   * today's goal progress (B1), yearly months (B7), years list.
   * Never throws — failures keep the previous values.
   */
  refreshDerived = () => {
    try {
      const { allDayEntries, manualStats, maxPageMinutes, year } = this.state;
      const maxPageSec = (parseInt(maxPageMinutes || "0", 10) || 0) * 60;
      const yearEntries: (DayStatEntry & { date: string })[] = [];
      for (const [date, entries] of Object.entries(allDayEntries || {})) {
        for (const e of capEntrySeconds(entries || [], maxPageSec)) {
          yearEntries.push({ ...e, date });
        }
      }
      for (const m of manualStats || []) {
        yearEntries.push({
          seconds: m.seconds,
          words: m.words,
          bookKey: m.bookKey,
          date: m.date,
        });
      }
      // B8: filter the per-book views by shelf/book name text
      const filter = (this.state.filterText || "").trim().toLowerCase();
      let ranking = aggregateByBook(yearEntries, 20);
      const names = this.state.bookNameMap || {};
      if (filter) {
        ranking = ranking.filter((item) => {
          if (item.bookKey === "(unknown)") {
            return false;
          }
          const label = (names[item.bookKey] || item.bookKey).toLowerCase();
          return label.includes(filter);
        });
      }
      ranking = ranking.slice(0, 10);
      // The engine's `readingStats` map carries no hour, so the hourly
      // distribution comes from the LOCAL-ONLY hour store the reader fills
      // in (src/utils/stats/hourStatsUtil.ts). Until the reader has been
      // used with this build, the store is empty and the tab says so.
      const hourly = aggregateHourStats(loadHourStats());
      const todayKey = this.dateToKey(new Date());
      let tSec = 0;
      let tWords = 0;
      for (const e of capEntrySeconds(
        (allDayEntries || {})[todayKey] || [],
        maxPageSec
      )) {
        tSec += e.seconds;
        tWords += e.words || 0;
      }
      for (const m of manualStats || []) {
        if (m.date === todayKey) {
          tSec += m.seconds;
          tWords += m.words;
        }
      }
      const monthNames = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
      ];
      const months = yearlyMonthlySeconds(
        yearEntries,
        year,
        (e) => (e as DayStatEntry & { date: string }).date
      );
      const yearlyMonths = months.map((seconds, i) => ({
        month: monthNames[i],
        minutes: Math.round(seconds / 60),
      }));
      const yearSet = new Set<number>([new Date().getFullYear()]);
      const collectYear = (s: string) => {
        const m = /^(\d{4})-/.exec(s);
        if (m) {
          yearSet.add(parseInt(m[1], 10));
        }
      };
      Object.keys(allDayEntries || {}).forEach(collectYear);
      (manualStats || []).forEach((m) => collectYear(m.date));
      this.setState({
        ranking,
        hourly,
        hasHourData: hourly.some((v) => v > 0),
        yearlyMonths,
        today: { seconds: tSec, words: tWords },
        years: Array.from(yearSet).sort((a, b) => b - a),
      });
    } catch {
      // keep previous derived values
    }
  };

  /** Resolve display names for the ranked books (B6/B8). */
  loadBookNames = async () => {
    try {
      const keys = Array.from(
        new Set(
          (this.state.ranking || [])
            .map((r) => r.bookKey)
            .filter((k) => k !== "(unknown)")
        )
      );
      const map: Record<string, string> = {};
      for (const key of keys) {
        try {
          const book = await DatabaseService.getRecord(key, "books");
          if (book && (book as { name?: string }).name) {
            map[key] = (book as { name: string }).name;
          }
        } catch {
          // unknown book — keep raw key
        }
      }
      if (Object.keys(map).length > 0) {
        this.setState(
          { bookNameMap: { ...this.state.bookNameMap, ...map } },
          () => this.refreshDerived()
        );
      }
    } catch {
      // non-fatal
    }
  };

  // ── goals & manual stats handlers (B1-B4) ────────────────────────────────
  handleSaveGoals = () => {
    const { goalsForm } = this.state;
    const parsed = (v: string) => parseInt(v, 10);
    const goals = normalizeGoals({
      dailyWords: Number.isFinite(parsed(goalsForm.dailyWords))
        ? parsed(goalsForm.dailyWords)
        : 0,
      dailyMinutes: Number.isFinite(parsed(goalsForm.dailyMinutes))
        ? parsed(goalsForm.dailyMinutes)
        : 0,
      yearlyBooks: Number.isFinite(parsed(goalsForm.yearlyBooks))
        ? parsed(goalsForm.yearlyBooks)
        : 0,
    });
    saveGoals(goals);
    this.setState({ goals }, () => this.refreshDerived());
    toast.success(this.props.t("Change successful"));
  };

  handleAddManual = () => {
    const { manualForm } = this.state;
    try {
      const seconds = parseInt(manualForm.seconds, 10);
      const words = manualForm.words ? parseInt(manualForm.words, 10) : 0;
      const list = addManualStat({
        date: manualForm.date,
        seconds,
        words,
      });
      this.setState(
        {
          manualStats: list,
          manualForm: { date: manualForm.date, seconds: "", words: "" },
        },
        () => this.refreshDerived()
      );
      toast.success(this.props.t("Added"));
    } catch (err) {
      toast.error(this.manualEntryErrorMessage(err));
    }
  };

  /**
   * Map a validation failure to a translated message. The util throws a
   * [ManualEntryError] carrying a machine-readable code; anything else falls
   * back to a generic message.
   */
  manualEntryErrorMessage = (err: unknown): string => {
    if (err instanceof ManualEntryError) {
      return this.props.t(MANUAL_ENTRY_ERROR_KEYS[err.code]);
    }
    return this.props.t("Invalid input");
  };

  handleRemoveManual = (key: string) => {
    try {
      const list = removeManualStat(key);
      this.setState({ manualStats: list }, () => this.refreshDerived());
    } catch {
      toast.error(this.props.t("Remove failed"));
    }
  };

  handleSaveMaxPage = () => {
    const minutes = parseInt(this.state.maxPageMinutes || "0", 10);
    const saved = saveMaxPageTimeMinutes(
      Number.isFinite(minutes) ? minutes : 0
    );
    this.setState(
      { maxPageMinutes: saved ? String(saved) : "" },
      () => this.refreshDerived()
    );
    toast.success(this.props.t("Change successful"));
  };

  handleFilterChange = (text: string) => {
    this.setState({ filterText: text }, () => this.refreshDerived());
  };

  /**
   * B9: draw a poster from plain text lines and return it as a PNG Blob.
   *
   * A Blob (not a data URL) is what the download path should use: file-saver
   * sends a string through an XHR, which does not behave like a real file
   * download for a `data:` URL in Electron. The font export already uses
   * the Blob form — this keeps the two consistent.
   *
   * Throws in environments without a usable 2D context (callers show a
   * friendly toast).
   */
  renderPosterBlob = (lines: string[]): Promise<Blob> => {
    if (typeof document === "undefined") {
      throw new Error("Canvas unavailable");
    }
    const canvas = document.createElement("canvas");
    const W = 560;
    const lineH = 46;
    const H = 150 + lines.length * lineH;
    canvas.width = W;
    canvas.height = H;
    const ctx = canvas.getContext("2d");
    if (!ctx) {
      throw new Error("Canvas 2D context unavailable");
    }
    ctx.fillStyle = "#1f2430";
    ctx.fillRect(0, 0, W, H);
    ctx.fillStyle = "rgba(75,156,255,0.9)";
    ctx.fillRect(0, H - 4, W, 4);
    lines.forEach((line, i) => {
      ctx.fillStyle = i === 0 ? "#ffffff" : "rgba(255,255,255,0.85)";
      ctx.font =
        i === 0 ? "bold 30px system-ui, sans-serif" : "22px system-ui, sans-serif";
      ctx.fillText(line, 40, 90 + i * lineH);
    });
    ctx.fillStyle = "rgba(255,255,255,0.5)";
    ctx.font = "14px system-ui, sans-serif";
    ctx.fillText("Koodo Reader", 40, H - 20);
    return new Promise<Blob>((resolve, reject) => {
      canvas.toBlob((blob) => {
        if (blob) {
          resolve(blob);
        } else {
          reject(new Error("Canvas export failed"));
        }
      }, "image/png");
    });
  };

  handleShareGoalPoster = async () => {
    try {
      const { goals, today, allDayEntries } = this.state;
      const entries: (DayStatEntry & { date: string })[] = [];
      for (const [date, list] of Object.entries(allDayEntries || {})) {
        for (const e of list || []) {
          entries.push({ ...e, date });
        }
      }
      (this.state.manualStats || []).forEach((m) =>
        entries.push({
          seconds: m.seconds,
          words: m.words,
          bookKey: m.bookKey,
          date: m.date,
        })
      );
      const distinctBooks = countDistinctBooks(entries);
      const lines = buildGoalPosterLines(goals, today, distinctBooks);
      const blob = await this.renderPosterBlob(lines);
      saveAs(blob, "reading-goals-poster.png");
    } catch (err) {
      toast.error(
        err instanceof Error ? err.message : this.props.t("Share failed")
      );
    }
  };

  handleShareBookPoster = async () => {
    const top = (this.state.ranking || [])[0];
    if (!top || top.seconds <= 0) {
      toast.error(this.props.t("No book data to share"));
      return;
    }
    try {
      const name =
        (this.state.bookNameMap || {})[top.bookKey] || top.bookKey;
      const lines = buildBookPosterLines(name, {
        seconds: top.seconds,
        words: top.words,
      });
      const blob = await this.renderPosterBlob(lines);
      saveAs(blob, "book-stats-poster.png");
    } catch (err) {
      toast.error(
        err instanceof Error ? err.message : this.props.t("Share failed")
      );
    }
  };

  dateToKey(d: Date): string {
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, "0");
    const dd = String(d.getDate()).padStart(2, "0");
    return `${yyyy}-${mm}-${dd}`;
  }

  formatTime(seconds: number): string {
    if (seconds < 60) return `${seconds}s`;
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    if (h > 0) return `${h}h ${m}m`;
    return `${m}m`;
  }

  getHeatmapColor(seconds: number, isDark: boolean): string {
    if (seconds === 0)
      return isDark ? "rgba(255,255,255,0.07)" : "rgba(0,0,0,0.07)";
    if (seconds < 300) return isDark ? "#0e4429" : "#9be9a8";
    if (seconds < 900) return isDark ? "#006d32" : "#40c463";
    if (seconds < 1800) return isDark ? "#26a641" : "#30a14e";
    return isDark ? "#39d353" : "#216e39";
  }

  isDark(): boolean {
    const skin = ConfigService.getReaderConfig("appSkin");
    const isOSNight = ConfigService.getReaderConfig("isOSNight");
    return skin === "night" || (skin === "system" && isOSNight === "yes");
  }

  renderGoalProgress(isDark: boolean, _cardBg: string, textColor: string) {
    const { goals, today, battery } = this.state;
    const progress = goalProgress(today, goals);
    const hasAnyGoal =
      goals.dailyWords > 0 || goals.dailyMinutes > 0 || goals.yearlyBooks > 0;
    if (!hasAnyGoal) {
      return (
        <div style={{ color: textColor, opacity: 0.6, padding: "8px 0" }}>
          <Trans>Set a daily or yearly goal to track progress.</Trans>
        </div>
      );
    }
    const barTrack = isDark ? "rgba(255,255,255,0.08)" : "rgba(0,0,0,0.06)";
    return (
      <div className="stats-goal-progress" style={{ padding: "8px 0" }}>
        <div style={{ color: textColor, marginBottom: 8 }}>
          <Trans>Today</Trans>: {this.formatTime(today.seconds)}
          {today.words > 0
            ? ` · ${today.words} ${this.props.t("chars")}`
            : ""}
          {typeof battery === "number" ? ` · ${Math.round(battery)}%` : ""}
        </div>
        {goals.dailyMinutes > 0 && (
          <div
            className="stats-progress-bar"
            style={{ backgroundColor: barTrack }}
            title={`${Math.round(progress.secondsPct)}%`}
          >
            <div
              className="stats-progress-fill"
              style={{ width: `${progress.secondsPct}%`, background: "#4b9cff" }}
            />
          </div>
        )}
        {goals.dailyWords > 0 && (
          <>
            <div
              className="stats-progress-bar"
              style={{ backgroundColor: barTrack, marginTop: 6 }}
              title={`${Math.round(progress.wordsPct)}%`}
            >
              <div
                className="stats-progress-fill"
                style={{ width: `${progress.wordsPct}%`, background: "#40c463" }}
              />
            </div>
            {/*
              The reading engine records reading TIME per book per day, never a
              word count, so this goal can only advance through the manual
              entries. Saying so keeps an always-zero bar from looking like a
              broken progress meter.
            */}
            <div style={{ color: textColor, opacity: 0.55, fontSize: 12, marginTop: 4 }}>
              <Trans>
                Word counts come from manual entries only — the reader does not
                count words read.
              </Trans>
            </div>
          </>
        )}        {goals.yearlyBooks > 0 && (
          <div style={{ color: textColor, marginTop: 8 }}>
            <Trans>Yearly goal</Trans>: {this.state.finishedBooks}/
            {goals.yearlyBooks}{" "}
            {this.state.finishedBooks >= goals.yearlyBooks ? "✓" : ""}
          </div>
        )}
      </div>
    );
  }

  renderHeatmap() {
    const { heatmapData } = this.state;
    const isDark = this.isDark();

    // Build week columns: each column is 7 days (Sun–Sat)
    // We need to figure out which day of week the first date falls on
    const padded: ({ date: string; seconds: number } | null)[] = [
      ...heatmapData,
      ...Array((7 - (heatmapData.length % 7 || 7)) % 7).fill(null),
    ];

    // Split into columns of 7
    const weeks: ({ date: string; seconds: number } | null)[][] = [];
    for (let i = 0; i < padded.length; i += 7) {
      weeks.push(padded.slice(i, i + 7));
    }

    // Month labels
    const monthLabels: { label: string; colIndex: number }[] = [];
    weeks.forEach((week, wi) => {
      const firstVisibleCell = week.find((cell) => cell !== null);
      const monthStartCell =
        wi === 0
          ? firstVisibleCell
          : week.find((cell) => cell && new Date(cell.date).getDate() === 1);

      if (monthStartCell) {
        const d = new Date(monthStartCell.date);
        monthLabels.push({
          label: d.toLocaleString("default", { month: "short" }),
          colIndex: wi,
        });
      }
    });

    const weekDays = ["", "Mon", "", "Wed", "", "Fri", ""];
    const legendLevels = [0, 200, 600, 1200, 2400];

    return (
      <div>
        <div className="heatmap-layout">
          <div className="heatmap-side">
            <div className="heatmap-month-spacer" />
            <div className="heatmap-weekdays">
              {weekDays.map((d, i) => (
                <div key={i} className="heatmap-weekday-label">
                  {d}
                </div>
              ))}
            </div>
          </div>
          <div className="heatmap-main">
            <div className="heatmap-months">
              {weeks.map((_, wi) => {
                const label = monthLabels.find((ml) => ml.colIndex === wi);
                return (
                  <div key={wi} className="heatmap-month-label">
                    {label ? label.label : ""}
                  </div>
                );
              })}
            </div>
            <div className="heatmap-grid">
              {weeks.map((week, wi) => (
                <div key={wi} className="heatmap-col">
                  {week.map((cell, di) =>
                    cell === null ? (
                      <div
                        key={di}
                        className="heatmap-cell"
                        style={{ visibility: "hidden" }}
                      />
                    ) : (
                      <div
                        key={di}
                        className="heatmap-cell"
                        title={`${cell.date}: ${this.formatTime(cell.seconds)}`}
                        style={{
                          backgroundColor: this.getHeatmapColor(
                            cell.seconds,
                            isDark
                          ),
                        }}
                      />
                    )
                  )}
                </div>
              ))}
            </div>
          </div>
        </div>
        {/* Legend */}
        <div className="heatmap-legend">
          {legendLevels.map((lvl) => (
            <div
              key={lvl}
              className="heatmap-legend-cell"
              style={{
                backgroundColor: this.getHeatmapColor(lvl, isDark),
              }}
            />
          ))}
        </div>
      </div>
    );
  }

  render() {
    const {
      totalBooks,
      totalSeconds,
      longestStreak,
      avgDailySeconds,
      last30Days,
      chartTab,
      isLoading,
      viewTab,
      goals,
      goalsForm,
      today,
      battery,
      manualStats,
      manualForm,
      maxPageMinutes,
      filterText,
      ranking,
      hourly,
      hasHourData,
      yearlyMonths,
      years,
      year,
      bookNameMap,
    } = this.state;

    const isDark = this.isDark();
    const bgColor = isDark ? "#1e1e1e" : "#f5f5f5";
    const cardBg = isDark ? "#2a2a2a" : "#ffffff";
    const textColor = isDark ? "#e0e0e0" : "#333333";
    const chartColor = textColor;
    const lineChartColor = isDark ? "#ffb066" : "#ff6b1a";
    const lineChartGradientId = `stats-line-gradient-${
      isDark ? "dark" : "light"
    }`;
    const gridColor = isDark ? "rgba(255,255,255,0.08)" : "rgba(0,0,0,0.08)";
    const tabActiveBg = isDark ? "#3a3a3a" : "#333";
    const tabActiveColor = isDark ? "#fff" : "#fff";
    const tabInactiveBg = isDark
      ? "rgba(255,255,255,0.06)"
      : "rgba(0,0,0,0.06)";
    const tabInactiveColor = isDark
      ? "rgba(255,255,255,0.6)"
      : "rgba(0,0,0,0.5)";

    const cards = [
      {
        icon: "icon-bookshelf",
        value: totalBooks,
        label: this.props.t("Books read"),
        color: textColor,
        iconSize: 24,
      },
      {
        icon: "icon-grid",
        value: this.formatTime(totalSeconds),
        label: this.props.t("Total reading time"),
        color: textColor,
        iconSize: 22,
      },
      {
        icon: "icon-clock",
        value: `${longestStreak}`,
        label: this.props.t("Reading streak (days)"),
        color: textColor,
        iconSize: 28,
      },
      {
        icon: "icon-chart",
        value: this.formatTime(avgDailySeconds),
        label: this.props.t("Daily average"),
        color: textColor,
        iconSize: 22,
      },
    ];

    const chartData = last30Days.map((d) => ({
      ...d,
      minutes: Math.round(d.seconds / 60),
    }));

    return (
      <div
        className="stats-page"
        style={{ backgroundColor: bgColor, color: textColor }}
      >
        {/* Close button */}
        <div
          className="stats-close-btn"
          style={{ color: textColor }}
          onClick={() => {
            this.props.history.push("/manager/home");
          }}
        >
          <span className="icon-close"></span>
        </div>

        {/* Title */}
        <div className="stats-title">
          <Trans>Reading Stats</Trans>
        </div>

        {/* Cards */}
        {!isLoading && (
          <>
            <div className="stats-cards">
              {cards.map((card, i) => (
                <div
                  key={i}
                  className="stats-card"
                  style={{ backgroundColor: cardBg }}
                >
                  <div
                    className="stats-card-icon"
                    style={{ color: card.color, fontSize: card.iconSize }}
                  >
                    <span className={card.icon}></span>
                  </div>
                  <div
                    className="stats-card-value"
                    style={{ color: textColor }}
                  >
                    {card.value}
                  </div>
                  <div className="stats-card-label">{card.label}</div>
                </div>
              ))}
            </div>

            {/* Goals & manual stats (B1-B4, B8, B9) */}
            <div
              className="stats-goals-wrapper"
              style={{ backgroundColor: cardBg }}
            >
              <div className="stats-section-title" style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <Trans>Reading goals</Trans>
                <button
                  className="stats-chart-tab"
                  style={{ backgroundColor: tabActiveBg, color: tabActiveColor }}
                  onClick={this.handleShareGoalPoster}
                >
                  <Trans>Share poster</Trans>
                </button>
              </div>
              {this.renderGoalProgress(isDark, cardBg, textColor)}
              <div className="stats-goals-inputs">
                <input
                  className="stats-goal-input"
                  type="number"
                  min="0"
                  placeholder={this.props.t("Daily words goal")}
                  value={goalsForm.dailyWords}
                  onChange={(e) =>
                    this.setState({
                      goalsForm: { ...goalsForm, dailyWords: e.target.value },
                    })
                  }
                  style={{ backgroundColor: bgColor, color: textColor }}
                />
                <input
                  className="stats-goal-input"
                  type="number"
                  min="0"
                  placeholder={this.props.t("Daily minutes goal")}
                  value={goalsForm.dailyMinutes}
                  onChange={(e) =>
                    this.setState({
                      goalsForm: { ...goalsForm, dailyMinutes: e.target.value },
                    })
                  }
                  style={{ backgroundColor: bgColor, color: textColor }}
                />
                <input
                  className="stats-goal-input"
                  type="number"
                  min="0"
                  placeholder={this.props.t("Yearly books goal")}
                  value={goalsForm.yearlyBooks}
                  onChange={(e) =>
                    this.setState({
                      goalsForm: { ...goalsForm, yearlyBooks: e.target.value },
                    })
                  }
                  style={{ backgroundColor: bgColor, color: textColor }}
                />
                <button
                  className="stats-chart-tab"
                  style={{ backgroundColor: tabActiveBg, color: tabActiveColor, whiteSpace: "nowrap" }}
                  onClick={this.handleSaveGoals}
                >
                  <Trans>Save goals</Trans>
                </button>
              </div>
              <div className="stats-goals-inputs" style={{ marginTop: 8 }}>
                <input
                  className="stats-goal-input"
                  type="number"
                  min="0"
                  placeholder={this.props.t("Max minutes per recorded page (0 = off)")}
                  value={maxPageMinutes}
                  onChange={(e) =>
                    this.setState({ maxPageMinutes: e.target.value })
                  }
                  style={{ backgroundColor: bgColor, color: textColor, flex: 1 }}
                />
                <button
                  className="stats-chart-tab"
                  style={{ backgroundColor: tabInactiveBg, color: tabInactiveColor, whiteSpace: "nowrap" }}
                  onClick={this.handleSaveMaxPage}
                >
                  <Trans>Apply</Trans>
                </button>
              </div>
            </div>

            {/* Manual entry (B3) */}
            <div
              className="stats-manual-wrapper"
              style={{ backgroundColor: cardBg }}
            >
              <div className="stats-section-title">
                <Trans>Manual entry</Trans>
              </div>
              <div className="stats-goals-inputs">
                <input
                  className="stats-goal-input"
                  type="date"
                  value={manualForm.date}
                  onChange={(e) =>
                    this.setState({
                      manualForm: { ...manualForm, date: e.target.value },
                    })
                  }
                  style={{ backgroundColor: bgColor, color: textColor }}
                />
                <input
                  className="stats-goal-input"
                  type="number"
                  min="1"
                  max="86400"
                  placeholder={this.props.t("Seconds")}
                  value={manualForm.seconds}
                  onChange={(e) =>
                    this.setState({
                      manualForm: { ...manualForm, seconds: e.target.value },
                    })
                  }
                  style={{ backgroundColor: bgColor, color: textColor }}
                />
                <input
                  className="stats-goal-input"
                  type="number"
                  min="0"
                  placeholder={this.props.t("Words")}
                  value={manualForm.words}
                  onChange={(e) =>
                    this.setState({
                      manualForm: { ...manualForm, words: e.target.value },
                    })
                  }
                  style={{ backgroundColor: bgColor, color: textColor }}
                />
                <button
                  className="stats-chart-tab"
                  style={{ backgroundColor: tabActiveBg, color: tabActiveColor, whiteSpace: "nowrap" }}
                  onClick={this.handleAddManual}
                >
                  <Trans>Add</Trans>
                </button>
              </div>
              {this.state.manualStats.slice(-5).reverse().map((entry) => (
                <div
                  key={entry.key}
                  className="stats-manual-row"
                  style={{ color: textColor }}
                >
                  <span>
                    {entry.date} · {this.formatTime(entry.seconds)}
                    {entry.words ? ` · ${entry.words}${this.props.t("chars")}` : ""}
                  </span>
                  <button
                    className="stats-chart-tab"
                    style={{
                      backgroundColor: tabInactiveBg,
                      color: tabInactiveColor,
                    }}
                    onClick={() => this.handleRemoveManual(entry.key)}
                  >
                    <Trans>Remove</Trans>
                  </button>
                </div>
              ))}
            </div>

            {/* View tabs (B5-B7) */}
            <div className="stats-chart-tabs" style={{ marginTop: 12 }}>
              {(
                [
                  ["trend", this.props.t("Trend")],
                  ["ranking", this.props.t("Ranking")],
                  ["hourly", this.props.t("Hourly")],
                  ["year", this.props.t("Year")],
                ] as [typeof viewTab, string][]
              ).map(([tab, label]) => (
                <button
                  key={tab}
                  className="stats-chart-tab"
                  style={{
                    backgroundColor:
                      viewTab === tab ? tabActiveBg : tabInactiveBg,
                    color: viewTab === tab ? tabActiveColor : tabInactiveColor,
                  }}
                  onClick={() =>
                    this.setState({ viewTab: tab }, () =>
                      tab === "year"
                        ? this.refreshDerived()
                        : null
                    )
                  }
                >
                  {label}
                </button>
              ))}
            </div>

            {/* Trend view: 30-day chart + annual heatmap */}
            {viewTab === "trend" && (
              <>
            {/* Chart */}
            <div
              className="stats-chart-wrapper"
              style={{ backgroundColor: cardBg }}
            >
              <div className="stats-section-title">
                <Trans>Last 30 Days</Trans>
              </div>
              <div className="stats-chart-tabs">
                <button
                  className="stats-chart-tab"
                  style={{
                    backgroundColor:
                      chartTab === "bar" ? tabActiveBg : tabInactiveBg,
                    color:
                      chartTab === "bar" ? tabActiveColor : tabInactiveColor,
                  }}
                  onClick={() => this.setState({ chartTab: "bar" })}
                >
                  <Trans>Bar Chart</Trans>
                </button>
                <button
                  className="stats-chart-tab"
                  style={{
                    backgroundColor:
                      chartTab === "line" ? tabActiveBg : tabInactiveBg,
                    color:
                      chartTab === "line" ? tabActiveColor : tabInactiveColor,
                  }}
                  onClick={() => this.setState({ chartTab: "line" })}
                >
                  <Trans>Line Chart</Trans>
                </button>
              </div>

              <ResponsiveContainer width="100%" height={220}>
                {chartTab === "bar" ? (
                  <BarChart
                    data={chartData}
                    margin={{ top: 5, right: 10, left: -10, bottom: 5 }}
                  >
                    <CartesianGrid
                      strokeDasharray="3 3"
                      stroke={gridColor}
                      vertical={false}
                    />
                    <XAxis
                      dataKey="date"
                      tick={{ fontSize: 11, fill: textColor, opacity: 0.5 }}
                      tickLine={false}
                      axisLine={false}
                      interval={4}
                    />
                    <YAxis
                      tick={{ fontSize: 11, fill: textColor, opacity: 0.5 }}
                      tickLine={false}
                      axisLine={false}
                      tickFormatter={(v) => `${v}m`}
                    />
                    <Tooltip
                      formatter={(value: TooltipValue) => [
                        `${value ?? 0} min`,
                      ]}
                      contentStyle={{
                        backgroundColor: cardBg,
                        border: "none",
                        borderRadius: 8,
                        color: textColor,
                        fontSize: 12,
                      }}
                      cursor={{
                        fill: isDark
                          ? "rgba(255,255,255,0.05)"
                          : "rgba(0,0,0,0.05)",
                      }}
                    />
                    <Bar
                      dataKey="minutes"
                      fill={chartColor}
                      radius={[4, 4, 0, 0]}
                    />
                  </BarChart>
                ) : (
                  <AreaChart
                    data={chartData}
                    margin={{ top: 5, right: 10, left: -10, bottom: 5 }}
                  >
                    <defs>
                      <linearGradient
                        id={lineChartGradientId}
                        x1="0"
                        y1="0"
                        x2="0"
                        y2="1"
                      >
                        <stop
                          offset="0%"
                          stopColor={lineChartColor}
                          stopOpacity={0.32}
                        />
                        <stop
                          offset="70%"
                          stopColor={lineChartColor}
                          stopOpacity={0.1}
                        />
                        <stop
                          offset="100%"
                          stopColor={lineChartColor}
                          stopOpacity={0.02}
                        />
                      </linearGradient>
                    </defs>
                    <CartesianGrid
                      strokeDasharray="3 3"
                      stroke={gridColor}
                      vertical={false}
                    />
                    <XAxis
                      dataKey="date"
                      tick={{ fontSize: 11, fill: textColor, opacity: 0.5 }}
                      tickLine={false}
                      axisLine={false}
                      interval={4}
                    />
                    <YAxis
                      tick={{ fontSize: 11, fill: textColor, opacity: 0.5 }}
                      tickLine={false}
                      axisLine={false}
                      tickFormatter={(v) => `${v}m`}
                    />
                    <Tooltip
                      formatter={(value: TooltipValue) => [
                        `${value ?? 0} min`,
                      ]}
                      contentStyle={{
                        backgroundColor: cardBg,
                        border: "none",
                        borderRadius: 8,
                        color: textColor,
                        fontSize: 12,
                      }}
                      cursor={{
                        fill: isDark
                          ? "rgba(255,255,255,0.05)"
                          : "rgba(0,0,0,0.05)",
                      }}
                    />
                    <Area
                      type="monotone"
                      dataKey="minutes"
                      stroke="none"
                      fill={`url(#${lineChartGradientId})`}
                    />
                    <Line
                      type="monotone"
                      dataKey="minutes"
                      stroke={lineChartColor}
                      strokeWidth={2.5}
                      dot={false}
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      activeDot={{ r: 5 }}
                    />
                  </AreaChart>
                )}
              </ResponsiveContainer>
            </div>

            {/* Heatmap */}
            <div
              className="stats-heatmap-wrapper"
              style={{ backgroundColor: cardBg }}
            >
              <div className="stats-section-title">
                <Trans>Reading Activity</Trans>
              </div>
              {this.renderHeatmap()}
            </div>
              </>
            )}

            {/* Ranking view (B6, B8, B9) */}
            {viewTab === "ranking" && (
              <div
                className="stats-rank-wrapper"
                style={{ backgroundColor: cardBg }}
              >
                <div
                  className="stats-section-title"
                  style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}
                >
                  <Trans>Reading time ranking</Trans>
                  <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                    <input
                      className="stats-goal-input"
                      type="text"
                      placeholder={this.props.t("Filter by book or tag")}
                      value={filterText}
                      onChange={(e) => this.handleFilterChange(e.target.value)}
                      style={{
                        backgroundColor: bgColor,
                        color: textColor,
                        width: 180,
                      }}
                    />
                    <button
                      className="stats-chart-tab"
                      style={{ backgroundColor: tabActiveBg, color: tabActiveColor, whiteSpace: "nowrap" }}
                      onClick={this.handleShareBookPoster}
                    >
                      <Trans>Share top</Trans>
                    </button>
                  </div>
                </div>
                {ranking.length === 0 ? (
                  <div style={{ color: tabInactiveColor, padding: "12px 0" }}>
                    <Trans>No per-book data yet</Trans>
                  </div>
                ) : (
                  ranking.map((item, i) => (
                    <div
                      key={item.bookKey}
                      className="stats-rank-row"
                      style={{ color: textColor }}
                    >
                      <span className="stats-rank-index">{i + 1}</span>
                      <span className="stats-rank-name">
                        {(bookNameMap || {})[item.bookKey] || item.bookKey}
                      </span>
                      <span className="stats-rank-value">
                        {this.formatTime(item.seconds)}
                        {item.words ? ` · ${item.words}` : ""}
                      </span>
                    </div>
                  ))
                )}
              </div>
            )}

            {/* Hourly view (B5) */}
            {viewTab === "hourly" && (
              <div
                className="stats-hourly-wrapper"
                style={{ backgroundColor: cardBg }}
              >
                <div className="stats-section-title">
                  <Trans>Reading time distribution</Trans>
                </div>
                {!hasHourData ? (
                  <div style={{ color: tabInactiveColor, padding: "12px 0" }}>
                    <Trans>
                      Hour-level data is not available for the recorded
                      sessions
                    </Trans>
                  </div>
                ) : (
                  <ResponsiveContainer width="100%" height={220}>
                    <BarChart
                      data={hourly.map((minutes, hour) => ({
                        hour: `${hour}`,
                        minutes: Math.round((minutes || 0) / 60),
                      }))}
                      margin={{ top: 5, right: 10, left: -10, bottom: 5 }}
                    >
                      <CartesianGrid
                        strokeDasharray="3 3"
                        stroke={gridColor}
                        vertical={false}
                      />
                      <XAxis
                        dataKey="hour"
                        tick={{ fontSize: 11, fill: textColor, opacity: 0.5 }}
                        tickLine={false}
                        axisLine={false}
                        interval={1}
                      />
                      <YAxis
                        tick={{ fontSize: 11, fill: textColor, opacity: 0.5 }}
                        tickLine={false}
                        axisLine={false}
                        tickFormatter={(v) => `${v}m`}
                      />
                      <Tooltip
                        formatter={(value: TooltipValue) => [
                        `${value ?? 0} min`,
                      ]}
                        contentStyle={{
                          backgroundColor: cardBg,
                          border: "none",
                          borderRadius: 8,
                          color: textColor,
                          fontSize: 12,
                        }}
                        cursor={{
                          fill: isDark
                            ? "rgba(255,255,255,0.05)"
                            : "rgba(0,0,0,0.05)",
                        }}
                      />
                      <Bar
                        dataKey="minutes"
                        fill={lineChartColor}
                        radius={[4, 4, 0, 0]}
                      />
                    </BarChart>
                  </ResponsiveContainer>
                )}
              </div>
            )}

            {/* Year view (B7) */}
            {viewTab === "year" && (
              <div
                className="stats-year-wrapper"
                style={{ backgroundColor: cardBg }}
              >
                <div
                  className="stats-section-title"
                  style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}
                >
                  <Trans>Yearly overview</Trans>
                  <select
                    className="lang-setting-dropdown"
                    value={year}
                    onChange={(e) => {
                      const nextYear = parseInt(e.target.value, 10);
                      this.setState({ year: nextYear }, () =>
                        this.refreshDerived()
                      );
                    }}
                    style={{
                      backgroundColor: bgColor,
                      color: textColor,
                    }}
                  >
                    {years.map((y) => (
                      <option key={y} value={y} className="lang-setting-option">
                        {y}
                      </option>
                    ))}
                  </select>
                </div>
                <ResponsiveContainer width="100%" height={220}>
                  <BarChart
                    data={yearlyMonths}
                    margin={{ top: 5, right: 10, left: -10, bottom: 5 }}
                  >
                    <CartesianGrid
                      strokeDasharray="3 3"
                      stroke={gridColor}
                      vertical={false}
                    />
                    <XAxis
                      dataKey="month"
                      tick={{ fontSize: 11, fill: textColor, opacity: 0.5 }}
                      tickLine={false}
                      axisLine={false}
                    />
                    <YAxis
                      tick={{ fontSize: 11, fill: textColor, opacity: 0.5 }}
                      tickLine={false}
                      axisLine={false}
                      tickFormatter={(v) => `${v}m`}
                    />
                    <Tooltip
                      formatter={(value: TooltipValue) => [
                        `${value ?? 0} min`,
                      ]}
                      contentStyle={{
                        backgroundColor: cardBg,
                        border: "none",
                        borderRadius: 8,
                        color: textColor,
                        fontSize: 12,
                      }}
                      cursor={{
                        fill: isDark
                          ? "rgba(255,255,255,0.05)"
                          : "rgba(0,0,0,0.05)",
                      }}
                    />
                    <Bar
                      dataKey="minutes"
                      fill={lineChartColor}
                      radius={[4, 4, 0, 0]}
                    />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </>
        )}
      </div>
    );
  }
}

export default withRouter(Stats as any);
