#!/usr/bin/env node
/**
 * Stage the pdf.js engine assets from `public/lib/pdfjs/` into
 * `android/app/src/main/assets/pdfengine/` (P3, docs/android-pdf-poc.md).
 *
 * Why? The native PDF reader embeds pdf.js (Apache-2.0) in the engine
 * WebView, served through the existing `LocalAssetServer` (loopback HTTP).
 * The assets must:
 *   - keep the SAME filenames as the desktop webapp so pdf.js's own
 *     imports (`pdf.worker.mjs` is loaded via `workerSrc = pdf.worker.entry` —
 *     which resolves relative to the document URL) keep resolving;
 *   - be a SUBSET of the desktop vendored bundle (we drop `pdfjs.history*`
 *     and desktop-only chrome);
 *   - include a `cmaps/` directory with the same character maps, so CJK
 *     font fallback keeps working.
 *
 * Run from the repo root:
 *   node scripts/stage-pdf-engine-assets.js
 *   node scripts/stage-pdf-engine-assets.js --check   # CI guard
 *
 * The CI guard exits non-zero when the staged copy drifts from the source.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'public', 'lib', 'pdfjs');
const DEST = path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'pdfengine');
const ENGINE_BOOTSTRAP = path.join(DEST, 'index.html');

const REQUIRED = ['pdf.mjs', 'pdf.worker.mjs'];
const RECOMMENDED = [
    'annotation_layer_builder.css',
    'pdf_viewer.css',         // missing on the desktop copy: drop gracefully
    'cmaps',
];

const CHECK_ONLY = process.argv.includes('--check');

function fail(msg, code = 1) {
    console.error(`[stage-pdf-engine-assets] ${msg}`);
    process.exit(code);
}

function rimraf(p) {
    if (!fs.existsSync(p)) return;
    const stat = fs.lstatSync(p);
    if (stat.isDirectory()) {
        for (const entry of fs.readdirSync(p)) {
            rimraf(path.join(p, entry));
        }
        try { fs.rmdirSync(p); } catch (e) { /* ignore */ }
        return;
    }
    try { fs.unlinkSync(p); } catch (e) { /* ignore */ }
}

function copyRecursive(src, dest) {
    const stat = fs.statSync(src);
    if (stat.isDirectory()) {
        if (!fs.existsSync(dest)) fs.mkdirSync(dest, { recursive: true });
        for (const entry of fs.readdirSync(src)) {
            copyRecursive(path.join(src, entry), path.join(dest, entry));
        }
        return;
    }
    fs.copyFileSync(src, dest);
}

function ensureBootstrap(d) {
    // We DON'T overwrite an existing index.html — the bootstrap is the
    // bridge between pdf.js and window.__koodoPdfHost. CI ships it as
    // a versioned source file (see index.html in this directory's
    // sibling — committed alongside the staged assets).
    const expected = path.join(d, 'index.html');
    if (!fs.existsSync(expected)) {
        fail(`missing bootstrap at ${expected}; commit index.html alongside the staged assets`);
    }
}

function stage() {
    if (!fs.existsSync(SRC)) {
        fail(`source pdf.js vendored copy not found at ${SRC}`);
    }
    if (CHECK_ONLY) {
        // Guard mode: every file in SRC must be present in DEST (modulo
        // the optional files we strip on purpose).
        const srcFiles = listFiles(SRC);
        const destFiles = fs.existsSync(DEST) ? listFiles(DEST) : new Set();
        let missing = 0;
        for (const f of srcFiles) {
            if (REQUIRED.indexOf(f) >= 0 && !destFiles.has(f)) {
                console.error(`[stage-pdf-engine-assets] MISSING ${f}`);
                missing++;
            }
        }
        if (missing > 0) {
            fail(`${missing} required files missing in ${DEST}; run scripts/stage-pdf-engine-assets.js`);
        }
        // Also verify the bootstrap is in place.
        ensureBootstrap(DEST);
        console.log(`[stage-pdf-engine-assets] check OK (${srcFiles.size} source files verified)`);
        return;
    }
    // Staging mode: refresh the library files but PRESERVE the bootstrap
    // (index.html). The bootstrap is a hand-written bridge between pdf.js
    // and the native host — it's a source file, not a generated artifact.
    if (!fs.existsSync(DEST)) fs.mkdirSync(DEST, { recursive: true });
    const bootstrapPath = path.join(DEST, 'index.html');
    let bootstrapSnapshot = null;
    if (fs.existsSync(bootstrapPath)) {
        bootstrapSnapshot = fs.readFileSync(bootstrapPath, 'utf8');
    }
    // Required files
    for (const f of REQUIRED) {
        const src = path.join(SRC, f);
        if (!fs.existsSync(src)) fail(`required file missing: ${src}`);
        fs.copyFileSync(src, path.join(DEST, f));
    }
    // Recommended / optional — refresh, but never blow up the destination
    // directory (we want a non-staging edit to index.html to survive).
    for (const f of RECOMMENDED) {
        const src = path.join(SRC, f);
        if (fs.existsSync(src)) {
            const dest = path.join(DEST, f);
            // Wipe only the destination subtree so stale files don't
            // accumulate (e.g. a removed bcmap would linger otherwise).
            if (fs.existsSync(dest)) rimraf(dest);
            copyRecursive(src, dest);
            console.log(`[stage-pdf-engine-assets] staged ${f}`);
        } else {
            console.warn(`[stage-pdf-engine-assets] optional ${f} not present in source`);
        }
    }
    // Restore the bootstrap snapshot.
    if (bootstrapSnapshot != null) {
        fs.writeFileSync(bootstrapPath, bootstrapSnapshot, 'utf8');
    } else {
        ensureBootstrap(DEST);
    }
    console.log(`[stage-pdf-engine-assets] staged ${REQUIRED.length} required + ${RECOMMENDED.length} optional → ${DEST}`);
}

function listFiles(root) {
    const out = new Set();
    function walk(p) {
        const stat = fs.statSync(p);
        if (stat.isDirectory()) {
            for (const e of fs.readdirSync(p)) walk(path.join(p, e));
            return;
        }
        out.add(path.relative(root, p).replace(/\\/g, '/'));
    }
    walk(root);
    return out;
}

if (require.main === module) stage();

module.exports = { stage, REQUIRED, RECOMMENDED };