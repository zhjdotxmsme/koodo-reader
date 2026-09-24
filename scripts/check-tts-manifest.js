#!/usr/bin/env node
/*
 * check-tts-manifest.js — keep the P6 TTS wiring in `AndroidManifest.xml` in
 * lock-step with the module that implements it.
 *
 * Why: `LockscreenControlsReceiver`'s doc comment claims its `ACTIONS` list and
 * the manifest `<intent-filter>` "cannot drift", but until this guard existed
 * nothing checked that — the receiver, its service and the TTS `<queries>`
 * entry were simply missing from the manifest, so the whole P6 TTS track was
 * unreachable while every test passed. A missing `<queries>` entry is
 * especially quiet: `TextToSpeech` just reports no engines.
 *
 * Checks (the P6-TTS-WIRE acceptance list, machine-verified):
 *   1. POST_NOTIFICATIONS / FOREGROUND_SERVICE /
 *      FOREGROUND_SERVICE_MEDIA_PLAYBACK permissions are declared;
 *   2. `<queries>` asks for `android.intent.action.TTS_SERVICE`;
 *   3. `ForegroundTtsService` is declared with `foregroundServiceType=mediaPlayback`;
 *   4. `LockscreenControlsReceiver` is declared, and its intent-filter actions
 *      are exactly the receiver's `ACTIONS` (MEDIA_BUTTON + every
 *      `TtsMediaCommand.action`).
 *
 * Usage: node scripts/check-tts-manifest.js [--verbose]   # exit 0/1
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const MANIFEST = path.join(ROOT, 'android', 'app', 'src', 'main', 'AndroidManifest.xml');
const COMMANDS = path.join(
  ROOT, 'android', 'feature', 'tts', 'src', 'main', 'kotlin',
  'com', 'koodoreader', 'feature', 'tts', 'TtsMediaCommand.kt',
);
const VERBOSE = process.argv.includes('--verbose');

const failures = [];
const notes = [];

function read(file) {
  if (!fs.existsSync(file)) {
    console.error(`[check-tts-manifest] missing ${file}`);
    process.exit(2);
  }
  return fs.readFileSync(file, 'utf8');
}

function check(name, ok, detail) {
  if (ok) {
    if (VERBOSE) console.log(`  PASS  ${name}`);
  } else {
    failures.push(`${name}${detail ? ' — ' + detail : ''}`);
  }
}

/** `<action android:name="x" />` values inside one `<receiver>` block. */
function receiverActions(manifest, receiverName) {
  const blocks = manifest.split(/<receiver\b/).slice(1);
  const block = blocks.find((b) => b.includes(receiverName));
  if (!block) return null;
  const end = block.indexOf('</receiver>');
  const body = end >= 0 ? block.slice(0, end) : block;
  const actions = [];
  const re = /<action\s+android:name="([^"]+)"/g;
  let m;
  while ((m = re.exec(body)) !== null) actions.push(m[1]);
  return actions;
}

function main() {
  const manifest = read(MANIFEST);
  const commands = read(COMMANDS);

  for (const permission of [
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
  ]) {
    check(
      `declares ${permission}`,
      new RegExp(`<uses-permission\\s+android:name="${permission.replace(/\./g, '\\.')}"`).test(manifest),
    );
  }

  check(
    'queries the platform TTS service',
    /<queries>[\s\S]*?<action\s+android:name="android\.intent\.action\.TTS_SERVICE"\s*\/>[\s\S]*?<\/queries>/.test(manifest),
    'without <queries> Android 11+ hides every installed TTS engine',
  );

  check(
    'declares ForegroundTtsService as a mediaPlayback foreground service',
    /<service[\s\S]*?android:name="com\.koodoreader\.feature\.tts\.ForegroundTtsService"[\s\S]*?foregroundServiceType="mediaPlayback"[\s\S]*?\/>/.test(manifest),
  );

  // The receiver's expected actions: MEDIA_BUTTON (platform) + every enum value.
  const expected = new Set(['android.intent.action.MEDIA_BUTTON']);
  const actionRe = /"((?:com\.koodoreader\.tts\.action\.)[A-Z_]+)"/g;
  let m;
  while ((m = actionRe.exec(commands)) !== null) expected.add(m[1]);
  check('the command table exposes actions to compare', expected.size > 1);

  const declared = receiverActions(manifest, 'LockscreenControlsReceiver');
  check('declares LockscreenControlsReceiver', declared !== null);
  if (declared) {
    const declaredSet = new Set(declared);
    const missing = [...expected].filter((a) => !declaredSet.has(a)).sort();
    const extra = [...declaredSet].filter((a) => !expected.has(a)).sort();
    check(
      'the receiver intent-filter matches LockscreenControlsReceiver.ACTIONS',
      missing.length === 0 && extra.length === 0,
      [
        missing.length ? `missing: ${missing.join(', ')}` : '',
        extra.length ? `not in ACTIONS: ${extra.join(', ')}` : '',
      ].filter(Boolean).join(' | '),
    );
    if (declared.length !== declaredSet.size) {
      notes.push(`intent-filter has ${declared.length - declaredSet.size} duplicate action(s)`);
    }
  }

  // Index.html-free sanity note for reviewers: the icon the service references.
  const service = path.join(
    ROOT, 'android', 'feature', 'tts', 'src', 'main', 'kotlin',
    'com', 'koodoreader', 'feature', 'tts', 'ForegroundTtsService.kt',
  );
  const icon = path.join(
    ROOT, 'android', 'feature', 'tts', 'src', 'main', 'res', 'drawable',
    'ic_tts_notification.xml',
  );
  if (fs.existsSync(service)) {
    check(
      'the service default icon exists as a vector drawable',
      /R\.drawable\.ic_tts_notification/.test(read(service)) && fs.existsSync(icon),
      'ForegroundTtsService must default to the module notification icon',
    );
  }

  if (failures.length > 0) {
    console.error(`[check-tts-manifest] ${failures.length} check(s) FAILED`);
    for (const f of failures) console.error(`  - ${f}`);
    process.exit(1);
  }
  for (const n of notes) console.warn(`[check-tts-manifest] note: ${n}`);
  console.log(
    `[check-tts-manifest] OK — permissions, <queries>, service and receiver ` +
    `(${expected.size} actions) all declared`,
  );
}

main();
