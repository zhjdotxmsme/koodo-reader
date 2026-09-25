#!/usr/bin/env node
/*
 * measure-import-jvm.js — record the JVM half of the P1-IMPORT acceptance case.
 *
 * Why this exists: the board's P1-IMPORT card measures 1000 books on a device
 * (adb + dumpsys) and cannot run in a device-less environment. The pipeline's
 * own scale behaviour is still checkable here — and it was NOT: until this
 * script's companion fix, `ImportPipelineTest` used `@Test suspend fun`, which
 * JUnit 5 cannot invoke, so the whole class (including its 1000-book case)
 * silently produced no results while `docs/android-baseline.json` cited it as
 * evidence. Now the class runs, and this script records what it measures.
 *
 * What it runs: `:core:importer:importScaleTest` — 1000 books, mixed formats
 * (500 TXT / 300 EPUB-with-cover / 200 CBZ-with-pages), batch pass plus a
 * per-book latency pass, under a bounded heap (`--heap`, default 256m).
 *
 * What it does NOT claim: device numbers. JVM heap and a desktop CPU are not an
 * ARM device, SAF and Room are not imported here. Results are labelled
 * `measured-jvm` and the card's device fields stay `pending-device`.
 *
 * Usage:
 *   node scripts/measure-import-jvm.js                     # run + write CSV
 *   node scripts/measure-import-jvm.js --update-baseline    # + patch baseline JSON
 *   node scripts/measure-import-jvm.js --books 200 --heap 192m
 *   node scripts/measure-import-jvm.js --gradle /opt/gradle-8.5/bin/gradle
 *   node scripts/measure-import-jvm.js --dry-run            # print the plan
 */
'use strict';

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const BASELINE = path.join(ROOT, 'docs', 'android-baseline.json');
const BENCH_DIR = path.join(ROOT, 'docs', 'benchmarks');

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  if (i < 0) return fallback;
  const next = process.argv[i + 1];
  return next && !next.startsWith('--') ? next : true;
}

const books = Number(arg('books', 1000));
const heap = String(arg('heap', '256m'));
const dryRun = process.argv.includes('--dry-run');
const updateBaseline = process.argv.includes('--update-baseline');
// CI puts `gradle` on PATH; a dev box usually needs `--gradle <path>` or $GRADLE.
// The repo ships no wrapper (CI installs Gradle 8.5 itself).
const gradleArg = arg('gradle', null);
const gradle = typeof gradleArg === 'string'
  ? gradleArg
  : process.env.GRADLE || 'gradle';

const gradleArgs = [
  '-p', path.join(ROOT, 'android'),
  '--console=plain',
  '--no-daemon',
  ':core:importer:importScaleTest',
  `-PimportBooks=${books}`,
  `-PimportHeap=${heap}`,
];

if (dryRun) {
  console.log(`[measure-import-jvm] plan: ${gradle} ${gradleArgs.join(' ')}`);
  console.log(`[measure-import-jvm] would write ${path.join(BENCH_DIR, '<date>-import-1000-jvm.csv')}`);
  process.exit(0);
}

console.log(`[measure-import-jvm] running: ${gradle} ${gradleArgs.join(' ')}`);
const started = Date.now();
const run = spawnSync(gradle, gradleArgs, {
  cwd: ROOT,
  stdio: ['ignore', 'pipe', 'pipe'],
  encoding: 'utf8',
  shell: process.platform === 'win32',
});
const stdout = `${run.stdout || ''}\n${run.stderr || ''}`;
const elapsed = Math.round((Date.now() - started) / 1000);

const match = stdout.match(/IMPORT_SCALE_JSON=(\{.*\})/);
if (!match) {
  console.error('[measure-import-jvm] no IMPORT_SCALE_JSON line in the Gradle output');
  console.error(stdout.split('\n').slice(-25).join('\n'));
  process.exit(2);
}

let report;
try {
  report = JSON.parse(match[1]);
} catch (err) {
  console.error(`[measure-import-jvm] could not parse the report: ${err.message}`);
  process.exit(2);
}

const date = new Date().toISOString().slice(0, 10);
const csvPath = path.join(BENCH_DIR, `${date}-import-1000-jvm.csv`);
fs.mkdirSync(BENCH_DIR, { recursive: true });

const rows = [
  ['metric', 'value', 'unit', 'scope', 'note'],
  ['books', String(report.books), 'books', 'measured-jvm', 'mixed formats'],
  ['formats.txt', String(report.formats.txt), 'books', 'measured-jvm', 'plain copy path'],
  ['formats.epub', String(report.formats.epub), 'books', 'measured-jvm', 'OPF parse + cover bytes'],
  ['formats.cbz', String(report.formats.cbz), 'books', 'measured-jvm', 'natural sort + page count'],
  ['batch.imported', String(report.batch.imported), 'books', 'measured-jvm', 'single process() call'],
  ['batch.duplicates', String(report.batch.duplicates), 'books', 'measured-jvm', ''],
  ['batch.failed', String(report.batch.failed), 'books', 'measured-jvm', ''],
  ['batch.elapsedMs', String(report.batch.elapsedMs), 'ms', 'measured-jvm', '1000 books end-to-end'],
  ['batch.peakHeapMb', String(report.batch.peakHeapMb), 'MB', 'measured-jvm', 'sampled every 5 ms'],
  ['batch.heapCapMb', String(report.batch.heapCapMb), 'MB', 'measured-jvm', 'JVM -Xmx for the run'],
  ['perBook.p50', String(report.perBookMs.p50), 'ms', 'measured-jvm', 'one process() per book'],
  ['perBook.p90', String(report.perBookMs.p90), 'ms', 'measured-jvm', ''],
  ['perBook.p95', String(report.perBookMs.p95), 'ms', 'measured-jvm', ''],
  ['perBook.p99', String(report.perBookMs.p99), 'ms', 'measured-jvm', ''],
  ['perBook.max', String(report.perBookMs.max), 'ms', 'measured-jvm', ''],
  ['wallClock', String(elapsed), 's', 'measured-jvm', 'includes Gradle + compile'],
];
fs.writeFileSync(csvPath, rows.map((r) => r.join(',')).join('\n') + '\n');
console.log(`[measure-import-jvm] wrote ${path.relative(ROOT, csvPath)}`);

if (updateBaseline) {
  const baseline = JSON.parse(fs.readFileSync(BASELINE, 'utf8'));
  const entry = baseline.jvmImportThroughput || {};
  entry.status = 'measured-jvm';
  entry.metric = `${report.books} synthetic books imported end-to-end ` +
    `(${report.formats.txt} TXT / ${report.formats.epub} EPUB-with-cover / ${report.formats.cbz} CBZ-with-pages)`;
  entry.result = `pass: ${report.batch.imported}/${report.books} imported, ` +
    `${report.batch.duplicates} duplicates, ${report.batch.failed} failures, ` +
    `unique md5/keys, peak heap ${report.batch.peakHeapMb} MB of ${report.batch.heapCapMb} MB cap`;
  entry.test = 'com.koodoreader.core.importer.ImportScaleHarnessTest ' +
    '(run: node scripts/measure-import-jvm.js)';
  entry.elapsedMs = report.batch.elapsedMs;
  entry.peakHeapMb = report.batch.peakHeapMb;
  entry.heapCapMb = report.batch.heapCapMb;
  entry.perBookMs = report.perBookMs;
  entry.measuredAt = date;
  entry.csv = path.relative(ROOT, csvPath).replace(/\\/g, '/');
  entry.caveats = [
    'JVM desktop-class CPU and JVM heap, NOT an ARM device: do not read device memory budgets from this.',
    'SAF, Room upserts and cover files on disk are not exercised here (Android-only).',
    'The device half of the card (P1-IMPORT adb + dumpsys run) stays pending-device.',
  ];
  baseline.jvmImportThroughput = entry;
  fs.writeFileSync(BASELINE, JSON.stringify(baseline, null, 2) + '\n');
  console.log(`[measure-import-jvm] updated ${path.relative(ROOT, BASELINE)}`);
}

console.log(
  `[measure-import-jvm] ${report.books} books in ${report.batch.elapsedMs} ms ` +
  `(P90 ${report.perBookMs.p90} ms/book), peak heap ${report.batch.peakHeapMb} MB / ${report.batch.heapCapMb} MB`,
);
