#!/usr/bin/env node
/**
 * Guard: native release APKs with `-PstripIsland=true` must not ship the React
 * fallback island under assets/webapp/.
 *
 * Usage: node scripts/check-native-apk-assets.js <app.apk>
 * Exit 0 = OK, 1 = forbidden paths present, 2 = usage/I/O error.
 */
'use strict';

const fs = require('fs');

const FORBIDDEN_PREFIXES = ['assets/webapp/'];

const EOCD_SIG = 0x06054b50;
const ZIP64_EOCD_LOCATOR_SIG = 0x07064b50;
const ZIP64_EOCD_SIG = 0x06064b50;
const CDH_SIG = 0x02014b50;

function findEndOfCentralDirectory(buf) {
    const minPos = Math.max(0, buf.length - 0xffff - 22);
    for (let p = buf.length - 22; p >= minPos; p--) {
        if (buf.readUInt32LE(p) === EOCD_SIG) return p;
    }
    throw new Error('not a ZIP archive (no end-of-central-directory record)');
}

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

function listZipEntryNames(buf) {
    const eocd = findEndOfCentralDirectory(buf);
    let total = buf.readUInt16LE(eocd + 10);
    let cdOffset = buf.readUInt32LE(eocd + 16);
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
    const names = [];
    let p = cdOffset;
    for (let i = 0; i < total; i++) {
        if (p + 46 > buf.length || buf.readUInt32LE(p) !== CDH_SIG) {
            throw new Error(`malformed central directory at offset ${p}`);
        }
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
        names.push(name);
        p += 46 + nameLength + extraLength + commentLength;
    }
    return names;
}

function main() {
    const apkPath = process.argv[2];
    if (!apkPath) {
        console.error('usage: node scripts/check-native-apk-assets.js <app.apk>');
        process.exit(2);
    }
    const buf = fs.readFileSync(apkPath);
    const names = listZipEntryNames(buf);
    const forbidden = names.filter((n) =>
        FORBIDDEN_PREFIXES.some((prefix) => n.startsWith(prefix) || n === prefix.slice(0, -1)),
    );
    if (forbidden.length > 0) {
        console.error('[check-native-apk-assets] forbidden entries:', forbidden.slice(0, 20).join(', '));
        process.exit(1);
    }
    console.log('[check-native-apk-assets] OK (no assets/webapp/)');
}

main();
