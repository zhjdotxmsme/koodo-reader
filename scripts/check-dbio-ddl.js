#!/usr/bin/env node
// check-dbio-ddl.js — guard: the desktop DDL constants in
// android/core/dbio/.../DesktopDdl.kt must stay VERBATIM (modulo whitespace)
// with schema.lock (the frozen desktop schema, `databases.<t>.tables.<t>.sql`).
//
//   node scripts/check-dbio-ddl.js        # verify, exit 0/2
//   node scripts/check-dbio-ddl.js --verbose
//
// Checked per table (books/notes/bookmarks/plugins/words):
//   1. ddl(<t>) constant == schema.lock sql (whitespace-insensitive)
//   2. COLUMNS[<t>] == schema.lock column names, same order
// Rationale: the native track exports .db files the desktop engine must read
// back ("双向可读"); any DDL drift breaks the round trip, so CI enforces it.
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const LOCK = path.join(ROOT, 'schema.lock');
const DDL_FILE = path.join(
  ROOT, 'android', 'core', 'dbio', 'src', 'main', 'kotlin',
  'com', 'koodoreader', 'core', 'dbio', 'DesktopDdl.kt',
);
const TABLES = ['books', 'notes', 'bookmarks', 'plugins', 'words'];
const VERBOSE = process.argv.includes('--verbose');

function fail(msg) {
  console.error(`[check-dbio-ddl] ${msg}`);
  process.exit(2);
}

const usage = () => {
  console.log('Usage: node scripts/check-dbio-ddl.js [--verbose]');
  console.log('  Verifies DesktopDdl.kt against schema.lock (native → desktop DDL guard).');
};

const normalize = (s) => s.replace(/\s+/g, ' ').trim().toLowerCase();

/** Extract a Kotlin raw-string constant: `const val NAME = """..."""`. */
function rawConst(src, name) {
  const re = new RegExp(`const val ${name}\\s*=\\s*"""([\\s\\S]*?)"""`);
  const m = re.exec(src);
  if (!m) fail(`raw string constant ${name} not found in DesktopDdl.kt`);
  return m[1];
}

/** Extract a `COLUMNS` mapOf entry for one table -> column names in order. */
function columnsFor(src, table) {
  const re = new RegExp(`"(${table})"\\s+to\\s+listOf\\(([^)]*)\\)`);
  const m = re.exec(src);
  if (!m) fail(`COLUMNS entry "${table}" not found in DesktopDdl.kt`);
  return [...m[2].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
}

function main() {
  if (process.argv.includes('--help') || process.argv.includes('-h')) {
    usage();
    return;
  }
  if (!fs.existsSync(LOCK)) fail(`schema.lock not found: ${LOCK}`);
  if (!fs.existsSync(DDL_FILE)) fail(`DesktopDdl.kt not found: ${DDL_FILE}`);
  const lock = JSON.parse(fs.readFileSync(LOCK, 'utf8'));
  const src = fs.readFileSync(DDL_FILE, 'utf8');

  for (const t of TABLES) {
    const expected = lock.databases?.[t]?.tables?.[t];
    if (!expected) fail(`schema.lock has no table "${t}"`);
    const expectedSql = normalize(expected.sql);
    const actualSql = normalize(rawConst(src, t.toUpperCase()));
    if (expectedSql !== actualSql) {
      fail(`DDL drift for "${t}":\n  schema.lock: ${expectedSql}\n  DesktopDdl : ${actualSql}`);
    }
    const expectedCols = expected.columns.map((c) => c.name);
    const actualCols = columnsFor(src, t);
    if (JSON.stringify(expectedCols) !== JSON.stringify(actualCols)) {
      fail(
        `COLUMNS drift for "${t}":\n` +
        `  schema.lock: [${expectedCols.join(', ')}]\n` +
        `  DesktopDdl : [${actualCols.join(', ')}]`,
      );
    }
    if (VERBOSE) console.log(`[check-dbio-ddl] ${t}: DDL + ${actualCols.length} columns aligned`);
  }
  console.log(`[check-dbio-ddl] OK — ${TABLES.length} tables aligned with schema.lock`);
}

main();
