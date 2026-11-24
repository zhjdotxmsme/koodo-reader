import { RouteComponentProps } from "react-router";
import {
  ReadingGoals,
  ManualStatEntry,
  BookRankEntry,
} from "../../utils/stats/statsUtil";

export interface StatsProps extends RouteComponentProps<any> {
  t: (title: string) => string;
}

export interface StatsState {
  totalBooks: number;
  /** Books the reader marked as finished (recordLocation.percentage >= 1). */
  finishedBooks: number;
  totalSeconds: number;
  longestStreak: number;
  avgDailySeconds: number;
  last30Days: { date: string; seconds: number }[];
  heatmapData: { date: string; seconds: number }[];
  chartTab: "line" | "bar";
  isLoading: boolean;
  // goals & manual stats (B1-B3)
  goals: ReadingGoals;
  goalsForm: {
    dailyWords: string;
    dailyMinutes: string;
    yearlyBooks: string;
  };
  manualStats: ManualStatEntry[];
  manualForm: { date: string; seconds: string; words: string };
  // max per-page recorded time in minutes (B4; "" = no cap)
  maxPageMinutes: string;
  // multi-view (B5-B7)
  viewTab: "trend" | "ranking" | "hourly" | "year";
  year: number;
  years: number[];
  // tag/shelf filter (B8)
  filterText: string;
  // derived data
  ranking: BookRankEntry[];
  hourly: number[];
  hasHourData: boolean;
  yearlyMonths: { month: string; minutes: number }[];
  today: { seconds: number; words: number };
  bookNameMap: Record<string, string>;
  allDayEntries: Record<string, { seconds: number; words?: number; bookKey?: string; hour?: number }[]>;
  battery: number | null;
}
