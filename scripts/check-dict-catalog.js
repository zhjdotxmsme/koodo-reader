#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const CATALOG_FILE = path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'dicts', 'catalog.json');
const DESKTOP_FILE = path.join(ROOT, 'src', 'assets', 'lib', 'kookit-extra-browser.min.js');
const EXPECTED_COUNT = 11;

function extractDesktopDicts(source) {
  const markers = [...source.matchAll(/\bCloudDictList\s*:\s*\[/g)];
  if (markers.length !== 1) {
    throw new Error(`expected one desktop CloudDictList array, found ${markers.length}`);
  }

  const open = markers[0].index + markers[0][0].lastIndexOf('[');
  let depth = 0;
  let quote = null;
  let escaped = false;
  let close = -1;
  for (let i = open; i < source.length; i++) {
    const ch = source[i];
    if (quote !== null) {
      if (escaped) escaped = false;
      else if (ch === '\\') escaped = true;
      else if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") quote = ch;
    else if (ch === '[') depth++;
    else if (ch === ']' && --depth === 0) {
      close = i;
      break;
    }
  }
  if (close < 0) throw new Error('desktop CloudDictList array is unbalanced');

  const body = source.slice(open + 1, close);
  const entryPattern = /\{\s*id\s*:\s*("(?:\\.|[^"\\])*")\s*,\s*name\s*:\s*("(?:\\.|[^"\\])*")\s*,\s*translation\s*:\s*("(?:\\.|[^"\\])*")\s*,\s*source\s*:\s*("(?:\\.|[^"\\])*")\s*\}/y;
  const dicts = [];
  let cursor = 0;
  while (cursor < body.length) {
    while (cursor < body.length && /[\s,]/.test(body[cursor])) cursor++;
    if (cursor === body.length) break;
    entryPattern.lastIndex = cursor;
    const match = entryPattern.exec(body);
    if (!match) throw new Error(`cannot parse desktop CloudDictList entry at offset ${cursor}`);
    dicts.push({ id: JSON.parse(match[1]), name: JSON.parse(match[2]), translation: JSON.parse(match[3]), source: JSON.parse(match[4]) });
    cursor = entryPattern.lastIndex;
  }
  return dicts;
}

function duplicates(items) {
  const seen = new Set();
  const repeated = new Set();
  for (const item of items) {
    if (seen.has(item.id)) repeated.add(item.id);
    seen.add(item.id);
  }
  return [...repeated];
}

function main() {
  let catalog;
  let desktop;
  try {
    catalog = JSON.parse(fs.readFileSync(CATALOG_FILE, 'utf8'));
    desktop = extractDesktopDicts(fs.readFileSync(DESKTOP_FILE, 'utf8'));
  } catch (error) {
    console.error(`[check-dict-catalog] FAIL — ${error.message}`);
    process.exit(1);
  }

  const failures = [];
  const android = Array.isArray(catalog.dicts) ? catalog.dicts : [];
  if (!Array.isArray(catalog.dicts)) failures.push('catalog dicts must be an array');
  if (desktop.length !== EXPECTED_COUNT) failures.push(`desktop has ${desktop.length} entries; expected ${EXPECTED_COUNT}`);
  if (android.length !== EXPECTED_COUNT) failures.push(`Android has ${android.length} entries; expected ${EXPECTED_COUNT}`);

  const androidDuplicates = duplicates(android);
  const desktopDuplicates = duplicates(desktop);
  if (androidDuplicates.length) failures.push(`duplicate Android IDs: ${androidDuplicates.join(', ')}`);
  if (desktopDuplicates.length) failures.push(`duplicate desktop IDs: ${desktopDuplicates.join(', ')}`);

  const androidById = new Map(android.map((item) => [item.id, item]));
  const desktopIds = new Set(desktop.map((item) => item.id));
  const missing = desktop.filter((item) => !androidById.has(item.id)).map((item) => item.id);
  const extra = android.filter((item) => !desktopIds.has(item.id)).map((item) => item.id);
  if (missing.length) failures.push(`missing Android IDs: ${missing.join(', ')}`);
  if (extra.length) failures.push(`extra Android IDs: ${extra.join(', ')}`);

  for (const expected of desktop) {
    const actual = androidById.get(expected.id);
    if (!actual) continue;
    for (const field of ['name', 'translation', 'source']) {
      if (actual[field] !== expected[field]) {
        failures.push(`${expected.id}.${field} differs: Android ${JSON.stringify(actual[field])}, desktop ${JSON.stringify(expected[field])}`);
      }
    }
  }
  for (const item of android) {
    if (item.bytes !== 0) failures.push(`${item.id}.bytes must be 0`);
  }
  if (!Array.isArray(catalog.bundled) || catalog.bundled.length !== 0) failures.push('catalog bundled must be []');

  if (failures.length) {
    console.error(`[check-dict-catalog] FAIL — ${failures.length} check(s) failed`);
    for (const failure of failures) console.error(`  - ${failure}`);
    process.exit(1);
  }
  console.log(`[check-dict-catalog] OK — ${android.length} cloud dicts match desktop (bytes=0, bundled=[])`);
}

main();
