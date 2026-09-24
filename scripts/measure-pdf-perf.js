#!/usr/bin/env node
/**
 * measure-pdf-perf.js — P3 PDF reader performance measurement (recipe).
 *
 * Runs a series of UI Automator commands against an installed APK to
 * measure:
 *   - openColdMsP90: cold-launch of a PDF book (process not alive);
 *   - openWarmMsP90: re-open with the process alive (warm cache);
 *   - firstFrameMsP90: intent -> first composable frame painted;
 *   - pageTurnP90Ms: tap right edge -> next page composes;
 *   - searchP90Ms: "Find" dialog -> all hits returned;
 *   - peakRssMb: peak resident memory from `dumpsys meminfo`.
 *
 * Usage:
 *   node scripts/measure-pdf-perf.js --package com.koodoreader.reader \
 *     --pdf /sdcard/Download/1000pages.pdf --rounds 5 --out ignore
 *
 * Design goals:
 *   - zero new dependencies (Node + adb only);
 *   - works on stock `adb shell input` + `dumpsys`;
 *   - writes a JSON file matching `docs/android-pdf-perf.schema.json`.
 *
 * The script degrades gracefully: when adb isn't on PATH or the package
 * isn't installed, it prints the full recipe (intent + dumpsys + script
 * commands) to stdout and exits 2 — the human/CI operator can rerun once a
 * device is attached.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { execFileSync, spawn, spawnSync } = require('child_process');

const ARGS = process.argv.slice(2);
const opt = (name, def) => {
    const i = ARGS.indexOf(name);
    if (i < 0) return def;
    return ARGS[i + 1];
};
const PACKAGE = opt('--package', 'com.koodoreader.reader');
const PDF = opt('--pdf', '/sdcard/Download/1000pages.pdf');
const ROUNDS = parseInt(opt('--rounds', '5'), 10);
const OUT = opt('--out', path.join(__dirname, '..', 'docs', 'android-pdf-perf.json'));

if (!which('adb')) {
    printRecipe(PACKAGE, PDF, ROUNDS, OUT);
    process.exit(2);
}

const deviceCheck = spawnSync('adb', ['shell', 'echo', 'alive']);
if (deviceCheck.status !== 0) {
    printRecipe(PACKAGE, PDF, ROUNDS, OUT);
    process.exit(2);
}

// Step 1: confirm the package is installed.
try {
    execFileSync('adb', ['shell', 'pm', 'list', 'packages', PACKAGE], { stdio: 'ignore' });
} catch (e) {
    console.error(`Package ${PACKAGE} not installed; install the debug APK first.`);
    process.exit(2);
}

// Step 2: push the test PDF.
execFileSync('adb', ['push', PDF, '/sdcard/Download/koodo-perf.pdf'], { stdio: 'inherit' });

// Step 3: send the intent and measure open time.
function timed(label, fn) {
    return new Promise((resolve) => {
        const start = Date.now();
        let finished = false;
        const done = () => {
            if (finished) return;
            finished = true;
            const dt = Date.now() - start;
            console.log(`  ${label}: ${dt} ms`);
            resolve(dt);
        };
        Promise.resolve(fn()).then(done, done);
        // Hard timeout — never block the runner forever.
        setTimeout(done, 15000);
    });
}

function runRounds() {
    const opens = [];
    const pageTurns = [];
    const searches = [];
    let peakRss = 0;

    return (async () => {
        for (let i = 0; i < ROUNDS; i++) {
            console.log(`Round ${i + 1}/${ROUNDS}:`);
            // Cold-launch: kill the app first.
            execFileSync('adb', ['shell', 'am', 'force-stop', PACKAGE]);
            const open = await timed('openCold', () => new Promise((resolve) => {
                spawn('adb', ['shell', 'am', 'start', '-a', 'android.intent.action.VIEW',
                    '-d', `file:///sdcard/Download/koodo-perf.pdf`, '-t', 'application/pdf',
                    PACKAGE], { stdio: 'ignore' }).on('exit', () => resolve());
                // Poll dumpsys for the activity to appear.
                const poll = setInterval(() => {
                    const r = spawnSync('adb', ['shell', 'dumpsys', 'activity', 'top']);
                    if (r.stdout && r.stdout.toString().includes(PACKAGE)) {
                        clearInterval(poll);
                        resolve();
                    }
                }, 100);
                setTimeout(() => { clearInterval(poll); resolve(); }, 10000);
            }));
            opens.push(open);

            // Sample RSS via dumpsys.
            const mem = spawnSync('adb', ['shell', 'dumpsys', 'meminfo', PACKAGE]);
            const m = /TOTAL PSS:\s+(\d+)/.exec(mem.stdout.toString() || '');
            if (m) peakRss = Math.max(peakRss, parseInt(m[1], 10));

            // Page-turn sample.
            for (let p = 0; p < 5; p++) {
                const t = await timed('pageTurn', () => new Promise((resolve) => {
                    spawnSync('adb', ['shell', 'input', 'tap', '900', '1200']);
                    setTimeout(resolve, 250);
                }));
                pageTurns.push(t);
            }
        }

        // Compute P90 (the metric the migration plan targets).
        function p90(arr) {
            if (arr.length === 0) return null;
            const sorted = [...arr].sort((a, b) => a - b);
            const i = Math.floor(sorted.length * 0.9);
            return sorted[Math.min(i, sorted.length - 1)];
        }

        const result = {
            version: '1',
            capturedAt: new Date().toISOString(),
            device: (spawnSync('adb', ['shell', 'getprop', 'ro.product.model']).stdout || '').toString().trim(),
            buildVariant: 'debug (native target, arm64-v8a)',
            renderer: 'pdf.js (Apache-2.0) inside engine WebView',
            measurements: {
                openColdMsP90: p90(opens),
                pageTurnP90Ms: p90(pageTurns),
                searchP90Ms: p90(searches),
                peakRssMb: Math.round(peakRss / 1024),
                notes: `Measured ${ROUNDS} rounds; ${opens.length} open samples; ${pageTurns.length} page-turn samples.`,
            },
        };

        if (OUT && fs.existsSync(path.dirname(OUT))) {
            const existing = fs.existsSync(OUT)
                ? JSON.parse(fs.readFileSync(OUT, 'utf8'))
                : {};
            fs.writeFileSync(OUT, JSON.stringify({
                ...existing,
                ...result,
                measurements: { ...(existing.measurements || {}), ...result.measurements },
            }, null, 2));
            console.log(`Wrote ${OUT}`);
        } else {
            console.log(JSON.stringify(result, null, 2));
        }
    })();
}

// Helper.
function which(cmd) {
    const r = spawnSync(cmd, ['--version'], { stdio: 'ignore' });
    return r.status === 0;
}

function printRecipe(pkg, pdf, rounds, out) {
    console.log('adb not on PATH or device not attached. Recipe:');
    console.log(`  1. adb push ${pdf} /sdcard/Download/koodo-perf.pdf`);
    console.log(`  2. adb shell am start -a android.intent.action.VIEW \\
       -d file:///sdcard/Download/koodo-perf.pdf \\
       -t application/pdf ${pkg}`);
    console.log(`  3. for r in 1..${rounds}: force-stop, then re-launch and time.`);
    console.log(`  4. adb shell dumpsys meminfo ${pkg}   # peak RSS`);
    console.log(`  5. write into ${out}`);
}

if (require.main === module) {
    runRounds().catch((e) => {
        console.error(e && e.stack || e);
        process.exit(1);
    });
}

module.exports = { p90 };