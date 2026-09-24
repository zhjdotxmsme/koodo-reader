#!/usr/bin/env node
// measure-cold-start.js — native shell cold-start measurement (P1 acceptance,
// migration doc §8: cold start P90 ≤ 1.5 s).
//
// Requires: a device/emulator with the native APK installed
//   gradle :app:assembleDebug -Ptarget=native && adb install -r app-debug.apk
// Usage:
//   node scripts/measure-cold-start.js [--runs 10] [--package com.koodoreader.reader] [--json OUT]
//
// Method (朴素 logcat/am-start 方案, Macrobenchmark 为 P2 终态):
//   1. force-stop the app (guarantees a cold start)
//   2. `adb shell am start -W -n <pkg>/<activity>` and parse TotalTime
//   3. repeat N times, report Min/P50/P90/Max and pass/fail vs the target.
'use strict';

const { execFileSync } = require('child_process');
const fs = require('fs');

const ADB = 'adb';
const ACTIVITY = 'com.koodoreader.reader/.shell.NativeShellActivity';
const TARGET_P90_MS = 1500;

function arg(name, def) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : def;
}

const runs = Number(arg('runs', 10));
const pkg = arg('package', 'com.koodoreader.reader');
const jsonOut = arg('json', null);

function adb(args) {
  return execFileSync(ADB, args, { encoding: 'utf8', timeout: 60_000 });
}

function coldStartOnce() {
  adb(['shell', 'am', 'force-stop', pkg]);
  const out = adb(['shell', 'am', 'start', '-W', '-n', ACTIVITY]);
  const total = /TotalTime:\s*(\d+)/.exec(out);
  const wait = /WaitTime:\s*(\d+)/.exec(out);
  if (!total) throw new Error(`no TotalTime in am start output:\n${out}`);
  return { totalMs: Number(total[1]), waitMs: wait ? Number(wait[1]) : null };
}

function percentile(sorted, p) {
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, idx)];
}

function main() {
  const devices = adb(['devices']).trim().split('\n').slice(1).filter((l) => l.trim());
  if (devices.length === 0) {
    console.error('[measure-cold-start] no adb device attached — connect a device or start an emulator.');
    process.exit(2);
  }
  console.log(`[measure-cold-start] device: ${devices[0]} · ${runs} cold starts · target P90 <= ${TARGET_P90_MS} ms`);

  // Warm-up (first start compiles/dexopts; excluded from the sample).
  coldStartOnce();
  const samples = [];
  for (let i = 0; i < runs; i++) {
    const s = coldStartOnce();
    samples.push(s.totalMs);
    console.log(`  run ${i + 1}/${runs}: TotalTime=${s.totalMs} ms`);
  }
  const sorted = [...samples].sort((a, b) => a - b);
  const result = {
    metric: 'coldStartP90Ms',
    activity: ACTIVITY,
    runs: samples.length,
    samplesMs: samples,
    minMs: sorted[0],
    p50Ms: percentile(sorted, 50),
    p90Ms: percentile(sorted, 90),
    maxMs: sorted[sorted.length - 1],
    targetP90Ms: TARGET_P90_MS,
    pass: percentile(sorted, 90) <= TARGET_P90_MS,
    measuredAt: new Date().toISOString(),
  };
  console.log(
    `[measure-cold-start] min=${result.minMs} p50=${result.p50Ms} ` +
      `p90=${result.p90Ms} max=${result.maxMs} -> ${result.pass ? 'PASS' : 'FAIL'} (target ${TARGET_P90_MS} ms)`,
  );
  if (jsonOut) {
    fs.writeFileSync(jsonOut, JSON.stringify(result, null, 2));
    console.log(`[measure-cold-start] wrote ${jsonOut}`);
  }
  process.exit(result.pass ? 0 : 1);
}

main();
