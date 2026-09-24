#!/usr/bin/env bash
# ci-macro-benchmark.sh — P8 CI entry point for the Android performance budgets.
#
# Covers the four acceptance metrics of docs/android-native-migration.md §8 and
# the P8 checklist (docs/p8-rollout-checklist.md §5):
#   1. cold start P90  <= 1.5 s   (Macrobenchmark StartupTimingMetric + am start -W cross-check)
#   2. open EPUB       <= 1.2 s   (trace section, opt-in until the reader emits it)
#   3. page turn P90   <  50 ms   (Macrobenchmark FrameTimingMetric P90)
#   4. memory peak     <  350 MB  (dumpsys meminfo TOTAL PSS sampled during a read-through)
#
# Usage:
#   bash scripts/ci-macro-benchmark.sh [options]
#     --serial <id>          adb device serial (default: the only attached device)
#     --out <dir>            output dir                        (default out/macrobenchmark)
#     --budget <file>        budget JSON                       (default docs/android-baseline-after.json)
#     --book-title <title>   fixture title on the bookshelf    (-e benchmarkBookTitle)
#     --iterations <n>       Macrobenchmark iterations         (default 10)
#     --cold-runs <n>        am start -W samples for the P90    (default 10)
#     --open-epub-trace      enable the openBook trace-section benchmark
#     --skip-build           reuse the APKs already built
#     --skip-cold-start      skip the am start -W cross-check
#     --strict               missing data is a failure (default: warning)
#     --allow-no-device      exit 0 with SKIP when no device is attached
#     --dry-run              print the plan and exit
#
# Exit codes: 0 pass (or skipped), 1 budget/measurement failure, 2 environment problem.
#
# NOTE for reviewers: the budget numbers live in docs/android-baseline-after.json —
# change them there, not here.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"

ADB_BIN="${ADB:-adb}"
GRADLE_BIN="${GRADLE:-gradle}"
NODE_BIN="${NODE:-node}"

PACKAGE="com.koodoreader.reader"
RUNNER="$PACKAGE.benchmarks/androidx.test.runner.AndroidJUnitRunner"
TARGET="${TARGET:-native}"          # -Ptarget passed to Gradle
SERIAL="${ANDROID_SERIAL:-}"
OUT="$ROOT/out/macrobenchmark"
BUDGET="$ROOT/docs/android-baseline-after.json"
BOOK_TITLE="${BOOK_TITLE:-benchmark-300ch.epub}"
ITERATIONS=10
COLD_RUNS=10
SKIP_BUILD=0
SKIP_COLD_START=0
STRICT=0
ALLOW_NO_DEVICE=0
DRY_RUN=0
OPEN_EPUB_TRACE="${OPEN_EPUB_TRACE:-false}"

log()  { printf '[macrobenchmark] %s\n' "$*"; }
warn() { printf '[macrobenchmark] WARN: %s\n' "$*" >&2; }
die()  { printf '[macrobenchmark] ERROR: %s\n' "$*" >&2; exit 2; }

usage() {
  cat <<'EOF'
ci-macro-benchmark.sh — P8 CI entry point for the Android performance budgets.

Usage:
  bash scripts/ci-macro-benchmark.sh [options]
    --serial <id>          adb device serial (default: the only attached device)
    --out <dir>            output dir                        (default out/macrobenchmark)
    --budget <file>        budget JSON                       (default docs/android-baseline-after.json)
    --book-title <title>   fixture title on the bookshelf    (-e benchmarkBookTitle)
    --iterations <n>       Macrobenchmark iterations         (default 10)
    --cold-runs <n>        am start -W samples for the P90    (default 10)
    --open-epub-trace      enable the openBook trace-section benchmark
    --skip-build           reuse the APKs already built
    --skip-cold-start      skip the am start -W cross-check
    --strict               missing data is a failure (default: warning)
    --allow-no-device      exit 0 with SKIP when no device is attached
    --dry-run              print the plan and exit

Exit codes: 0 pass (or skipped), 1 budget/measurement failure, 2 environment problem.

NOTE for reviewers: the budget numbers live in docs/android-baseline-after.json —
change them there, not here.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)          SERIAL="${2:?}"; shift 2 ;;
    --out)             OUT="${2:?}"; shift 2 ;;
    --budget)          BUDGET="${2:?}"; shift 2 ;;
    --book-title)      BOOK_TITLE="${2:?}"; shift 2 ;;
    --iterations)      ITERATIONS="${2:?}"; shift 2 ;;
    --cold-runs)       COLD_RUNS="${2:?}"; shift 2 ;;
    --open-epub-trace) OPEN_EPUB_TRACE=true; shift ;;
    --skip-build)      SKIP_BUILD=1; shift ;;
    --skip-cold-start) SKIP_COLD_START=1; shift ;;
    --strict)          STRICT=1; shift ;;
    --allow-no-device) ALLOW_NO_DEVICE=1; shift ;;
    --dry-run)         DRY_RUN=1; shift ;;
    -h|--help)         usage; exit 0 ;;
    *)                 die "unknown argument: $1 (try --help)" ;;
  esac
done

adb() {
  if [[ -n "$SERIAL" ]]; then "$ADB_BIN" -s "$SERIAL" "$@"; else "$ADB_BIN" "$@"; fi
}

# ---------------------------------------------------------------- environment

command -v "$ADB_BIN"  >/dev/null 2>&1 || die "adb not found (set ADB=/path/to/adb)"
command -v "$NODE_BIN" >/dev/null 2>&1 || die "node not found (set NODE=/path/to/node)"
[[ -f "$BUDGET" ]] || die "budget file not found: $BUDGET"

if [[ -z "$SERIAL" ]]; then
  SERIAL="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi

log "device   : ${SERIAL:-<none>}"
log "out      : $OUT"
log "budget   : $BUDGET"
log "target   : -Ptarget=$TARGET · iterations=$ITERATIONS · cold-run samples=$COLD_RUNS"

if [[ $DRY_RUN -eq 1 ]]; then
  log "dry run — plan:"
  log "  1. (cd android && $GRADLE_BIN :app:assembleBenchmark :benchmarks:assembleBenchmark -Ptarget=$TARGET)"
  log "  2. adb -s ${SERIAL:-<device>} install -r -t <app-benchmark.apk> <benchmarks-benchmark.apk>"
  log "  3. adb -s ${SERIAL:-<device>} shell am instrument -w -e benchmarkBookTitle '$BOOK_TITLE' $RUNNER"
  log "  4. node scripts/measure-cold-start.js --runs $COLD_RUNS --package $PACKAGE"
  log "  5. compare against $(basename "$BUDGET") into $OUT"
  exit 0
fi

if [[ -z "$SERIAL" ]]; then
  if [[ $ALLOW_NO_DEVICE -eq 1 ]]; then
    log "SKIP: no adb device attached (--allow-no-device)."
    exit 0
  fi
  die "no adb device/emulator attached. Start one, or pass --allow-no-device to skip in CI."
fi

mkdir -p "$OUT"

# ---------------------------------------------------------------------- build

if [[ $SKIP_BUILD -eq 1 ]]; then
  log "build: skipped (--skip-build)"
else
  log "build: :app:assembleBenchmark :benchmarks:assembleBenchmark -Ptarget=$TARGET"
  ( cd "$ANDROID_DIR" && "$GRADLE_BIN" \
      :app:assembleBenchmark \
      :benchmarks:assembleBenchmark \
      "-Ptarget=$TARGET" \
      --no-daemon --console=plain )
fi

APP_APK="$(find "$ANDROID_DIR/app/build/outputs/apk" -name '*benchmark*.apk' -type f | head -n 1 || true)"
BENCH_APK="$(find "$ANDROID_DIR/benchmarks/build/outputs/apk" -name '*.apk' -type f | head -n 1 || true)"
[[ -n "$APP_APK"   && -f "$APP_APK"   ]] || die "app benchmark APK not found — run without --skip-build"
[[ -n "$BENCH_APK" && -f "$BENCH_APK" ]] || die "benchmarks APK not found — is ':benchmarks' in settings.gradle?"
log "app apk  : $APP_APK"
log "bench apk: $BENCH_APK"

# -------------------------------------------------------------------- install

log "install: app + benchmarks APK"
adb install -r -t "$APP_APK"   >/dev/null
adb install -r -t "$BENCH_APK" >/dev/null

log "device setup: animations off, stay-awake on"
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global "$scale" 0 >/dev/null 2>&1 || true
done
adb shell svc power stayon true >/dev/null 2>&1 || true

# ------------------------------------------------------------- macrobenchmark

log "run: Macrobenchmark suite (this takes several minutes)"
set +e
adb shell am instrument -w \
  -e benchmarkBookTitle "$BOOK_TITLE" \
  -e benchmarkFailureOnBudget "${BENCHMARK_FAIL_ON_BUDGET:-false}" \
  -e benchmarkOpenEpubTrace "$OPEN_EPUB_TRACE" \
  "$RUNNER" | tee "$OUT/instrumentation.txt"
INSTRUMENT_STATUS=${PIPESTATUS[0]}
set -e
if [[ $INSTRUMENT_STATUS -ne 0 ]]; then
  warn "instrumentation exited $INSTRUMENT_STATUS — see $OUT/instrumentation.txt"
fi

# Macrobenchmark writes its JSON + traces to the *target* app's media dir.
RESULT_DIR="/sdcard/Android/media/$PACKAGE/additional_test_output"
mkdir -p "$OUT/benchmarkData"
if adb shell ls "$RESULT_DIR" >/dev/null 2>&1; then
  adb pull "$RESULT_DIR/." "$OUT/benchmarkData/" >/dev/null 2>&1 || warn "could not pull $RESULT_DIR"
else
  warn "no benchmark output dir on device ($RESULT_DIR) — no Macrobenchmark JSON to parse"
fi

# dumpsys-based memory report written by MemoryPeakBenchmark.
adb pull "/sdcard/Android/data/$PACKAGE/files/benchmark/memory-peak.json" "$OUT/memory-peak.json" \
  >/dev/null 2>&1 || warn "memory-peak.json not found (did MemoryPeakBenchmark run?)"

# -------------------------------------------------------------- cold start P90

if [[ $SKIP_COLD_START -eq 1 ]]; then
  log "cold start: skipped (--skip-cold-start)"
else
  log "cold start: $COLD_RUNS samples via am start -W (true P90, no profiling needed)"
  set +e
  "$NODE_BIN" "$ROOT/scripts/measure-cold-start.js" \
    --runs "$COLD_RUNS" --package "$PACKAGE" --json "$OUT/cold-start.json"
  COLD_STATUS=$?
  set -e
  [[ $COLD_STATUS -eq 0 ]] || warn "measure-cold-start.js reported a budget miss (exit $COLD_STATUS)"
fi

# ------------------------------------------------------------------- compare

cat > "$OUT/compare-budgets.js" <<'JS'
'use strict';
// Compares collected measurements against the budgets in the baseline document.
// Usage: node compare-budgets.js <budget.json> <outDir> [--strict]
const fs = require('fs');
const path = require('path');

const [, , budgetFile, outDir, ...flags] = process.argv;
const strict = flags.includes('--strict');
const budget = JSON.parse(fs.readFileSync(budgetFile, 'utf8'));
const limits = budget.budgets || {};

function readJson(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; }
}
function p90(values) {
  const s = [...values].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.ceil(0.9 * s.length) - 1)];
}

const rows = [];
const cold = readJson(path.join(outDir, 'cold-start.json'));

// Macrobenchmark JSON: metrics.<name> carries percentiles (frame metrics) and/or
// raw samples. StartupBenchmark and OpenEpubBenchmark produce DIFFERENT metrics
// (startupTimeMs vs openBookMs) and both files live in the same directory, so
// they are collected separately — mixing them would report the cold-start number
// as the open-EPUB number.
let frames = null;
let startupMetric = null;
let openBookMetric = null;
const dataDir = path.join(outDir, 'benchmarkData');
if (fs.existsSync(dataDir)) {
  for (const f of fs.readdirSync(dataDir)) {
    if (!f.endsWith('.json')) continue;
    const doc = readJson(path.join(dataDir, f));
    for (const b of (doc && doc.benchmarks) || []) {
      const m = b.metrics || {};
      if (m.frameDurationCpuMs && frames === null) frames = m.frameDurationCpuMs;
      if (m.startupTimeMs && startupMetric === null) startupMetric = m.startupTimeMs;
      if (m.openBookMs && openBookMetric === null) openBookMetric = m.openBookMs;
    }
  }
}

/** P90 from raw samples when the benchmark version exports them, else the median. */
function p90Of(metric) {
  if (!metric) return { value: null, note: null };
  if (Array.isArray(metric.runs) && metric.runs.length > 0) {
    return { value: p90(metric.runs), note: `P90 of ${metric.runs.length} samples` };
  }
  if (typeof metric.median === 'number') {
    return { value: metric.median, note: 'median (benchmark JSON carried no raw samples)' };
  }
  return { value: null, note: null };
}

if (cold) {
  rows.push({
    metric: 'coldStartP90Ms',
    limit: limits.coldStartP90Ms,
    value: cold.p90Ms,
    source: `measure-cold-start.js, ${cold.runs} am start -W samples`,
  });
} else {
  const s = p90Of(startupMetric);
  rows.push({
    metric: 'coldStartP90Ms',
    limit: limits.coldStartP90Ms,
    value: s.value,
    source: s.note ? `Macrobenchmark startupTimeMs — ${s.note}` : null,
  });
}

const openBook = p90Of(openBookMetric);
rows.push({
  metric: 'openEpubP90Ms',
  limit: limits.openEpubP90Ms,
  value: openBook.value,
  source: openBook.note ? `Macrobenchmark openBook trace section — ${openBook.note}` : null,
});

rows.push({
  metric: 'pageTurnP90Ms',
  limit: limits.pageTurnP90Ms,
  value: frames ? (frames.P90 ?? frames.p90 ?? null) : null,
  source: frames ? 'Macrobenchmark frameDurationCpuMs P90' : null,
});

const mem = readJson(path.join(outDir, 'memory-peak.json'));
rows.push({
  metric: 'memoryPeakMb',
  limit: limits.memoryPeakMb,
  value: mem ? mem.peakPssMb : null,
  source: mem ? `dumpsys meminfo TOTAL PSS (${mem.pagesRead} pages)` : null,
});

let failed = 0;
let missing = 0;
console.log('');
console.log('metric             | measured | budget | verdict');
console.log('-------------------+----------+--------+---------');
for (const r of rows) {
  if (r.value == null || r.limit == null) {
    missing++;
    console.log(`${r.metric.padEnd(18)} | ${'—'.padStart(8)} | ${String(r.limit ?? '—').padStart(6)} | NO DATA`);
    continue;
  }
  const ok = r.value <= r.limit;
  if (!ok) failed++;
  console.log(
    `${r.metric.padEnd(18)} | ${r.value.toFixed(1).padStart(8)} | ${String(r.limit).padStart(6)} | ${ok ? 'PASS' : 'FAIL'}`,
  );
}
console.log('');
for (const r of rows) {
  if (r.source) {
    console.log(`  ${r.metric} <- ${r.value == null ? 'no data' : r.value.toFixed(1)} (${r.source})`);
  }
}
console.log('');
if (missing > 0) {
  console.log(`[compare] ${missing} metric(s) without data — see ${outDir}`);
}
if (failed > 0) {
  console.log(`[compare] ${failed} metric(s) over budget`);
  process.exit(1);
}
if (missing > 0 && strict) {
  console.log('[compare] --strict: missing data treated as failure');
  process.exit(1);
}
console.log('[compare] all measured metrics within budget');
JS

log "compare: budgets from $(basename "$BUDGET")"
NODE_ARGS=("$BUDGET" "$OUT")
if [[ $STRICT -eq 1 ]]; then
  NODE_ARGS+=(--strict)
fi
set +e
"$NODE_BIN" "$OUT/compare-budgets.js" "${NODE_ARGS[@]}"
COMPARE_STATUS=$?
set -e

log "artifacts in $OUT:"
ls -1 "$OUT" || true

RESULT=0
if [[ $COMPARE_STATUS -ne 0 ]]; then
  RESULT=1
fi
log "result: $([[ $RESULT -eq 0 ]] && echo PASS || echo FAIL)"
exit "$RESULT"
