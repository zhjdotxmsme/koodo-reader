#!/usr/bin/env node
// sync-locales-android.js — build-time bridge: sync the desktop translation
// JSONs (src/assets/locales) into the Android app assets so the native shell
// serves strings with keys IDENTICAL to the desktop (react-i18next keys).
//
//   node scripts/sync-locales-android.js            # sync
//   node scripts/sync-locales-android.js --check    # CI: fail on drift
//   node scripts/sync-locales-android.js --verbose
//
// Subset policy (ADR-004): only en + zh-CN are bundled with the APK today
// (the desktop ships 41 locales; ~1.4k keys each). The script is the single
// place to extend ANDROID_LOCALES when on-demand locale packs land (P6+).
//
// LINE-ENDING POLICY (root-cause fix for the recurring "manifest sha256
// drift for en" CI red): all reads are normalized CRLF → LF before hashing
// or comparing, and bundled files are always written with LF. Without this,
// a Windows working tree (core.autocrlf=true) hashes CRLF bytes into
// manifest.json while the Linux runner checks out LF bytes — the guard then
// fails on CI even moments after a "sync" on Windows. Normalize once here,
// and the manifest sha becomes platform-independent.
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.join(__dirname, '..');
const SRC_DIR = path.join(ROOT, 'src', 'assets', 'locales');
const OUT_DIR = path.join(
  ROOT, 'android', 'app', 'src', 'main', 'assets', 'locales',
);
const VERBOSE = process.argv.includes('--verbose');
const CHECK = process.argv.includes('--check');

// Bundled subset — keep aligned with ADR-004.
const ANDROID_LOCALES = ['en', 'zh-CN'];

function fail(msg) {
  console.error(`[sync-locales-android] ${msg}`);
  process.exit(2);
}

/** Read a text file with CRLF/BOM normalized away (platform-independent). */
function readNormalized(file) {
  return fs
    .readFileSync(file, 'utf8')
    .replace(/^\uFEFF/, '')
    .replace(/\r\n/g, '\n');
}

function sha256(text) {
  return crypto.createHash('sha256').update(text).digest('hex');
}

function main() {
  if (!fs.existsSync(SRC_DIR)) fail(`missing desktop locales dir: ${SRC_DIR}`);

  const manifest = { locales: [], source: {}, syncedAt: new Date().toISOString() };
  for (const code of ANDROID_LOCALES) {
    const srcFile = path.join(SRC_DIR, `${code}.json`);
    if (!fs.existsSync(srcFile)) fail(`missing locale source: ${code}.json`);
    const raw = readNormalized(srcFile);
    const parsed = JSON.parse(raw); // validates before bundling
    const keys = Object.keys(parsed);
    if (keys.length === 0) fail(`locale ${code} parsed empty`);
    const nonString = keys.filter((k) => typeof parsed[k] !== 'string');
    if (nonString.length > 0) fail(`locale ${code} has non-string values: ${nonString[0]}`);
    manifest.locales.push(code);
    manifest.source[code] = {
      keys: keys.length,
      sha256: sha256(raw),
    };

    const outFile = path.join(OUT_DIR, `${code}.json`);
    const outRaw = fs.existsSync(outFile) ? readNormalized(outFile) : null;
    if (CHECK) {
      if (outRaw === null) fail(`missing bundled locale: ${outFile}`);
      if (outRaw !== raw) fail(`bundled ${code}.json drifted from src/assets/locales — run sync`);
      if (VERBOSE) console.log(`[sync-locales-android] ${code}: OK (${keys.length} keys)`);
    } else {
      fs.mkdirSync(OUT_DIR, { recursive: true });
      fs.writeFileSync(outFile, raw);
      if (VERBOSE) console.log(`[sync-locales-android] ${code}: ${keys.length} keys -> ${outFile}`);
    }
  }

  const manifestFile = path.join(OUT_DIR, 'manifest.json');
  if (CHECK) {
    if (!fs.existsSync(manifestFile)) fail('missing bundled manifest.json');
    const bundled = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
    for (const code of ANDROID_LOCALES) {
      if (!bundled.locales.includes(code)) fail(`manifest missing locale ${code}`);
      if (bundled.source?.[code]?.sha256 !== manifest.source[code].sha256) {
        fail(`manifest sha256 drift for ${code} — run sync`);
      }
    }
  } else {
    fs.writeFileSync(manifestFile, JSON.stringify(manifest, null, 2) + '\n');
  }

  console.log(
    `[sync-locales-android] ${CHECK ? 'check' : 'sync'} OK — ` +
      `bundled: ${ANDROID_LOCALES.join(', ')} of 41 desktop locales (ADR-004)`,
  );
}

main();
