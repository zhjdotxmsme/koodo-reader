#!/usr/bin/env node
/*
 * check-p6-entries.js — is every delivered P6 UI actually reachable from the
 * native shell?
 *
 * Why: the completeness audit found the defining failure of this codebase's
 * native track — modules that compile, pass their tests, and are never mounted
 * (docs/android-completeness-2026-09-24.md §4: "编译期在、运行期不用"). Hand-
 * auditing that does not scale and does not stay true: TTS shipped 24 files with
 * no manifest entry, PDF shipped a screen whose body was placeholder text.
 *
 * This guard turns gap #2 ("P6 六个模块无入口") into a checked list:
 *   - `wired`   → the screen symbol must be referenced from :app;
 *   - `pending` → it must NOT be referenced yet, and the reason must be recorded
 *                 here, so the remaining backlog is explicit instead of folklore.
 * Wiring a screen without updating this matrix fails the check, which keeps the
 * list honest in both directions.
 *
 * Usage: node scripts/check-p6-entries.js [--verbose]   # exit 0/1
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const ANDROID = path.join(ROOT, 'android');
const APP_SRC = path.join(ANDROID, 'app', 'src', 'main', 'java');
const VERBOSE = process.argv.includes('--verbose');

/**
 * The P6 surfaces and their wiring state.
 *
 * Keep the `pending` reasons concrete: they are the answer to "why is this not
 * reachable yet", and they become stale (and fail the check) the moment someone
 * wires the screen.
 */
const MATRIX = [
  {
    module: 'feature/stats',
    screen: 'StatsScreen',
    state: 'wired',
    // Reachability is checked in two hops: `hostEntry` must be navigated to from
    // the nav graph, and it must mount `screen`. A screen that is merely
    // referenced by a dead file therefore does NOT count as wired.
    hostFile: 'shell/StatsRoute.kt',
    hostEntry: 'StatsRoute',
  },
  {
    module: 'feature/dictionary',
    screen: 'DictManagementScreen',
    state: 'pending',
    reason: 'needs the app-owned DictRepository + a SAF import flow for .mdx/.mdd packs',
  },
  {
    module: 'feature/tts',
    screen: 'TtsControlSheet',
    state: 'pending',
    reason: 'needs a text reader to control (EPUB host = gap #1); its manifest/service wiring is done',
  },
  {
    module: 'feature/translate',
    screen: 'TranslationPopup',
    state: 'pending',
    reason: 'needs a text selection source (reader); the popup is stateless and ready',
  },
];

/** Modules that deliberately have no screen to mount, with the real integration point. */
const NO_UI = [
  {
    module: 'feature/ocr',
    integrationPoint: 'reader page scan + PdfHostBridge host (feature/ocr ships no composable)',
  },
  {
    module: 'core/locale',
    integrationPoint: 'reader text pipeline (OpenCC conversion), not a screen',
  },
];

const failures = [];

function readDirKt(dir) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  const walk = (current) => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.kt')) out.push(full);
    }
  };
  walk(dir);
  return out;
}

function check(name, ok, detail) {
  if (ok) {
    if (VERBOSE) console.log(`  PASS  ${name}`);
  } else {
    failures.push(`${name}${detail ? ' — ' + detail : ''}`);
  }
}

/** Top-level composables a module exposes (`@Composable` + `fun Name(`). */
function composablesOf(moduleDir) {
  const names = new Set();
  for (const file of readDirKt(path.join(moduleDir, 'src', 'main'))) {
    const text = fs.readFileSync(file, 'utf8');
    const re = /@Composable\s+(?:(?:public|internal|private)\s+)?fun\s+([A-Z]\w+)\s*\(/g;
    let m;
    while ((m = re.exec(text)) !== null) names.add(m[1]);
  }
  return names;
}

function main() {
  const hostFiles = readDirKt(APP_SRC);
  if (hostFiles.length === 0) {
    console.error(`[check-p6-entries] no :app sources under ${APP_SRC}`);
    process.exit(2);
  }
  const hostText = hostFiles.map((f) => fs.readFileSync(f, 'utf8')).join('\n');
  const navGraphPath = path.join(APP_SRC, 'com', 'koodoreader', 'reader', 'shell', 'ShellNavHost.kt');
  const navGraph = fs.existsSync(navGraphPath) ? fs.readFileSync(navGraphPath, 'utf8') : '';
  check('the native shell nav graph exists', navGraph.length > 0, navGraphPath);

  let wired = 0;
  let pending = 0;

  console.log('P6 entry points (:app must mount the screen):');
  for (const entry of MATRIX) {
    const moduleDir = path.join(ANDROID, ...entry.module.split('/'));
    const available = composablesOf(moduleDir);
    check(
      `${entry.module} still exposes ${entry.screen}`,
      available.has(entry.screen),
      available.size
        ? `found: ${[...available].sort().join(', ')}`
        : 'no composable found at all — did the module move?',
    );

    const referenced = new RegExp(`\\b${entry.screen}\\s*\\(`).test(hostText);
    if (entry.state === 'wired') {
      wired++;
      let reachable = false;
      if (entry.hostFile && entry.hostEntry) {
        const hostPath = path.join(APP_SRC, 'com', 'koodoreader', 'reader', ...entry.hostFile.split('/'));
        const hostSource = fs.existsSync(hostPath) ? fs.readFileSync(hostPath, 'utf8') : null;
        check(`${entry.hostFile} exists`, hostSource !== null, hostPath);
        check(
          `${entry.hostEntry} is navigated to from the nav graph`,
          new RegExp(`\\b${entry.hostEntry}\\s*\\(`).test(navGraph),
          'a host that nothing navigates to is still unreachable',
        );
        check(
          `${entry.hostEntry} mounts ${entry.screen}`,
          hostSource !== null && new RegExp(`\\b${entry.screen}\\s*\\(`).test(hostSource),
        );
        reachable = new RegExp(`\\b${entry.hostEntry}\\s*\\(`).test(navGraph) &&
          hostSource !== null && new RegExp(`\\b${entry.screen}\\s*\\(`).test(hostSource);
      } else {
        reachable = new RegExp(`\\b${entry.screen}\\s*\\(`).test(navGraph);
        check(
          `${entry.screen} is mounted from the nav graph`,
          reachable,
          'delivered but unreachable — this is exactly what the audit flagged',
        );
      }
      console.log(`  [wired]   ${entry.screen}${reachable ? '' : '  (UNREACHABLE)'}`);
    } else {
      pending++;
      check(
        `${entry.screen} is not mounted yet, as the matrix says`,
        !referenced,
        'it IS mounted now — move it to "wired" here (with its host file)',
      );
      console.log(`  [pending] ${entry.screen} — ${entry.reason}`);
    }
  }

  console.log('\nNo-screen modules (integration point is not a screen):');
  for (const entry of NO_UI) {
    const moduleDir = path.join(ANDROID, ...entry.module.split('/'));
    check(`${entry.module} exists`, fs.existsSync(moduleDir));
    console.log(`  [no-ui]   ${entry.module} — ${entry.integrationPoint}`);
  }

  if (failures.length > 0) {
    console.error(`\n[check-p6-entries] ${failures.length} check(s) FAILED`);
    for (const f of failures) console.error(`  - ${f}`);
    process.exit(1);
  }
  console.log(
    `\n[check-p6-entries] OK — P6 surfaces: ${wired} wired, ${pending} pending (reasons above), ` +
    `${NO_UI.length} with no screen`,
  );
}

main();
