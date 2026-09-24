#!/usr/bin/env node
/*
 * check-ocr-manifest.js — keep the P6 OCR install-time pre-download wiring in
 * `AndroidManifest.xml` in sync with the `OcrScript` enum.
 *
 * Why: `feature/ocr`'s models are Google Play services modules. The manifest
 * meta-data below is the only mechanism that pre-fetches them at install time
 * (`ModuleInstallClient` cannot address ML Kit's options objects — see the
 * RESOLVED note in android/feature/ocr/build.gradle), so a typo'd or stale
 * token means the model silently is not pre-downloaded and the first scan waits
 * on a fetch. Nothing else in the build can see that.
 *
 * Checks:
 *   1. the `com.google.mlkit.vision.DEPENDENCIES` meta-data exists and is non-empty;
 *   2. every token in it is a real `OcrScript.manifestValue` (read from the enum);
 *   3. no duplicate tokens;
 *   4. the covered scripts are a prefix-plausible subset — latin is always
 *      declared, since it is the fallback script for every locale.
 *
 * Usage: node scripts/check-ocr-manifest.js [--verbose]   # exit 0/1
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const MANIFEST = path.join(ROOT, 'android', 'app', 'src', 'main', 'AndroidManifest.xml');
const ENGINE = path.join(
  ROOT, 'android', 'feature', 'ocr', 'src', 'main', 'kotlin',
  'com', 'koodoreader', 'feature', 'ocr', 'OcrEngine.kt',
);
const VERBOSE = process.argv.includes('--verbose');

const failures = [];

function read(file) {
  if (!fs.existsSync(file)) {
    console.error(`[check-ocr-manifest] missing ${file}`);
    process.exit(2);
  }
  return fs.readFileSync(file, 'utf8');
}

function check(name, ok, detail) {
  if (ok) {
    if (VERBOSE) console.log(`  PASS  ${name}`);
  } else {
    failures.push(`${name}${detail ? ' — ' + detail : ''}`);
  }
}

function main() {
  const manifest = read(MANIFEST);
  const engine = read(ENGINE);

  // `LATIN(manifestValue = "ocr", ...)` — the enum's tokens.
  const known = {};
  const enumRe = /manifestValue\s*=\s*"([^"]+)"/g;
  let m;
  while ((m = enumRe.exec(engine)) !== null) known[m[1]] = true;
  check('the OcrScript enum exposes manifest tokens', Object.keys(known).length > 0);

  const metaRe = /<meta-data\s+android:name="com\.google\.mlkit\.vision\.DEPENDENCIES"\s+android:value="([^"]*)"\s*\/>/;
  const meta = manifest.match(metaRe);
  check(
    'declares the ML Kit DEPENDENCIES meta-data',
    meta !== null,
    'without it Play services does not pre-download any OCR model',
  );
  if (!meta) return;

  const tokens = meta[1].split(',').map((t) => t.trim()).filter(Boolean);
  check('the meta-data value is not empty', tokens.length > 0);

  const unknown = tokens.filter((t) => !known[t]);
  check(
    'every token is an OcrScript.manifestValue',
    unknown.length === 0,
    unknown.length ? `unknown: ${unknown.join(', ')} (known: ${Object.keys(known).join(', ')})` : '',
  );

  const duplicates = tokens.filter((t, i) => tokens.indexOf(t) !== i);
  check('no duplicate tokens', duplicates.length === 0, duplicates.join(', '));

  check(
    'latin ("ocr") is pre-downloaded, being the fallback script',
    tokens.includes('ocr'),
    'OcrScript.forLanguage() falls back to LATIN for every unknown locale',
  );

  if (failures.length > 0) {
    console.error(`[check-ocr-manifest] ${failures.length} check(s) FAILED`);
    for (const f of failures) console.error(`  - ${f}`);
    process.exit(1);
  }
  console.log(
    `[check-ocr-manifest] OK — pre-downloads ${tokens.join(', ')} ` +
    `(${tokens.length} of ${Object.keys(known).length} known scripts; the rest fetch on first use)`,
  );
}

main();
