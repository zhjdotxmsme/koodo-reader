#!/usr/bin/env node
// check-room-schema.js — guard: the Room entities in android/core/data must
// stay column-for-column aligned with the desktop schema frozen in
// schema.lock (see docs/adr/ADR-002-cfi-compat.md).
//
//   node scripts/check-room-schema.js        # verify, exit 0/2
//   node scripts/check-room-schema.js --verbose
//
// Checked per table (books/notes/bookmarks/plugins/words):
//   1. same column name set AND order as schema.lock
//   2. same primary-key column
// Column Kotlin types are intentionally NOT compared: desktop DDL uses quirky
// type names ("object"/"array"/"string") that Room deliberately normalises to
// TEXT/INTEGER (see android/core/data/build.gradle header).
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const LOCK = path.join(ROOT, 'schema.lock');
const ENTITY_DIR = path.join(
  ROOT, 'android', 'core', 'data', 'src', 'main', 'kotlin',
  'com', 'koodoreader', 'core', 'data', 'entity',
);
const TABLES = ['books', 'notes', 'bookmarks', 'plugins', 'words'];

function fail(msg) {
  console.error(`[check-room-schema] ${msg}`);
  process.exit(2);
}

function usage() {
  console.log('Usage: node scripts/check-room-schema.js [--verbose]');
  console.log('  Verifies android/core/data Room entities against schema.lock.');
}

/** Parse one entity file into { table, columns: [{name, pk}] }. */
function parseEntityFile(file) {
  const src = fs.readFileSync(file, 'utf8');
  const table = /@Entity\(\s*tableName\s*=\s*"([^"]+)"/.exec(src)?.[1];
  if (!table) fail(`no @Entity(tableName=...) in ${path.relative(ROOT, file)}`);
  const columns = [];
  // Property lines look like:
  //   @PrimaryKey @ColumnInfo(name = "key") val key: String,
  //   @ColumnInfo(name = "name") val name: String? = null,
  const re = /(@PrimaryKey\s+)?@ColumnInfo\(name\s*=\s*"([^"]+)"\)/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    columns.push({ name: m[2], pk: Boolean(m[1]) });
  }
  if (columns.length === 0) fail(`no @ColumnInfo fields in ${path.relative(ROOT, file)}`);
  return { table, columns };
}

function main() {
  const args = process.argv.slice(2);
  if (args.includes('--help') || args.includes('-h')) { usage(); process.exit(0); }
  const verbose = args.includes('--verbose');

  if (!fs.existsSync(LOCK)) fail('schema.lock not found — run: node scripts/android-baseline.js --schema bootstrap');
  const lock = JSON.parse(fs.readFileSync(LOCK, 'utf8'));
  if (!fs.existsSync(ENTITY_DIR)) fail(`entity dir missing: ${path.relative(ROOT, ENTITY_DIR)}`);

  const entities = new Map();
  for (const f of fs.readdirSync(ENTITY_DIR).filter((f) => f.endsWith('Entity.kt'))) {
    const e = parseEntityFile(path.join(ENTITY_DIR, f));
    if (entities.has(e.table)) fail(`duplicate @Entity for table "${e.table}"`);
    entities.set(e.table, e);
  }

  const problems = [];
  for (const table of TABLES) {
    const expected = lock.databases?.[table]?.tables?.[table];
    if (!expected) fail(`schema.lock has no table "${table}"`);
    const expectedCols = expected.columns.map((c) => c.name);
    const expectedPk = expected.columns.filter((c) => c.pk > 0).map((c) => c.name);

    const entity = entities.get(table);
    if (!entity) { problems.push(`${table}: no matching @Entity`); continue; }
    const actualCols = entity.columns.map((c) => c.name);
    const actualPk = entity.columns.filter((c) => c.pk).map((c) => c.name);

    if (JSON.stringify(actualCols) !== JSON.stringify(expectedCols)) {
      problems.push(
        `${table}: column mismatch\n` +
        `    schema.lock: [${expectedCols.join(', ')}]\n` +
        `    room entity: [${actualCols.join(', ')}]`,
      );
    }
    if (JSON.stringify(actualPk) !== JSON.stringify(expectedPk)) {
      problems.push(`${table}: primary key mismatch (lock=[${expectedPk}], room=[${actualPk}])`);
    }
    if (verbose) {
      console.log(`[check-room-schema] ${table}: ${actualCols.length} columns, pk=[${actualPk}] OK`);
    }
  }

  // Extra entities are allowed to exist only if they are not one of the five
  // frozen tables — flag unknown table names so additions are deliberate.
  for (const table of entities.keys()) {
    if (!TABLES.includes(table)) {
      problems.push(`unknown @Entity table "${table}" (not in schema.lock tables: ${TABLES.join(', ')})`);
    }
  }

  if (problems.length > 0) {
    console.error('[check-room-schema] MISMATCH:');
    for (const p of problems) console.error(`  - ${p}`);
    console.error('[check-room-schema] fix the entity or regenerate schema.lock deliberately.');
    process.exit(2);
  }
  console.log(`[check-room-schema] OK — ${TABLES.length} tables aligned with schema.lock`);
}

if (require.main === module) main();
module.exports = { parseEntityFile, TABLES };
