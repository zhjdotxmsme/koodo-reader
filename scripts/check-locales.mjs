#!/usr/bin/env node
// check-locales.mjs — P6 guard: locales + 简繁转换 (OpenCC port).
//
//   node scripts/check-locales.mjs                 # verify, exit 0/2
//   node scripts/check-locales.mjs --verbose
//   node scripts/check-locales.mjs --print-pack-table   # regenerate the doc table
//
// Part A is a superset of `node scripts/sync-locales-android.js --check`
// (same semantics, same failure conditions, same whitelist — which is PARSED
// from that script so the two can never disagree). Parts B–D cover the P6
// additions and are the drift guards for:
//
//   B. android/core/locale module shape + the 39 on-demand locale list must
//      equal `src/assets/locales` minus the bundled subset (ADR-004).
//   C. docs/p6-zh-locale-design.md must carry the sha256 of every remote
//      locale source (that table is the download manifest for runtime packs).
//   D. the curated OpenCC seed must stay parseable and duplicate-free, and the
//      S→T character table must stay reversible (1:1), because T→S is derived
//      from it by value reversal.
//
// Wiring is reported as a WARNING, not a failure: adding ':core:locale' to
// android/settings.gradle and the dependency to android/app/build.gradle is
// outside this task's file scope (see the card constraints).
//
// ESM on purpose (the `.mjs` extension): no dependencies, no child processes —
// the same checks the build-time bridge performs, plus the P6 drift guards.

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const ROOT = path.join(__dirname, '..');
const SRC_DIR = path.join(ROOT, 'src', 'assets', 'locales');
const SYNC_SCRIPT = path.join(__dirname, 'sync-locales-android.js');
const APP_ASSET_DIR = path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'locales');
const LOCALE_MODULE = path.join(ROOT, 'android', 'core', 'locale');
const KOTLIN_DIR = path.join(LOCALE_MODULE, 'src', 'main', 'kotlin', 'com', 'koodoreader', 'core', 'locale');
const TEST_DIR = path.join(LOCALE_MODULE, 'src', 'test', 'kotlin', 'com', 'koodoreader', 'core', 'locale');
const SEED_FILE = path.join(KOTLIN_DIR, 'OpenCcSeed.kt');
const LOADER_FILE = path.join(KOTLIN_DIR, 'LocaleRuntimeLoader.kt');
const DESIGN_DOC = path.join(ROOT, 'docs', 'p6-zh-locale-design.md');
const SETTINGS_GRADLE = path.join(ROOT, 'android', 'settings.gradle');
const APP_GRADLE = path.join(ROOT, 'android', 'app', 'build.gradle');

const VERBOSE = process.argv.includes('--verbose');
const PRINT_TABLE = process.argv.includes('--print-pack-table');
// Diagnostic only: skips the part-A bundled/manifest comparison so the P6
// guards can be verified while a pre-existing sync drift is still unfixed.
// Source validation is always performed; the default behaviour (flag absent) is
// identical to `sync-locales-android.js --check`.
const SKIP_BUNDLED = process.argv.includes('--skip-bundled');

const failures = [];
const warnings = [];

function fail(msg) {
  failures.push(msg);
}

function warn(msg) {
  warnings.push(msg);
}

function ok(msg) {
  if (VERBOSE) console.log(`[check-locales]   ✓ ${msg}`);
}

function read(file) {
  return fs.readFileSync(file, 'utf8');
}

function sha256(text) {
  return crypto.createHash('sha256').update(text).digest('hex');
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// ─── shared policy: the bundled whitelist lives in sync-locales-android.js ────

function bundledWhitelist() {
  if (!fs.existsSync(SYNC_SCRIPT)) {
    fail(`missing build-time locale bridge: ${path.relative(ROOT, SYNC_SCRIPT)}`);
    return [];
  }
  const match = /ANDROID_LOCALES\s*=\s*\[([^\]]*)\]/.exec(read(SYNC_SCRIPT));
  if (!match) {
    fail('cannot parse ANDROID_LOCALES from scripts/sync-locales-android.js');
    return [];
  }
  // the sync script uses single quotes: ['en', 'zh-CN']
  return [...match[1].matchAll(/['"]([^'"]+)['"]/g)].map((m) => m[1]);
}

// ─── A. sources + bundled assets (parity with sync-locales-android.js --check) ─

function checkSources(bundled) {
  const sources = new Map();
  if (!fs.existsSync(SRC_DIR)) {
    fail(`missing desktop locales dir: ${SRC_DIR}`);
    return sources;
  }
  const codes = fs
    .readdirSync(SRC_DIR)
    .filter((name) => name.endsWith('.json'))
    .map((name) => name.slice(0, -'.json'.length))
    .sort();

  for (const code of codes) {
    const raw = read(path.join(SRC_DIR, `${code}.json`));
    let parsed;
    try {
      parsed = JSON.parse(raw); // same validation the sync script performs
    } catch (error) {
      fail(`locale ${code}.json is not valid JSON: ${error.message}`);
      continue;
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      fail(`locale ${code}.json is not a flat object`);
      continue;
    }
    const keys = Object.keys(parsed);
    if (keys.length === 0) fail(`locale ${code} parsed empty`);
    const nonString = keys.filter((key) => typeof parsed[key] !== 'string');
    if (nonString.length > 0) fail(`locale ${code} has non-string values: ${nonString[0]}`);
    sources.set(code, { raw, keys: keys.length, sha256: sha256(raw) });
  }

  if (sources.size !== 41) fail(`expected 41 desktop locales, found ${sources.size}`);
  ok(`desktop sources: ${sources.size} locales, all flat string maps`);
  return sources;
}

function checkBundledAssets(bundled, sources) {
  for (const code of bundled) {
    const source = sources.get(code);
    if (!source) {
      fail(`bundled locale ${code} has no desktop source`);
      continue;
    }
    const outFile = path.join(APP_ASSET_DIR, `${code}.json`);
    if (!fs.existsSync(outFile)) {
      fail(`missing bundled locale: ${path.relative(ROOT, outFile)} — run sync-locales-android.js`);
      continue;
    }
    const bundledRaw = read(outFile);
    if (bundledRaw !== source.raw) {
      fail(`bundled ${code}.json drifted from src/assets/locales — run sync-locales-android.js`);
    } else {
      ok(`bundled ${code}.json: byte-identical, ${source.keys} keys`);
    }
  }

  const manifestFile = path.join(APP_ASSET_DIR, 'manifest.json');
  if (!fs.existsSync(manifestFile)) {
    fail('missing bundled locales manifest.json — run sync-locales-android.js');
    return;
  }
  let manifest;
  try {
    manifest = JSON.parse(read(manifestFile));
  } catch (error) {
    fail(`bundled manifest.json is not valid JSON: ${error.message}`);
    return;
  }
  for (const code of bundled) {
    const source = sources.get(code);
    if (!manifest.locales || !manifest.locales.includes(code)) {
      fail(`manifest missing locale ${code}`);
      continue;
    }
    if (!source) continue;
    if (manifest.source?.[code]?.sha256 !== source.sha256) {
      fail(`manifest sha256 drift for ${code} — run sync-locales-android.js`);
    }
    if (manifest.source?.[code]?.keys !== source.keys) {
      fail(`manifest key-count drift for ${code} — run sync-locales-android.js`);
    } else {
      ok(`manifest ${code}: sha256 + ${source.keys} keys aligned`);
    }
  }
}

// ─── B. P6 module shape + the 39 on-demand locales ───────────────────────────

const REQUIRED_FILES = [
  'build.gradle',
  'src/main/assets/locales/.gitkeep',
  'src/main/kotlin/com/koodoreader/core/locale/ZhConvertEngine.kt',
  'src/main/kotlin/com/koodoreader/core/locale/OpenCcDictionary.kt',
  'src/main/kotlin/com/koodoreader/core/locale/OpenCcSeed.kt',
  'src/main/kotlin/com/koodoreader/core/locale/ZhConvertSettings.kt',
  'src/main/kotlin/com/koodoreader/core/locale/LocaleRuntimeLoader.kt',
  'src/main/kotlin/com/koodoreader/core/locale/LocaleFallbackChain.kt',
  'src/main/kotlin/com/koodoreader/core/locale/FallbackKey.kt',
  'src/main/kotlin/com/koodoreader/core/locale/LocalePackStore.kt',
];

const REQUIRED_TESTS = [
  'ZhConvertEngineTest.kt',
  'OpenCcDictionaryTest.kt',
  'LocaleRuntimeLoaderTest.kt',
  'LocaleFallbackChainTest.kt',
  'ZhConvertSettingsTest.kt',
];

function kotlinList(file, name) {
  const src = read(file);
  const match = new RegExp(`${name}[^=]*=\\s*listOf\\(([\\s\\S]*?)\\)`).exec(src);
  if (!match) return null;
  return [...match[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

function checkModule(bundled, sources) {
  for (const relative of REQUIRED_FILES) {
    if (!fs.existsSync(path.join(LOCALE_MODULE, relative))) {
      fail(`missing module file: android/core/locale/${relative}`);
    }
  }
  if (!fs.existsSync(TEST_DIR)) {
    fail('missing JVM test sources for android/core/locale');
    return;
  }
  const tests = fs.readdirSync(TEST_DIR).filter((name) => name.endsWith('Test.kt'));
  if (tests.length < 4) {
    fail(`expected >= 4 JVM unit tests, found ${tests.length}`);
  }
  for (const required of REQUIRED_TESTS) {
    if (!tests.includes(required)) fail(`missing unit test: ${required}`);
  }
  ok(`module files present, ${tests.length} JVM unit tests`);

  if (!fs.existsSync(LOADER_FILE)) return;
  const remote = kotlinList(LOADER_FILE, 'REMOTE_LOCALE_CODES');
  if (!remote) {
    fail('cannot parse REMOTE_LOCALE_CODES from LocaleRuntimeLoader.kt');
    return;
  }
  const expected = [...sources.keys()].filter((code) => !bundled.includes(code)).sort();
  const actual = [...remote].sort();
  if (actual.join(',') !== expected.join(',')) {
    const missing = expected.filter((code) => !actual.includes(code));
    const extra = actual.filter((code) => !expected.includes(code));
    fail(
      `REMOTE_LOCALE_CODES drifted from the desktop locale set ` +
        `(missing: ${missing.join(',') || 'none'}; unexpected: ${extra.join(',') || 'none'})`,
    );
  } else {
    ok(`REMOTE_LOCALE_CODES: ${actual.length} on-demand locales match the desktop set`);
  }

  const kotlinBundled = kotlinList(LOADER_FILE, 'BUNDLED_LOCALE_CODES');
  if (!kotlinBundled) {
    fail('cannot parse BUNDLED_LOCALE_CODES from LocaleRuntimeLoader.kt');
  } else if (kotlinBundled.join(',') !== [...bundled].join(',')) {
    fail(
      `BUNDLED_LOCALE_CODES (${kotlinBundled.join(', ')}) != ANDROID_LOCALES ` +
        `(${[...bundled].join(', ')}) in sync-locales-android.js`,
    );
  } else {
    ok(`BUNDLED_LOCALE_CODES: ${kotlinBundled.join(', ')} (ADR-004)`);
  }
}

// ─── C. the pack manifest table in the design doc ────────────────────────────

function printPackTable(bundled, sources) {
  const rows = [...sources.keys()]
    .filter((code) => !bundled.includes(code))
    .sort()
    .map((code) => {
      const source = sources.get(code);
      return `| \`${code}\` | ${source.keys} | \`${source.sha256}\` |`;
    });
  console.log(rows.join('\n'));
}

function checkDesignDoc(bundled, sources) {
  if (!fs.existsSync(DESIGN_DOC)) {
    fail(`missing design doc: ${path.relative(ROOT, DESIGN_DOC)}`);
    return;
  }
  const doc = read(DESIGN_DOC);
  if (!/ADR-004/.test(doc)) fail('design doc must reference ADR-004 (i18n subset policy)');

  const remote = [...sources.keys()].filter((code) => !bundled.includes(code));
  const stale = [];
  for (const code of remote) {
    const source = sources.get(code);
    const row = new RegExp(
      `\\|\\s*\`${escapeRegExp(code)}\`\\s*\\|\\s*\\d+\\s*\\|\\s*\`([0-9a-f]{64})\`\\s*\\|`,
    ).exec(doc);
    if (!row) {
      stale.push(`${code} (row missing)`);
    } else if (row[1] !== source.sha256) {
      stale.push(`${code} (sha256 drift)`);
    }
  }
  if (stale.length > 0) {
    fail(
      `docs/p6-zh-locale-design.md pack table is stale: ${stale.slice(0, 5).join(', ')}` +
        `${stale.length > 5 ? ` … (+${stale.length - 5})` : ''} — run: node scripts/check-locales.mjs --print-pack-table`,
    );
  } else {
    ok(`design doc pack table: ${remote.length} sha256 entries current`);
  }
}

// ─── D. curated OpenCC seed integrity ────────────────────────────────────────

function seedSection(source, name) {
  const match = new RegExp(`val ${name}[\\s\\S]*?"""([\\s\\S]*?)"""`).exec(source);
  if (!match) return null;
  const entries = new Map();
  const duplicates = [];
  const malformed = [];
  const identity = [];
  for (const rawLine of match[1].split('\n')) {
    const line = rawLine.trim();
    if (line === '' || line.startsWith('#')) continue;
    const parts = line.split('\t');
    if (parts.length !== 2 || parts[0] === '' || parts[1] === '') {
      malformed.push(JSON.stringify(line));
      continue;
    }
    const [key, value] = parts;
    if (entries.has(key)) duplicates.push(key);
    else entries.set(key, value);
    if (key === value) identity.push(key);
  }
  return { entries, duplicates, malformed, identity };
}

function checkSeed() {
  if (!fs.existsSync(SEED_FILE)) {
    fail(`missing seed dictionaries: ${path.relative(ROOT, SEED_FILE)}`);
    return;
  }
  const source = read(SEED_FILE);
  const minimums = { ST_CHARACTERS: 500, ST_PHRASES: 60, TW_PHRASES: 60, TS_EXTRA_CHARACTERS: 5 };
  for (const [name, minimum] of Object.entries(minimums)) {
    const section = seedSection(source, name);
    if (!section) {
      fail(`OpenCcSeed.kt: cannot parse ${name}`);
      continue;
    }
    if (section.duplicates.length > 0) {
      fail(`OpenCcSeed.$name has duplicated keys: ${section.duplicates.slice(0, 5).join(', ')}`);
    }
    if (section.malformed.length > 0) {
      fail(`OpenCcSeed.$name has malformed lines: ${section.malformed.slice(0, 3).join(', ')}`);
    }
    if (section.identity.length > 0) {
      fail(`OpenCcSeed.$name has identity rules (key == value): ${section.identity.slice(0, 5).join(', ')}`);
    }
    if (section.entries.size < minimum) {
      fail(`OpenCcSeed.$name shrank to ${section.entries.size} entries (< ${minimum})`);
    }
    ok(`seed ${name}: ${section.entries.size} entries, duplicate-free`);
  }

  const characters = seedSection(source, 'ST_CHARACTERS');
  if (!characters) return;
  const reverse = new Map();
  const collisions = [];
  const chaining = [];
  for (const [key, value] of characters.entries) {
    if (reverse.has(value)) collisions.push(`${value} <- ${reverse.get(value)},${key}`);
    else reverse.set(value, key);
    if (characters.entries.has(value)) chaining.push(value);
  }
  if (collisions.length > 0) {
    fail(`ST_CHARACTERS is not reversible (T→S would lose data): ${collisions.slice(0, 5).join(' | ')}`);
  }
  if (chaining.length > 0) {
    fail(`ST_CHARACTERS values are also keys (would chain-convert): ${chaining.slice(0, 5).join(', ')}`);
  }
  ok('ST_CHARACTERS is 1:1 and chain-free (T→S derivation is safe)');
}

// ─── E. wiring status (warning only — those files are out of scope) ──────────

function checkWiring() {
  const included = fs.existsSync(SETTINGS_GRADLE) && /include\s+':core:locale'/.test(read(SETTINGS_GRADLE));
  const depended = fs.existsSync(APP_GRADLE) && /project\(':core:locale'\)/.test(read(APP_GRADLE));
  if (!included) {
    warn(
      "android/settings.gradle does not include ':core:locale' yet — add " +
        "include ':core:locale' (not modified by this task: forbidden file)",
    );
  }
  if (!depended) {
    warn(
      "android/app/build.gradle does not depend on ':core:locale' yet — add " +
        "implementation project(':core:locale') (not modified by this task: forbidden file)",
    );
  }
  if (included && depended) ok('module is wired into the Android build');
}

// ─── main ────────────────────────────────────────────────────────────────────

function main() {
  const bundled = bundledWhitelist();
  const sources = checkSources(bundled);
  const codes = [...sources.keys()];

  if (PRINT_TABLE) {
    printPackTable(bundled, sources);
    return;
  }

  if (SKIP_BUNDLED) {
    warn('--skip-bundled: bundled assets + manifest comparison skipped (pre-existing drift, see docs §7.1)');
  } else {
    checkBundledAssets(bundled, sources);
  }
  checkModule(bundled, sources);
  checkDesignDoc(bundled, sources);
  checkSeed();
  checkWiring();

  for (const message of warnings) console.warn(`[check-locales] WARN ${message}`);
  if (failures.length > 0) {
    console.error(`[check-locales] FAIL — ${failures.length} problem(s):`);
    for (const message of failures) console.error(`  • ${message}`);
    process.exit(2);
  }
  const remote = codes.length - bundled.length;
  console.log(
    `[check-locales] check OK — ${codes.length} desktop locales, ` +
      `${bundled.length} bundled (${bundled.join(', ')}), ${remote} on demand, ` +
      `seed + doc table verified`,
  );
}

main();
