#!/usr/bin/env node
// apply-edits.js — apply a JSON edit list to files while PRESERVING the
// repository's LF line endings.
//
// Why: the DSH file tools (edit/write) rewrite text files with CRLF on this
// Windows host, but the repository stores LF blobs (every committed blob has
// 0 CR bytes). Editing via those tools therefore turns a one-line change into
// a whole-file diff.
//
//   node scripts/apply-edits.js <edits.json>
//
// The JSON is a list of { file, find, replace, count? } entries. `find` and
// `replace` are literal strings (not regexes) written with LF newlines. Each
// entry must match exactly `count` (default 1) occurrences, otherwise the
// script fails loudly without writing anything.
//
// Each target file is normalized CRLF -> LF before matching, so the caller's
// `find` string (LF) always matches and the committed blob stays LF.
'use strict';

const fs = require('fs');
const path = require('path');

const editsFile = process.argv[2];
if (!editsFile) {
  console.error('usage: node scripts/apply-edits.js <edits.json>');
  process.exit(2);
}

const edits = JSON.parse(fs.readFileSync(editsFile, 'utf8'));
let failures = 0;

for (const edit of edits) {
  const file = edit.file;
  const find = edit.find;
  const replace = edit.replace;
  const expected = edit.count === undefined ? 1 : edit.count;

  if (!fs.existsSync(file)) {
    console.error(`[apply-edits] MISSING file: ${file}`);
    failures += 1;
    continue;
  }

  const raw = fs.readFileSync(file, 'utf8');
  const before = raw.replace(/\r\n/g, '\n');
  const occurrences = before.split(find).length - 1;
  if (occurrences !== expected) {
    console.error(
      `[apply-edits] ${file}: expected ${expected} occurrence(s) of ` +
        `${JSON.stringify(find.slice(0, 60))}, found ${occurrences}`,
    );
    failures += 1;
    continue;
  }

  const after = before.split(find).join(replace);
  fs.writeFileSync(file, after, 'utf8');
  const eolNote = raw !== before ? ' (normalized CRLF->LF)' : '';
  console.log(
    `[apply-edits] ${file}: replaced ${occurrences} occurrence(s) ` +
      `(${path.basename(file)})${eolNote}`,
  );
}

if (failures > 0) {
  console.error(`[apply-edits] ${failures} edit(s) FAILED — nothing written for those`);
  process.exit(1);
}
console.log('[apply-edits] all edits applied');
