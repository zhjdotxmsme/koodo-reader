#!/usr/bin/env node
// check-import-rules.js — guard: the Kotlin import rules in
// android/core/importer/BookRules.kt must stay in lock-step with the JS
// mirror src/utils/android/folderBridge.js (WebView-track protocol).
//
//   node scripts/check-import-rules.js        # verify, exit 0/2
//   node scripts/check-import-rules.js --verbose
//
// Checked:
//   1. BOOK_EXTENSIONS  — same extensions, same order
//   2. MIME_BY_EXT      — same ext -> MIME mapping
//   3. MAX_FILES / FOLDER_DEPTH constants
//   4. FOLDER_EVENT protocol string
// Rationale: P1 migrates the import rules to Kotlin as the single source of
// truth for the native track; folderBridge.js stays until the WebView track
// retires (P8), so CI must fail when the two drift.
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JS_FILE = path.join(ROOT, 'src', 'utils', 'android', 'folderBridge.js');
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
  console.log('  Verifies BookRules.kt against folderBridge.js (import rules guard).');
}

/** Extract a balanced-paren call body, e.g. `listOf(...)` / `mapOf(...)`. */
function extractCall(src, name) {
  const start = src.indexOf(name);
  if (start < 0) fail(`${name} not found in BookRules.kt`);
  const open = src.indexOf('(', start);
  if (open < 0) fail(`${name}( not found in BookRules.kt`);
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
  const extensions = [...extBody.matchAll(/"([^"]+)"/g)].map((m) => m[1]);
  const mime = {};
  for (const m of mimeBody.matchAll(/"([^"]+)"\s+to\s+"([^"]+)"/g)) mime[m[1]] = m[2];
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

function diffArrays(jsArr, ktArr, label) {
  if (jsArr.length !== ktArr.length) {
    fail(`${label}: length differs (JS ${jsArr.length} vs Kotlin ${ktArr.length})`);
  }
  for (let i = 0; i < jsArr.length; i++) {
    if (jsArr[i] !== ktArr[i]) {
      fail(`${label}: index ${i} differs (JS "${jsArr[i]}" vs Kotlin "${ktArr[i]}")`);
    }
  }
}

function diffMaps(jsMap, ktMap, label) {
  const jsKeys = Object.keys(jsMap).sort();
  const ktKeys = Object.keys(ktMap).sort();
  diffArrays(jsKeys, ktKeys, `${label} keys`);
  for (const k of jsKeys) {
    if (jsMap[k] !== ktMap[k]) {
      fail(`${label}[${k}]: differs (JS "${jsMap[k]}" vs Kotlin "${ktMap[k]}")`);
    }
  }
}

function main() {
  if (process.argv.includes('--help') || process.argv.includes('-h')) {
    usage();
    process.exit(0);
  }
  if (!fs.existsSync(JS_FILE)) fail(`missing JS rules: ${JS_FILE}`);
  if (!fs.existsSync(KT_FILE)) fail(`missing Kotlin rules: ${KT_FILE}`);

  // The JS module is pure (CommonJS, no side effects) — require it directly.
  const js = require(JS_FILE);
  const kt = parseKotlin(KT_FILE);

  diffArrays(js.BOOK_EXTENSIONS, kt.extensions, 'BOOK_EXTENSIONS');
  diffMaps(js.MIME_BY_EXT, kt.mime, 'MIME_BY_EXT');
  if (js.MAX_FILES !== kt.maxFiles) {
    fail(`MAX_FILES differs (JS ${js.MAX_FILES} vs Kotlin ${kt.maxFiles})`);
  }
  if (js.FOLDER_DEPTH !== kt.folderDepth) {
    fail(`FOLDER_DEPTH differs (JS ${js.FOLDER_DEPTH} vs Kotlin ${kt.folderDepth})`);
  }
  if (js.FOLDER_EVENT !== kt.folderEvent) {
    fail(`FOLDER_EVENT differs (JS "${js.FOLDER_EVENT}" vs Kotlin "${kt.folderEvent}")`);
  }

  if (VERBOSE) {
    console.log(`[check-import-rules] ${kt.extensions.length} extensions, ` +
      `MAX_FILES=${kt.maxFiles}, FOLDER_DEPTH=${kt.folderDepth}, ` +
      `FOLDER_EVENT="${kt.folderEvent}" — JS and Kotlin in lock-step.`);
  }
  process.exit(0);
}

main();