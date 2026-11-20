#!/usr/bin/env node
// normalize-eol.js — restore LF line endings in the given files.
//
// The DSH file tools (edit/write) rewrite text files with CRLF on this
// Windows host. The repository stores LF blobs (git config has no
// core.autocrlf, and every committed blob has 0 CR bytes), so a CRLF
// rewrite shows up as a whole-file diff. Run this after every edit.
//
//   node scripts/normalize-eol.js <file> [<file> ...]
//
// Only touches files that actually contain CRLF; already-LF files are
// left byte-identical.
'use strict';

const fs = require('fs');

let changed = 0;
for (const file of process.argv.slice(2)) {
  if (!fs.existsSync(file)) {
    console.error(`[normalize-eol] skip (missing): ${file}`);
    continue;
  }
  const before = fs.readFileSync(file);
  if (!before.includes(0x0d)) {
    console.log(`[normalize-eol] ok (already LF): ${file}`);
    continue;
  }
  const after = Buffer.from(before.toString('utf8').replace(/\r\n/g, '\n'), 'utf8');
  fs.writeFileSync(file, after);
  changed += 1;
  console.log(`[normalize-eol] normalized to LF: ${file}`);
}
console.log(`[normalize-eol] ${changed} file(s) rewritten`);
