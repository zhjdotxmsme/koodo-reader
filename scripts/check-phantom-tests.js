#!/usr/bin/env node
/*
 * check-phantom-tests.js — find test classes that never execute.
 *
 * Why: a class can look like coverage and contribute zero results. Three real
 * instances in this repo:
 *   1. `ImportPipelineTest` declared `@Test suspend fun` — JUnit 5 cannot invoke
 *      a suspend test method, so the whole class produced no TEST-*.xml while
 *      docs/android-baseline.json cited its 1000-book case as evidence;
 *   2. modules that are not registered in android/settings.gradle never build at
 *      all, so any test under them is decoration;
 *   3. `core/designsystem` sat in KNOWN_UNBUILT for exactly that reason. Its 5
 *      test files had 26 compile errors and had never been compiled, and once
 *      registered they exposed a real bug in AppearanceCodec (decode() returned
 *      every string with literal quotes, so ThemeKind silently fell back to
 *      DEFAULT). An allow-listed warning is not a substitute for a build entry,
 *      so case 2 is now a failure and KNOWN_UNBUILT is empty.
 *
 * The check compares, per module: every class/object that *declares* `@Test`
 * against the classes present in `build/test-results/**` (plus the module
 * registration in settings.gradle). Self-check runners, fixtures and builders
 * are ignored — they have no `@Test` and are invoked through `selfCheck` tasks.
 *
 * Usage: node scripts/check-phantom-tests.js [--verbose]   # exit 0/1
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const ANDROID = path.join(ROOT, 'android');
const VERBOSE = process.argv.includes('--verbose');

const failures = [];

function walkFiles(dir, predicate, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walkFiles(full, predicate, out);
    else if (predicate(full)) out.push(full);
  }
  return out;
}

function readDirKt(dir, predicate = () => true) {
  return walkFiles(dir, (f) => f.endsWith('.kt') && predicate(f));
}

/** Module dirs (relative to android/) that own a src/test source set. */
function moduleDirs() {
  const modules = [];
  for (const group of fs.readdirSync(ANDROID, { withFileTypes: true })) {
    if (!group.isDirectory() || group.name.startsWith('.') || group.name === 'build') continue;
    const groupDir = path.join(ANDROID, group.name);
    if (fs.existsSync(path.join(groupDir, 'src', 'test'))) {
      modules.push(group.name);
      continue;
    }
    for (const child of fs.readdirSync(groupDir, { withFileTypes: true })) {
      if (!child.isDirectory()) continue;
      if (fs.existsSync(path.join(groupDir, child.name, 'src', 'test'))) {
        modules.push(`${group.name}/${child.name}`);
      }
    }
  }
  return modules.sort();
}

function settingsIncludes() {
  const raw = fs.readFileSync(path.join(ANDROID, 'settings.gradle'), 'utf8');
  // Strip comments BEFORE matching. The include regex is unanchored, so a
  // commented-out `// include ':x'` used to count as "registered" — and this
  // file legitimately contains commented-out includes and explanatory comments
  // naming modules. Verified by removing the `:core:designsystem` include: the
  // guard still reported OK until this stripping was added.
  const settings = raw
    .replace(/\/\*[\s\S]*?\*\//g, '') // block comments
    .replace(/\/\/[^\n]*/g, ''); // line comments
  const included = new Set();
  const re = /include\s+['"]([^'"]+)['"]/g;
  let m;
  while ((m = re.exec(settings)) !== null) included.add(m[1].replace(/^:/, '').replace(/:/g, '/'));
  // Gradle also accepts a list form: include ':a', ':b'
  return included;
}

/**
 * Test classes that one test source file contributes.
 *
 * Two traps this avoids:
 *  - a file can hold a *helper* class next to the test class (e.g.
 *    `AnnotationStoreTest.kt` declares `AnnotationStore`); the helper has no
 *    `@Test` of its own, so `@Test` is matched **inside each class body** by
 *    brace counting rather than per file;
 *  - `@Nested` inner classes are reported by Gradle as `Outer$Inner.xml` with no
 *    XML for the outer class, so "ran" must accept a `$`-suffixed result.
 */
function testClassesIn(file) {
  const text = fs.readFileSync(file, 'utf8');
  if (!/@Test\b/.test(text)) return [];
  const pkg = (text.match(/^\s*package\s+([\w.]+)/m) || [])[1] || '';
  const out = [];
  const re = /^(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+)*(class|object)\s+([A-Z]\w+)[^{]*\{/gm;
  let m;
  while ((m = re.exec(text)) !== null) {
    const bodyStart = m.index + m[0].length - 1; // at the '{'
    let depth = 0;
    let i = bodyStart;
    for (; i < text.length; i++) {
      if (text[i] === '{') depth++;
      else if (text[i] === '}') {
        depth--;
        if (depth === 0) break;
      }
    }
    const body = text.slice(bodyStart, i);
    if (/@Test\b/.test(body)) out.push(pkg ? `${pkg}.${m[2]}` : m[2]);
  }
  return out;
}

/** FQCNs that produced results, keyed by module dir (nested classes included). */
function resultClassesByModule() {
  const map = new Map();
  for (const module of moduleDirs()) {
    const dir = path.join(ANDROID, module, 'build', 'test-results');
    const names = new Set();
    for (const file of walkFiles(dir, (f) => path.basename(f).startsWith('TEST-') && f.endsWith('.xml'))) {
      const base = path.basename(file).replace(/^TEST-/, '').replace(/\.xml$/, '');
      names.add(base);
      names.add(base.split('$')[0]); // @Nested: the outer class has no own XML
    }
    map.set(module, names);
  }
  return map;
}

/**
 * Intentional exceptions: modules that exist on disk with `src/test` but are
 * deliberately NOT in android/settings.gradle. Map is module dir → reason, so
 * every exception has to justify itself in review.
 *
 * Kept EMPTY on purpose. It previously held `core/designsystem`, which is how
 * that module rotted: the allow-list turned a loud "nothing here is compiled"
 * warning into an accepted line of output, and the module sat unregistered with
 * 26 compile errors in its test sources and a quoting bug in AppearanceCodec
 * that its 72 never-executed tests were written to catch. It is now registered,
 * so the exception was removed rather than left as a stale entry that would
 * silently swallow a future regression. Adding a module here requires a reason
 * string — and a better answer is usually to register or delete the module.
 */
const KNOWN_UNBUILT = new Map();

function main() {
  const included = settingsIncludes();
  const results = resultClassesByModule();
  let checked = 0;
  const phantoms = [];
  const unbuilt = new Set();

  for (const module of moduleDirs()) {
    const testRoot = path.join(ANDROID, module, 'src', 'test');
    const classes = new Set();
    for (const file of readDirKt(testRoot)) {
      for (const name of testClassesIn(file)) classes.add(name);
    }
    if (classes.size === 0) continue;
    const registered = included.has(module);
    if (!registered) unbuilt.add(module);
    const produced = results.get(module) || new Set();
    for (const name of classes) {
      checked++;
      if (!registered) continue;
      if (!produced.has(name)) {
        phantoms.push({ module, name, reason: 'declares @Test but produced no test-results XML' });
      } else if (VERBOSE) {
        console.log(`  PASS  ${module}: ${name}`);
      }
    }
  }

  for (const module of unbuilt) {
    const reason = KNOWN_UNBUILT.get(module);
    if (reason) {
      console.warn(
        `[check-phantom-tests] WARNING ${module}: not in android/settings.gradle — ` +
        `nothing in it is compiled or run (accepted: ${reason})`,
      );
      continue;
    }
    // A module on disk that declares @Test but is not registered contributes
    // exactly zero results while looking like coverage. This is a failure, not
    // a warning: tolerating it is what let core/designsystem rot.
    console.error(
      `[check-phantom-tests] ${module}: declares @Test but is NOT in android/settings.gradle — ` +
      'nothing in it is compiled or executed. Register it (include \':…\') or delete it; ' +
      'if it is intentional, add it to KNOWN_UNBUILT with a reason.',
    );
    failures.push({ module, name: '(whole module)', reason: 'module not registered in settings.gradle' });
  }

  if (phantoms.length > 0) {
    console.error(`[check-phantom-tests] ${phantoms.length} test class(es) never execute:`);
    for (const p of phantoms) console.error(`  - ${p.module}: ${p.name} — ${p.reason}`);
    failures.push(...phantoms);
  }

  if (failures.length > 0) {
    console.error(
      '\n[check-phantom-tests] A class that declares @Test and produces no results is not coverage.\n' +
      'Suspend test methods (JUnit 5 cannot invoke them), unregistered modules and typos all look like this.',
    );
    process.exit(1);
  }
  console.log(
    `[check-phantom-tests] OK — ${checked} test class(es) across ${moduleDirs().length} module(s) produce results` +
    (unbuilt.size ? `; ${unbuilt.size} module(s) outside the build (see warning)` : ''),
  );
}

main();
