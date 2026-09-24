#!/usr/bin/env node
/**
 * check-elf-16kb.js — Node-side mirror of scripts/check-elf-16kb.sh (P3).
 *
 * Cross-platform alternative to the shell script (CI runners vary; the
 * repo enforces Node availability for the existing guards — see
 * scripts/check-room-schema.js, check-import-rules.js, ...). The Node
 * version also runs the unpacking via `unzip` and uses the system
 * `llvm-readelf` (or `readelf`).
 *
 * Usage:
 *   node scripts/check-elf-16kb.js <app.apk>
 *   node scripts/check-elf-16kb.js --dir <apk_unpacked_dir>
 *   node scripts/check-elf-16kb.js --quiet  # only print failures
 *
 * Exits 1 when ANY .so in the APK fails the 16KB page-alignment check,
 * 2 on usage / tool errors. Pure JS — no native deps.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { execFileSync, spawnSync } = require('child_process');
const os = require('os');

const ARGS = process.argv.slice(2);
const QUIET = ARGS.includes('--quiet');
const DIR_FLAG = ARGS.indexOf('--dir');
let apk = null;
let dirIn = null;
if (DIR_FLAG >= 0) {
    dirIn = ARGS[DIR_FLAG + 1];
} else {
    apk = ARGS.find((a) => !a.startsWith('-'));
}
if (!apk && !dirIn) {
    console.error('Usage: node scripts/check-elf-16kb.js <app.apk>');
    console.error('       node scripts/check-elf-16kb.js --dir <apk_unpacked_dir>');
    process.exit(2);
}

const READELF = process.env.READELF || 'llvm-readelf';

let workDir = dirIn;
let cleanup = null;
try {
    if (apk) {
        if (!fs.existsSync(apk)) {
            console.error(`APK not found: ${apk}`);
            process.exit(2);
        }
        workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pdf16kb-'));
        cleanup = workDir;
        execFileSync('unzip', ['-q', apk, '-d', workDir], { stdio: ['ignore', 'ignore', 'inherit'] });
    }

    // Discover .so files.
    const sos = [];
    function walk(p) {
        const stat = fs.statSync(p);
        if (stat.isDirectory()) {
            for (const e of fs.readdirSync(p)) walk(path.join(p, e));
            return;
        }
        if (p.endsWith('.so')) sos.push(p);
    }
    if (!fs.existsSync(workDir)) {
        console.error(`Directory not found: ${workDir}`);
        process.exit(2);
    }
    walk(workDir);
    if (sos.length === 0) {
        if (!QUIET) console.log(`INFO: no .so files in ${workDir} — no 16KB check required`);
        return;
    }
    sos.sort();

    // Try llvm-readelf; fall back to readelf when missing.
    let readelfBin = READELF;
    try {
        execFileSync(readelfBin, ['--version'], { stdio: 'ignore' });
    } catch (e) {
        if (readelfBin !== 'readelf') {
            readelfBin = 'readelf';
            try {
                execFileSync(readelfBin, ['--version'], { stdio: 'ignore' });
            } catch (e2) {
                console.error(`ERROR: neither llvm-readelf nor readelf found.`);
                console.error('       Install Android NDK r26+ and set READELF=<path>');
                process.exit(3);
            }
        } else {
            console.error(`ERROR: '$READELF' not found. Install Android NDK r26+ and set READELF=<path>`);
            process.exit(3);
        }
    }

    let failed = 0;
    if (!QUIET) console.log(`Checking ${sos.length} .so file(s) for 16KB page alignment...`);

    for (const so of sos) {
        const rel = path.relative(workDir, so);
        const ok = checkSo(so, readelfBin);
        if (!ok) {
            failed++;
            if (QUIET) console.log(`  FAIL  ${rel}`);
        } else if (!QUIET) {
            console.log(`  PASS  ${rel}`);
        }
    }

    if (failed > 0) {
        console.error(`${failed} of ${sos.length} .so file(s) failed 16KB alignment`);
        process.exit(1);
    }
    if (!QUIET) console.log(`All ${sos.length} .so file(s) are 16KB-page-aligned.`);
} finally {
    if (cleanup) {
        try { fs.rmSync(cleanup, { recursive: true, force: true }); } catch (e) {}
    }
}

/**
 * Returns true when every LOAD segment's p_align is a multiple of 0x4000
 * (16384). readelf/llvm-readelf prints the segments with whitespace-
 * separated columns; the 7th column is `Align`.
 */
function checkSo(soPath, readelfBin) {
    let out;
    try {
        out = execFileSync(readelfBin, ['-l', soPath], { encoding: 'utf8' });
    } catch (e) {
        return false;
    }
    let saw = false;
    for (const line of out.split('\n')) {
        if (!/^\s*LOAD/.test(line)) continue;
        saw = true;
        const cols = line.trim().split(/\s+/);
        if (cols.length < 7) return false;
        const alignField = cols[6];
        const align = parseAlign(alignField);
        if (align == null) return false;
        if (align % 0x4000 !== 0) return false;
    }
    // No LOAD segments? Treat as a malformed SO rather than pass-through.
    return saw;
}

function parseAlign(s) {
    if (s == null) return null;
    const t = String(s).trim();
    if (t.startsWith('0x') || t.startsWith('0X')) return parseInt(t.slice(2), 16);
    if (/^\d+$/.test(t)) return parseInt(t, 10);
    // Some toolchains print "4K" / "16K" instead of the raw number.
    const m = /^(\d+)([KMG])$/.exec(t);
    if (m) {
        const n = parseInt(m[1], 10);
        const mul = { K: 1024, M: 1024 * 1024, G: 1024 * 1024 * 1024 }[m[2]];
        return n * mul;
    }
    return null;
}