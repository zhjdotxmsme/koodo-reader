#!/usr/bin/env node
// check-import-rules.js — guard: the native Kotlin format registry must stay
// in lock-step with the desktop import list and legacy WebView-track mirror,
// except that CBR remains legacy-only and is deliberately unsupported natively.
//
//   node scripts/check-import-rules.js        # verify, exit 0/2
//   node scripts/check-import-rules.js --verbose
//
// Checked:
//   1. BookFormat == supportedFormats (src/utils/common.ts), excluding CBR
//   2. folderBridge.js is a subset of the native list, excluding legacy CBR
//      (it also intentionally excludes .xml until WebView-track parity is needed)
//   3. Every non-CBR folderBridge MIME mapping matches the registry
//   4. MAX_FILES / FOLDER_DEPTH / FOLDER_EVENT shared constants
// Rationale: P1 migrates the import rules to Kotlin as the single source of
// truth for the native track; folderBridge.js stays until the WebView track
// retires (P8), so CI must fail when the two drift.
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JS_FILE = path.join(ROOT, 'src', 'utils', 'android', 'folderBridge.js');
const DESKTOP_LIST_FILE = path.join(ROOT, 'src', 'utils', 'common.ts');
const KT_FILE = path.join(
  ROOT, 'android', 'core', 'importer', 'src', 'main', 'kotlin',
  'com', 'koodoreader', 'core', 'importer', 'BookRules.kt',
);
const VERBOSE = process.argv.includes('--verbose');

function fail(msg) {
  console.error(`[check-import-rules] ${msg}`);
  process.exit(2);
}

function usage() {
  console.log('Usage: node scripts/check-import-rules.js [--verbose]');
  console.log('  Verifies the native registry against the desktop and WebView lists,');
  console.log('  allowing CBR only in those legacy lists.');
}

/**
 * Extract a balanced-paren call body, e.g. `listOf(...)` / `mapOf(...)`.
 * Anchored on the `val` declaration (not the bare name, which can appear
 * in doc comments first).
 */
function extractCall(src, name) {
  const start = src.indexOf(`val ${name}`);
  if (start < 0) fail(`val ${name} not found in BookRules.kt`);
  const open = src.indexOf('(', start);
  if (open < 0) fail(`${name} ( not found in BookRules.kt`);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    const ch = src[i];
    if (ch === '(') depth++;
    else if (ch === ')') {
      depth--;
      if (depth === 0) return src.slice(open + 1, i);
    }
  }
  fail(`${name}(...) is unbalanced in BookRules.kt`);
  return '';
}

/** Parse Kotlin rules: { extensions, mime, maxFiles, folderDepth, folderEvent }. */
function parseKotlin(file) {
  const src = fs.readFileSync(file, 'utf8');
  const extBody = extractCall(src, 'BOOK_EXTENSIONS');
  const mimeBody = extractCall(src, 'MIME_BY_EXT');
  let extensions = [...extBody.matchAll(/"([^"]+)"/g)].map((m) => m[1]);
  let mime = {};
  for (const m of mimeBody.matchAll(/"([^"]+)"\s+to\s+"([^"]+)"/g)) mime[m[1]] = m[2];
  if (extensions.length === 0) {
    const formatFile = path.join(path.dirname(file), 'BookFormat.kt');
    if (!fs.existsSync(formatFile)) fail(`missing registry file ${formatFile}`);
    const formatSrc = fs.readFileSync(formatFile, 'utf8');
    const formats = [...formatSrc.matchAll(/^\s*[A-Z][A-Z0-9]*\("([^"]+)",\s*"([^"]+)"\)/gm)];
    if (formats.length === 0) fail('BookFormat registry parsed empty');
    extensions = formats.map((m) => m[1]);
    mime = Object.fromEntries(formats.map((m) => [m[1], m[2]]));
  }
  const num = (name) => {
    // Tolerate optional Kotlin type annotations: `const val X: Int = 1000`.
    const m = src.match(new RegExp(`const val ${name}[^=]*=\\s*(\\d+)`));
    if (!m) fail(`const val ${name} not found in BookRules.kt`);
    return Number(m[1]);
  };
  const str = (name) => {
    const m = src.match(new RegExp(`const val ${name}[^=]*=\\s*"([^"]+)"`));
    if (!m) fail(`const val ${name} not found in BookRules.kt`);
    return m[1];
  };
  return {
    extensions,
    mime,
    maxFiles: num('MAX_FILES'),
    folderDepth: num('FOLDER_DEPTH'),
    folderEvent: str('FOLDER_EVENT'),
  };
}

/** Desktop import list: `supportedFormats = [".epub", ...]` in common.ts. */
function parseDesktopList(file) {
  const src = fs.readFileSync(file, 'utf8');
  const m = src.match(/export const supportedFormats = \[([\s\S]*?)\]/);
  if (!m) fail('supportedFormats array not found in src/utils/common.ts');
  const exts = [...m[1].matchAll(/"\.([a-z0-9]+)"/gi)].map((mm) => mm[1].toLowerCase());
  if (exts.length === 0) fail('supportedFormats parsed empty in src/utils/common.ts');
  return exts;
}

function diffSets(jsArr, ktArr, label) {
  const jsSet = new Set(jsArr);
  const ktSet = new Set(ktArr);
  const missing = [...jsSet].filter((x) => !ktSet.has(x));
  const extra = [...ktSet].filter((x) => !jsSet.has(x));
  if (missing.length || extra.length) {
    fail(
      `${label} set drift: JS/desktop=[${missing.join(', ')}] missing in Kotlin; ` +
        `Kotlin-only=[${extra.join(', ')}]`,
    );
  }
}

function main() {
  if (process.argv.includes('--help') || process.argv.includes('-h')) {
    usage();
    process.exit(0);
  }
  for (const f of [JS_FILE, DESKTOP_LIST_FILE, KT_FILE]) {
    if (!fs.existsSync(f)) fail(`missing rules file: ${f}`);
  }

  const js = require(JS_FILE); // pure CommonJS, no side effects
  const desktop = parseDesktopList(DESKTOP_LIST_FILE);
  const kt = parseKotlin(KT_FILE);

  // 1) Native formats match the desktop list except deliberately unsupported CBR.
  diffSets(desktop.filter((ext) => ext !== 'cbr'), kt.extensions, 'BookFormat vs common.ts');
  // 2) Legacy WebView CBR remains in its protocol but is not a native format.
  const ktSet = new Set(kt.extensions);
  for (const ext of js.BOOK_EXTENSIONS) {
    if (ext === 'cbr') continue;
    if (!ktSet.has(ext)) fail(`folderBridge "${ext}" missing from BookRules`);
  }
  // 3) shared MIME mappings agree
  for (const [ext, mime] of Object.entries(js.MIME_BY_EXT)) {
    if (ext === 'cbr') continue;
    if (kt.mime[ext] !== mime) {
      fail(`MIME_BY_EXT[${ext}] drift (JS "${mime}" vs Kotlin "${kt.mime[ext]}")`);
    }
  }
  // 4) shared constants
  if (js.MAX_FILES !== kt.maxFiles) fail(`MAX_FILES differs (JS ${js.MAX_FILES} vs Kotlin ${kt.maxFiles})`);
  if (js.FOLDER_DEPTH !== kt.folderDepth) fail(`FOLDER_DEPTH differs (JS ${js.FOLDER_DEPTH} vs Kotlin ${kt.folderDepth})`);
  if (js.FOLDER_EVENT !== kt.folderEvent) fail(`FOLDER_EVENT differs (JS "${js.FOLDER_EVENT}" vs Kotlin "${kt.folderEvent}")`);

  if (VERBOSE) {
    console.log(`[check-import-rules] ${kt.extensions.length} native extensions (CBR excluded), ` +
      `legacy folderBridge has ${js.BOOK_EXTENSIONS.length}, MAX_FILES=${kt.maxFiles}, ` +
      `FOLDER_DEPTH=${kt.folderDepth}, FOLDER_EVENT="${kt.folderEvent}" — rules in lock-step.`);
  }
  process.exit(0);
}

main();
