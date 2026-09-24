#!/usr/bin/env node
// verify-desktop-read.js — desktop-read smoke for the native export (P1 收口).
//
//   node scripts/verify-desktop-read.js [zip]   # default: the sample export
//
// Proves the native -> desktop direction of "桌面 .db 双向可读" at the ENGINE
// level: the .db files an Android device exports are opened here with the same
// SQLite engine the desktop restore path uses (sql.js WASM shipped at
// public/lib/sqljs-wasm, see src/utils/file/sqlUtil.ts) and the rows are read
// back field by field.
//
// What this does NOT cover: the Electron GUI click-through (restore dialog).
// That stays a human step (see docs/android-native-migration.md §11).
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const SQLJS_DIR = path.join(ROOT, 'public', 'lib', 'sqljs-wasm');
const DEFAULT_ZIP = path.join(
  ROOT, 'android', 'core', 'dbio', 'build', 'export-sample',
  'KoodoReader-Backup-sample.zip',
);
const VERBOSE = process.argv.includes('--verbose');

function fail(msg) {
  console.error(`[verify-desktop-read] ${msg}`);
  process.exit(2);
}

function ok(msg) {
  console.log(`[verify-desktop-read] ${msg}`);
}

/** Same init path as scripts/android-baseline.js (repo-shipped sql.js). */
async function initSqlJs() {
  let init;
  try {
    init = require(path.join(SQLJS_DIR, 'sql-wasm.js'));
  } catch (e) {
    fail(`sql.js loader unavailable at ${SQLJS_DIR}: ${e.message}`);
  }
  return init({ locateFile: (f) => path.join(SQLJS_DIR, f) });
}

/** Read one config/<table>.db buffer into column-named row objects. */
function readTable(SQL, bytes, table) {
  const db = new SQL.Database(new Uint8Array(bytes));
  try {
    const res = db.exec(`SELECT * FROM "${table}"`);
    if (!res || res.length === 0) return [];
    const { columns, values } = res[0];
    return values.map((row) => {
      const obj = {};
      columns.forEach((c, i) => { obj[c] = row[i]; });
      return obj;
    });
  } finally {
    db.close();
  }
}

async function main() {
  const zipPath = process.argv[2] && !process.argv[2].startsWith('--')
    ? path.resolve(process.argv[2])
    : DEFAULT_ZIP;

  if (!fs.existsSync(zipPath)) {
    fail(
      `export not found: ${zipPath}\n` +
      '  generate it first: gradle :core:dbio:test --tests "*desktop-read smoke*"',
    );
  }

  const JSZip = require('jszip');
  const zip = await JSZip.loadAsync(fs.readFileSync(zipPath));
  const entries = Object.keys(zip.files).filter((n) => !zip.files[n].dir);
  if (VERBOSE) ok(`entries: ${entries.join(', ')}`);

  // Desktop restore REQUIRES config/config.json to exist (isNewBackup check).
  if (!zip.file('config/config.json')) {
    fail('missing config/config.json — desktop restore would reject this zip');
  }

  const SQL = await initSqlJs();
  const problems = [];

  const books = readTable(SQL, await zip.file('config/books.db').async('uint8array'), 'books');
  const notes = readTable(SQL, await zip.file('config/notes.db').async('uint8array'), 'notes');

  // Expected sample content (see BackupBundleTest.sampleBooks/sampleNotes).
  const b = books.find((r) => r.key === 'k1');
  if (!b) problems.push('books: row key=k1 missing');
  else {
    if (b.name !== 'Book') problems.push(`books.name: ${JSON.stringify(b.name)}`);
    if (b.md5 !== 'deadbeef') problems.push(`books.md5: ${JSON.stringify(b.md5)}`);
    if (b.size !== 10) problems.push(`books.size: ${JSON.stringify(b.size)} (expected integer 10)`);
    if (b.page !== 3) problems.push(`books.page: ${JSON.stringify(b.page)} (expected integer 3)`);
    if (b.format !== 'epub') problems.push(`books.format: ${JSON.stringify(b.format)}`);
    if (b.path !== '/b/k1.epub') problems.push(`books.path: ${JSON.stringify(b.path)}`);
  }

  const n = notes.find((r) => r.key === 'n1');
  if (!n) problems.push('notes: row key=n1 missing');
  else {
    if (n.date !== '{"ts":9}') problems.push(`notes.date: ${JSON.stringify(n.date)}`);
    if (n.chapterIndex !== 1) problems.push(`notes.chapterIndex: ${JSON.stringify(n.chapterIndex)}`);
    if (n.color !== 123) problems.push(`notes.color: ${JSON.stringify(n.color)}`);
    if (n.tag !== '[]') problems.push(`notes.tag: ${JSON.stringify(n.tag)}`);
  }

  const cover = await zip.file('cover/k1.jpeg').async('uint8array');
  if (cover.length !== 4) problems.push(`cover bytes: ${cover.length} (expected 4)`);

  // Schema fidelity: the desktop engine must see the frozen column set.
  const cols = readTable(SQL, await zip.file('config/books.db').async('uint8array'), 'books');
  if (cols.length && Object.keys(cols[0]).length !== 12) {
    problems.push(`books column count: ${Object.keys(cols[0]).length} (expected 12)`);
  }

  if (problems.length > 0) {
    console.error('[verify-desktop-read] MISMATCH:');
    problems.forEach((p) => console.error(`  - ${p}`));
    process.exit(2);
  }

  ok(
    `desktop-read smoke PASS — sql.js opened ${books.length} books / ${notes.length} notes, ` +
    `12-column books schema intact, cover present (${zipPath})`,
  );
}

main().catch((e) => fail(e && e.stack ? e.stack : String(e)));
