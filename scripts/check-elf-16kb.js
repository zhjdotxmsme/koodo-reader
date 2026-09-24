#!/usr/bin/env node
/**
 * check-elf-16kb.js — 16 KB page-size guard for the shared libraries in an APK (P3/P8).
 *
 * PURE NODE, NO EXTERNAL TOOLS. It reads the APK as a ZIP itself (central
 * directory + raw-deflate via `node:zlib`) and parses the ELF program headers
 * itself. The previous version shelled out to `unzip` and `llvm-readelf`
 * /`readelf`, which do not exist on a stock Windows box or on the GitHub
 * `windows-latest` runner — so the guard silently could not be run at all on
 * half the dev setups (it exited 3). Nothing here needs the NDK either.
 *
 * Why it matters: from Android 15 (and mandatory for Google Play from
 * 2025-11), a `.so` whose LOAD segments are only 4 KB aligned is rejected at
 * load time. Every PT_LOAD segment must therefore be aligned to a positive
 * multiple of 16 KB (0x4000). The first `.so` in this project's APK arrived
 * with `androidx.datastore` (P6-TTS) — see docs/android-baseline-after.json.
 *
 * Usage:
 *   node scripts/check-elf-16kb.js <app.apk>
 *   node scripts/check-elf-16kb.js --dir <apk_unpacked_dir>
 *   node scripts/check-elf-16kb.js --quiet          # only print failures
 *   node scripts/check-elf-16kb.js --json           # machine-readable summary
 *
 * Exit codes:
 *   0 — every `.so` is 16 KB aligned (or the APK has no `.so` at all)
 *   1 — at least one `.so` fails the check
 *   2 — usage / I/O / malformed-archive error
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const PAGE_SIZE_16KB = 0x4000;
const PT_LOAD = 1;

const ARGS = process.argv.slice(2);
const QUIET = ARGS.includes('--quiet');
const AS_JSON = ARGS.includes('--json');
const DIR_FLAG = ARGS.indexOf('--dir');

// ────────────────────────────────────────────────────────────── entry point
// Called at the very bottom so every `const` above (and below) is initialised.

function main() {
    let apkPath = null;
    let dirPath = null;
    if (DIR_FLAG >= 0) {
        dirPath = ARGS[DIR_FLAG + 1];
    } else {
        apkPath = ARGS.find((a) => !a.startsWith('-')) || null;
    }
    if (!apkPath && !dirPath) {
        console.error('Usage: node scripts/check-elf-16kb.js <app.apk>');
        console.error('       node scripts/check-elf-16kb.js --dir <apk_unpacked_dir>');
        process.exit(2);
    }

    let targets; // [{ name, buffer }]
    try {
        targets = apkPath ? readSosFromApk(apkPath) : readSosFromDir(dirPath);
    } catch (err) {
        console.error(`ERROR: ${err.message}`);
        process.exit(2);
    }

    if (targets.length === 0) {
        if (AS_JSON) {
            console.log(JSON.stringify({ source: apkPath || dirPath, checked: 0, failed: 0, results: [] }, null, 2));
        } else if (!QUIET) {
            console.log(`INFO: no .so files in ${apkPath || dirPath} — no 16KB check required`);
        }
        process.exit(0);
    }

    const results = [];
    for (const t of targets) {
        const r = checkElf(t.buffer);
        results.push({ name: t.name, ok: r.ok, reason: r.reason, loads: r.loads });
    }

    const failed = results.filter((r) => !r.ok);

    if (AS_JSON) {
        console.log(JSON.stringify({
            source: apkPath || dirPath,
            checked: results.length,
            failed: failed.length,
            results,
        }, null, 2));
    } else if (!QUIET) {
        console.log(`Checking ${results.length} .so file(s) for 16KB page alignment...`);
        for (const r of results) {
            console.log(`  ${r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.ok ? '' : `  (${r.reason})`}`);
        }
    }

    if (failed.length > 0) {
        if (QUIET) for (const r of failed) console.log(`  FAIL  ${r.name}  (${r.reason})`);
        console.error(`${failed.length} of ${results.length} .so file(s) failed 16KB alignment`);
        process.exit(1);
    }
    if (!QUIET && !AS_JSON) console.log(`All ${results.length} .so file(s) are 16KB-page-aligned.`);
    process.exit(0);
}

// ────────────────────────────────────────────────────────── .so discovery

/** Reads every `lib/**\/*.so` entry out of an APK without unpacking it to disk. */
function readSosFromApk(file) {
    if (!fs.existsSync(file)) throw new Error(`APK not found: ${file}`);
    const zip = fs.readFileSync(file);
    const entries = readZipCentralDirectory(zip);
    const sos = entries.filter((e) => /^lib\/.*\.so$/.test(e.name));
    sos.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
    return sos.map((e) => ({
        name: e.name,
        buffer: readZipEntry(zip, e),
    }));
}

/** Walks a directory (an unpacked APK, or any tree) for `.so` files. */
function readSosFromDir(dir) {
    if (!fs.existsSync(dir)) throw new Error(`Directory not found: ${dir}`);
    const sos = [];
    (function walk(p) {
        const st = fs.statSync(p);
        if (st.isDirectory()) {
            for (const e of fs.readdirSync(p)) walk(path.join(p, e));
            return;
        }
        if (p.endsWith('.so')) sos.push(p);
    })(dir);
    sos.sort();
    return sos.map((p) => ({ name: path.relative(dir, p) || p, buffer: fs.readFileSync(p) }));
}

// ───────────────────────────────────────────────────────── minimal ZIP reader
//
// Only what the guard needs: the central directory (authoritative entry list,
// survives data descriptors / streamed writers), then each entry's local header
// for the real data offset. Supports stored + deflate, and the ZIP64 fields the
// Android build tools emit for very large APKs.

const EOCD_SIG = 0x06054b50;
const ZIP64_EOCD_LOCATOR_SIG = 0x07064b50;
const ZIP64_EOCD_SIG = 0x06064b50;
const CDH_SIG = 0x02014b50;
const LFH_SIG = 0x04034b50;

function readZipCentralDirectory(buf) {
    const eocd = findEndOfCentralDirectory(buf);
    let total = buf.readUInt16LE(eocd + 10);
    let cdOffset = buf.readUInt32LE(eocd + 16);

    // ZIP64: the 32-bit fields saturate; the locator sits right before the EOCD.
    if (total === 0xffff || cdOffset === 0xffffffff) {
        const locator = eocd - 20;
        if (locator >= 0 && buf.readUInt32LE(locator) === ZIP64_EOCD_LOCATOR_SIG) {
            const z64 = Number(buf.readBigUInt64LE(locator + 8));
            if (buf.readUInt32LE(z64) !== ZIP64_EOCD_SIG) {
                throw new Error('malformed ZIP64 end-of-central-directory record');
            }
            total = Number(buf.readBigUInt64LE(z64 + 32));
            cdOffset = Number(buf.readBigUInt64LE(z64 + 48));
        }
    }

    const entries = [];
    let p = cdOffset;
    for (let i = 0; i < total; i++) {
        if (p + 46 > buf.length || buf.readUInt32LE(p) !== CDH_SIG) {
            throw new Error(`malformed central directory at offset ${p}`);
        }
        const method = buf.readUInt16LE(p + 10);
        let compressedSize = buf.readUInt32LE(p + 20);
        let uncompressedSize = buf.readUInt32LE(p + 24);
        const nameLength = buf.readUInt16LE(p + 28);
        const extraLength = buf.readUInt16LE(p + 30);
        const commentLength = buf.readUInt16LE(p + 32);
        let localOffset = buf.readUInt32LE(p + 42);
        const name = buf.toString('utf8', p + 46, p + 46 + nameLength);

        if (compressedSize === 0xffffffff || uncompressedSize === 0xffffffff || localOffset === 0xffffffff) {
            const z = readZip64Extra(buf, p + 46 + nameLength, extraLength, {
                uncompressedSize,
                compressedSize,
                localOffset,
            });
            compressedSize = z.compressedSize;
            uncompressedSize = z.uncompressedSize;
            localOffset = z.localOffset;
        }

        entries.push({ name, method, compressedSize, uncompressedSize, localOffset });
        p += 46 + nameLength + extraLength + commentLength;
    }
    return entries;
}

/** ZIP64 extended-information extra (0x0001): values appear only for saturated fields, in spec order. */
function readZip64Extra(buf, off, len, cur) {
    const end = off + len;
    let p = off;
    while (p + 4 <= end) {
        const id = buf.readUInt16LE(p);
        const size = buf.readUInt16LE(p + 2);
        const body = p + 4;
        if (id === 0x0001) {
            let q = body;
            const out = { ...cur };
            if (cur.uncompressedSize === 0xffffffff && q + 8 <= body + size) {
                out.uncompressedSize = Number(buf.readBigUInt64LE(q));
                q += 8;
            }
            if (cur.compressedSize === 0xffffffff && q + 8 <= body + size) {
                out.compressedSize = Number(buf.readBigUInt64LE(q));
                q += 8;
            }
            if (cur.localOffset === 0xffffffff && q + 8 <= body + size) {
                out.localOffset = Number(buf.readBigUInt64LE(q));
                q += 8;
            }
            return out;
        }
        p = body + size;
    }
    return cur;
}

function findEndOfCentralDirectory(buf) {
    const minPos = Math.max(0, buf.length - 0xffff - 22);
    for (let p = buf.length - 22; p >= minPos; p--) {
        if (buf.readUInt32LE(p) === EOCD_SIG) return p;
    }
    throw new Error('not a ZIP archive (no end-of-central-directory record)');
}

function readZipEntry(buf, entry) {
    const p = entry.localOffset;
    if (buf.readUInt32LE(p) !== LFH_SIG) {
        throw new Error(`malformed local header for ${entry.name}`);
    }
    const nameLength = buf.readUInt16LE(p + 26);
    const extraLength = buf.readUInt16LE(p + 28);
    const start = p + 30 + nameLength + extraLength;
    const raw = buf.subarray(start, start + entry.compressedSize);

    if (entry.method === 0) return Buffer.from(raw);
    if (entry.method === 8) {
        const out = zlib.inflateRawSync(raw);
        if (entry.uncompressedSize && out.length !== entry.uncompressedSize) {
            throw new Error(
                `inflated size mismatch for ${entry.name}: ${out.length} != ${entry.uncompressedSize}`,
            );
        }
        return out;
    }
    throw new Error(`unsupported ZIP compression method ${entry.method} for ${entry.name}`);
}

// ────────────────────────────────────────────────────────────── ELF parsing
//
// Program headers only: for each PT_LOAD the `p_align` field must be a positive
// multiple of 16 KB. Layouts are from the ELF spec (32-bit and 64-bit differ).

function checkElf(buf) {
    if (buf.length < 0x40) return { ok: false, reason: 'file too small to be an ELF object', loads: [] };
    if (!(buf[0] === 0x7f && buf[1] === 0x45 && buf[2] === 0x4c && buf[3] === 0x46)) {
        return { ok: false, reason: 'not an ELF object (bad magic)', loads: [] };
    }
    const is64 = buf[4] === 2;
    const isLE = buf[5] !== 2; // EI_DATA: 1 = little-endian
    if (!isLE) return { ok: false, reason: 'big-endian ELF is not supported', loads: [] };

    let phoff;
    let phentsize;
    let phnum;
    if (is64) {
        phoff = Number(buf.readBigUInt64LE(0x20));
        phentsize = buf.readUInt16LE(0x36);
        phnum = buf.readUInt16LE(0x38);
    } else {
        phoff = buf.readUInt32LE(0x1c);
        phentsize = buf.readUInt16LE(0x2a);
        phnum = buf.readUInt16LE(0x2c);
    }

    const loads = [];
    for (let i = 0; i < phnum; i++) {
        const o = phoff + i * phentsize;
        if (o + phentsize > buf.length) return { ok: false, reason: 'program header table is truncated', loads };
        const type = buf.readUInt32LE(o);
        if (type !== PT_LOAD) continue;
        const align = is64 ? Number(buf.readBigUInt64LE(o + 48)) : buf.readUInt32LE(o + 28);
        loads.push(align);
    }

    if (loads.length === 0) return { ok: false, reason: 'no PT_LOAD segment', loads };

    const bad = loads.filter((a) => !(a > 0 && a % PAGE_SIZE_16KB === 0));
    if (bad.length > 0) {
        const shown = bad.map((a) => `0x${a.toString(16)}`).join(', ');
        return {
            ok: false,
            reason: `${bad.length} of ${loads.length} PT_LOAD segment(s) not 16KB aligned: ${shown}`,
            loads,
        };
    }
    return { ok: true, reason: 'all PT_LOAD segments are 16KB aligned', loads };
}

main();
